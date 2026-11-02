package cn.nuist.pocket.data

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

class SchoolLoginRequired : Exception("学校会话已失效，请重新登录")
class SchoolConnectionError(message: String) : Exception(message)

enum class StudyBuilding(val title: String, val code: String) {
    MINGDE("明德楼", "1-202"), WENDE("文德楼", "1-120"),
    // Confirmed against all 31 rows of the school's jxlcx directory, 2026-10-22.
    BINJIANG("滨江楼", "1-301"), LANJIANG("揽江楼", "1-302"),
    YUEJIANG("阅江楼", "1-303"), LINJIANG("临江楼", "1-331")
}

object SchoolOrigins {
    fun allowed(url: String, track: StudentTrack = StudentTrack.UNDERGRADUATE): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in track.loginHosts && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)
}

interface SchoolTransport {
    suspend fun get(url: String): String
    suspend fun post(request: SchoolRequest): String
}

/** 每次只读取目标URL自身的Cookie；不保存密码，不输出Cookie或响应正文到日志。 */
class SchoolConnection(private val track: StudentTrack = StudentTrack.UNDERGRADUATE, userAgent: () -> String) : SchoolTransport {
    private val requestUserAgent by lazy(userAgent)
    override suspend fun get(url: String): String = exchange(url, null)
    override suspend fun post(request: SchoolRequest): String = exchange(request.url, request.encodedForm())

    private suspend fun exchange(initialUrl: String, form: String?): String {
        var currentUrl = initialUrl
        repeat(8) {
            coroutineContext.ensureActive()
            if (!SchoolOrigins.allowed(currentUrl, track)) throw SchoolConnectionError("学校跳转到了暂不支持的地址")
            val (cookie, agent) = withContext(Dispatchers.Main) {
                CookieManager.getInstance().getCookie(currentUrl) to requestUserAgent
            }
            val response = withContext(Dispatchers.IO) {
                val connection = URL(currentUrl).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 20_000
                    connection.useCaches = false
                    connection.setRequestProperty("User-Agent", agent)
                    connection.setRequestProperty("Referer", when {
                        track == StudentTrack.GRADUATE && initialUrl.endsWith("kckccx_list") -> GraduateRequests.EXAM_ENTRY
                        track == StudentTrack.GRADUATE -> GraduateRequests.COURSE_ENTRY
                        initialUrl.contains("/wdkb/") -> SchoolRequests.COURSE_ENTRY
                        initialUrl.contains("/kxjas/") -> SchoolRequests.ROOM_ENTRY
                        initialUrl.contains("/studentWdksapApp/") -> SchoolRequests.EXAM_ENTRY
                        else -> SchoolRequests.PORTAL
                    })
                    if (!cookie.isNullOrBlank()) connection.setRequestProperty("Cookie", cookie)
                    if (form != null) {
                        connection.requestMethod = "POST"
                        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                        connection.setRequestProperty("Origin", "https://${track.schoolHost}")
                        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        connection.doOutput = true
                        connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                    }
                    val code = connection.responseCode
                    val cookies = connection.headerFields.filter { (key, _) -> key.equals("Set-Cookie", true) }.values.flatten()
                    val location = connection.getHeaderField("Location")
                    val body = if (code in 200..299) connection.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (out.size() + n > 4 * 1024 * 1024) throw SchoolConnectionError("学校返回的数据过大，本次查询已停止")
                            out.write(buffer, 0, n)
                        }
                        out.toString("UTF-8")
                    } else ""
                    HttpReply(code, cookies, location, body, connection.getHeaderField("sessionstatus") == "timeout")
                } catch (error: CancellationException) { throw error }
                catch (error: SchoolConnectionError) { throw error }
                catch (_: Exception) { throw SchoolConnectionError("连接学校失败，请检查网络后重试") }
                finally { connection.disconnect() }
            }
            withContext(Dispatchers.Main) {
                val manager = CookieManager.getInstance()
                for (cookieValue in response.cookies) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        manager.setCookie(currentUrl, cookieValue) { if (continuation.isActive) continuation.resume(Unit) }
                    }
                }
                manager.flush()
            }
            if (response.code in listOf(401, 403) || response.sessionExpired) throw SchoolLoginRequired()
            if (response.code in 300..399) {
                if (form != null) throw SchoolLoginRequired()
                currentUrl = URI(currentUrl).resolve(response.location ?: throw SchoolLoginRequired()).toString()
            } else {
                if (response.code !in 200..299) throw SchoolConnectionError("学校服务暂不可用（${response.code}），请稍后重试")
                if (URI(currentUrl).host == "authserver.nuist.edu.cn") throw SchoolLoginRequired()
                if (form != null && if (track == StudentTrack.GRADUATE) response.body.trimStart().startsWith("<") else !response.body.trimStart().startsWith("{")) throw SchoolLoginRequired()
                return response.body
            }
        }
        throw SchoolLoginRequired()
    }
    private data class HttpReply(val code: Int, val cookies: List<String>, val location: String?, val body: String, val sessionExpired: Boolean)
}

data class LiveSchoolData(val semester: Semester, val periods: List<Period>, val courses: List<CourseSlot>, val exams: List<Exam>, val fetchedAt: String)
data class RoomQueryResult(val rooms: List<Classroom>, val reportedTotal: Int, val date: LocalDate, val building: StudyBuilding)

class SchoolApi(private val connection: SchoolTransport, private val track: StudentTrack = StudentTrack.UNDERGRADUATE,
                graduateMonday: (String) -> LocalDate? = { null }, graduatePeriods: () -> List<Period> = { emptyList() }) {
    private val graduate = GraduateApi(connection, graduateMonday, graduatePeriods)
    /** Retry a read-only operation once through the existing SSO session. No credentials,
     * background refresh, or login-page completion; a real expiry still needs the user. */
    private suspend fun <T> withExistingSession(block: suspend () -> T): T {
        return try { block() } catch (_: SchoolLoginRequired) {
            connection.get(if (track == StudentTrack.GRADUATE) GraduateRequests.PORTAL else SchoolRequests.PORTAL)
            block()
        }
    }
    private suspend fun studentSchedule(): Pair<String, List<CourseSlot>> {
        connection.get(SchoolRequests.COURSE_ENTRY)
        val raw = connection.post(SchoolRequests.currentSemester())
        val code = SchoolParser.currentSemester(raw)
        // The public current-term dictionary alone is not proof of an authenticated student session.
        val courses = SchoolParser.courses(connection.post(SchoolRequests.courses(code)))
        if (courses.any { it.semester != code }) throw SchoolConnectionError("学校返回了不同学期的课表，本次未保存")
        return code to courses
    }
    suspend fun verifyLogin(): String = if (track == StudentTrack.GRADUATE) graduate.verifyLogin() else studentSchedule().first
    suspend fun sync(): LiveSchoolData = withExistingSession { if (track == StudentTrack.GRADUATE) graduate.sync() else syncOnce() }
    private suspend fun syncOnce(): LiveSchoolData {
        val (code, courses) = studentSchedule()
        val semester = AcademicCalendar.semester(code)
            ?: throw SchoolConnectionError("这个学期的校历起点尚未核对，请先使用课表导入")
        val periods = SchoolParser.periods(connection.post(SchoolRequests.periods()))
        connection.get(SchoolRequests.EXAM_ENTRY)
        val exams = SchoolParser.exams(connection.post(SchoolRequests.exams(code)))
        return LiveSchoolData(semester, periods, courses, exams, java.time.Instant.now().toString())
    }
    suspend fun rooms(semester: Semester, date: LocalDate, building: StudyBuilding): RoomQueryResult {
        if (!track.supportsRooms) throw SchoolConnectionError("研究生模式不使用教室查询")
        return withExistingSession { roomsOnce(semester, date, building) }
    }

    private suspend fun roomsOnce(semester: Semester, date: LocalDate, building: StudyBuilding): RoomQueryResult {
        connection.get(SchoolRequests.ROOM_ENTRY)
        val code = building.code
        val records = linkedMapOf<String, Classroom>()
        var expectedTotal: Int? = null
        // 每页沿用实测值10；有界翻页，避免学校异常响应造成无限请求。
        for (pageNumber in 1..100) {
            coroutineContext.ensureActive()
            val page = SchoolParser.classrooms(connection.post(SchoolRequests.rooms(semester, date, code, pageNumber)))
            val total = page.total ?: throw SchoolConnectionError("学校未返回教室总数，无法确认查询完整性")
            if (expectedTotal != null && total != expectedTotal) throw SchoolConnectionError("查询期间教室列表发生变化，请重试")
            expectedTotal = total
            if (page.rows.any { it.building != code }) throw SchoolConnectionError("学校返回了其他教学楼的数据，本次不显示")
            if (page.rows.map { it.code }.distinct().size != page.rows.size || page.rows.any { it.code in records })
                throw SchoolConnectionError("教室列表出现重复记录，请重新查询")
            val before = records.size
            page.rows.forEach { records[it.code] = it }
            if (records.size > total) throw SchoolConnectionError("教室数量与分页信息不一致，请重试")
            if (records.size >= total) return RoomQueryResult(records.values.toList(), total, date, building)
            if (page.rows.isEmpty() || records.size == before) throw SchoolConnectionError("教室分页不完整，请重新查询")
        }
        throw SchoolConnectionError("教室页数超出范围，本次查询未完成")
    }
}
