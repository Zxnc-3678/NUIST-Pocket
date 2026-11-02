package cn.nuist.pocket.data

import android.annotation.SuppressLint
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object GraduateRequests {
    private const val BASE = "https://nxdyjs.nuist.edu.cn/gmis5/student/"
    private val termId = Regex("[0-9]{1,10}")
    const val PORTAL = BASE + "default/index"
    const val COURSE_ENTRY = BASE + "pygl/xskbcx"
    const val SEMESTERS = BASE + "default/bindterm"
    const val EXAM_ENTRY = BASE + "pygl/kckccx"
    fun courses(term: String): SchoolRequest {
        require(term.matches(termId))
        return SchoolRequest(BASE + "pygl/py_kbcx_ew", mapOf("kblx" to "xs", "termcode" to term))
    }
    fun exams() = SchoolRequest(BASE + "pygl/kckccx_list", emptyMap())
}

/** The school's public rajax.js protocol; this is not an authentication credential. */
object GraduateCodec {
    @SuppressLint("GetInstance") // Required for the observed school's response protocol; never used to store credentials.
    private fun decrypt(value: String, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding") // AES block size: equivalent to PKCS7.
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val bytes = cipher.doFinal(Base64.getDecoder().decode(value))
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private val responseKey: ByteArray by lazy {
        val seed = "sopthsk!#032IJDS".reversed().toByteArray(Charsets.UTF_8)
        val parts = listOf("ND7TLBY9Cx/SdS0R/7dqmg==", "ZXF3+Q3opQHlh6UkTTFRVA==", "==QrlklM2cjROKp18sqnxLd2".reversed())
        try { parts.joinToString("") { decrypt(it, seed) }.toByteArray(Charsets.UTF_8) }
        finally { seed.fill(0) }
    }
    fun decode(raw: String): JsonElement {
        if (raw.length > 4 * 1024 * 1024) throw SchoolDataException("研究生响应超过大小限制")
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.startsWith("<")) throw SchoolLoginRequired()
        val plain = if (text.startsWith("{") || text.startsWith("[")) text else try { decrypt(text, responseKey) }
            catch (_: Exception) { throw SchoolDataException("研究生响应无法还原") }
        val element = try { Json.parseToJsonElement(plain) }
            catch (_: Exception) { throw SchoolDataException("研究生响应不是有效JSON") }
        if (element !is JsonArray && element !is JsonObject) throw SchoolDataException("研究生响应缺少业务数据")
        return element
    }
}

data class GraduateTerm(val id: String, val code: String, val name: String, val selected: Boolean)

class GraduateCalendarRequired(val semesterCode: String, termName: String) :
    Exception("请在设置 → 课表数据中设置研究生${termName}第1周周一，再同步")

object GraduateParser {
    private val termId = Regex("[0-9]{1,10}")
    private val termName = Regex("(\\d{4})-(\\d{4})(秋季|春季)学期")
    private val periodName = Regex("(?:上午|下午|晚上|晚间|晚|第)?\\s*(\\d{1,2})\\s*节?")
    private val lineBreak = Regex("<br\\s*/?>|</(?:p|div)>", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("<[^>]*>")
    private val htmlEntity = Regex("&(?:#x([0-9a-fA-F]{1,6})|#([0-9]{1,7})|([a-zA-Z]+));")
    private val namedEntities = mapOf("nbsp" to " ", "ensp" to " ", "emsp" to " ", "lt" to "<", "gt" to ">",
        "quot" to "\"", "apos" to "'", "amp" to "&", "ndash" to "–", "mdash" to "—", "middot" to "·", "hellip" to "…")
    private val weekRange = Regex("\\s*(\\d{1,2})(?:\\s*[-—~～至]\\s*(\\d{1,2}))?\\s*")
    private val courseHeading = Regex("(?m)^\\s*课程\\s*[:：]")
    private val classHeading = Regex("(?m)^\\s*班级\\s*[:：]")
    private val courseSchedule = Regex("\\[([^\\[\\]\\n]+)周]\\s*(?:[（(]([^）)]*)[）)])?\\s*([^\\[\\]]*?)\\[([^\\[\\]]*)]")
    private val examTime = Regex("(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*(?:[-—–~～至]\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?))?")
    private val examDates = listOf("uuuu-MM-dd", "uuuu/M/d", "uuuu年M月d日").map {
        DateTimeFormatter.ofPattern(it, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    }
    private val minuteClock = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    private val secondClock = DateTimeFormatter.ofPattern("H:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

    private fun JsonObject.text(key: String): String? {
        val value = get(key) ?: return null
        if (value is JsonNull) return null
        if (value !is JsonPrimitive) throw SchoolDataException("研究生字段格式不正确")
        return value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
    private fun JsonObject.cell(key: String): String? = (get(key) as? JsonPrimitive)
        ?.takeUnless { it is JsonNull }?.contentOrNull?.takeUnless { it.isBlank() }
    private fun termRows(data: JsonElement): List<JsonObject> {
        val rows = data as? JsonArray ?: throw SchoolDataException("研究生学期字典格式不正确")
        if (rows.isEmpty() || rows.size > 512) throw SchoolDataException("研究生学期字典为空或过大")
        val ids = hashSetOf<String>()
        return rows.map { element ->
            val row = element as? JsonObject ?: throw SchoolDataException("研究生学期记录格式不正确")
            val id = row.text("termcode")?.takeIf { it.matches(termId) }
                ?: throw SchoolDataException("研究生学期缺少编码")
            if (!ids.add(id)) throw SchoolDataException("研究生学期编码重复")
            row
        }
    }
    private fun parseTerm(row: JsonObject): GraduateTerm {
            val id = checkNotNull(row.text("termcode"))
            val name = row.text("termname") ?: throw SchoolDataException("研究生学期缺少名称")
            val match = termName.matchEntire(name)
                ?: throw SchoolDataException("研究生学期名称暂不支持")
            if (match.groupValues[2].toInt() != match.groupValues[1].toInt() + 1) throw SchoolDataException("研究生学年不正确")
            return GraduateTerm(id, "${match.groupValues[1]}-${match.groupValues[2]}-${if (match.groupValues[3] == "秋季") 1 else 2}", name,
                row.text("selected") in setOf("true", "1"))
    }
    fun terms(data: JsonElement): List<GraduateTerm> = termRows(data).map(::parseTerm)
    fun selectedTerm(data: JsonElement): GraduateTerm {
        val row = termRows(data).filter { it.text("selected") in setOf("true", "1") }.singleOrNull()
            ?: throw SchoolDataException("无法唯一确认研究生当前学期")
        return parseTerm(row) // Unselected historical term names do not define the current term.
    }

    fun scheduleRows(data: JsonElement): List<JsonObject> {
        val root = data as? JsonObject ?: throw SchoolDataException("研究生课表格式不正确")
        val rows = root["rows"] as? JsonArray ?: throw SchoolDataException("研究生课表缺少行列表")
        if (rows.size > 100) throw SchoolDataException("研究生课表行数异常")
        return rows.map { it as? JsonObject ?: throw SchoolDataException("研究生课表行格式不正确") }.also { list ->
            list.forEach { row ->
                if (row.text("mc") == null) throw SchoolDataException("研究生课表缺少节次名称")
                (1..7).forEach { row.text("z$it") }
            }
        }
    }

    private fun period(row: JsonObject): Int? {
        val label = row.text("mc").orEmpty()
        if (label == "无节次") return null
        return periodName.matchEntire(label)
            ?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..20 }
            ?: throw SchoolDataException("研究生节次名称暂不支持")
    }
    private fun plainHtml(value: String): String {
        if (value.length > 100_000) throw SchoolDataException("研究生课程文本过长")
        return htmlEntity.replace(value.replace(lineBreak, "\n").replace(htmlTag, "")) { match ->
            val number = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull(16)
                ?: match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            if (number != null && Character.isValidCodePoint(number) && number > 0 && number !in 0xD800..0xDFFF)
                String(Character.toChars(number))
            else namedEntities[match.groupValues[3]] ?: match.value
        }.trim()
    }
    fun weeks(value: String, mode: String): Set<Int> {
        val result = linkedSetOf<Int>()
        value.replace('，', ',').replace('、', ',').split(',').forEach { token ->
            val m = weekRange.matchEntire(token)
                ?: throw SchoolDataException("研究生上课周次暂不支持")
            val start = m.groupValues[1].toInt(); val end = m.groupValues[2].toIntOrNull() ?: start
            if (start !in 1..64 || end !in start..64) throw SchoolDataException("研究生上课周次越界")
            result.addAll(start..end)
        }
        return when (mode.trim()) {
            "", "连续", "不连续", "单双周" -> result
            "单", "单周" -> result.filter { it % 2 == 1 }.toSet()
            "双", "双周" -> result.filter { it % 2 == 0 }.toSet()
            else -> throw SchoolDataException("研究生单双周标记暂不支持")
        }.also { if (it.isEmpty()) throw SchoolDataException("研究生课程没有有效周次") }
    }
    fun courses(data: JsonElement, semester: String): List<CourseSlot> {
        val digest = MessageDigest.getInstance("SHA-256")
        val rows = scheduleRows(data)
        val numbers = rows.map(::period)
        if (numbers.filterNotNull().distinct().size != numbers.count { it != null }) throw SchoolDataException("研究生节次重复")
        val result = mutableListOf<CourseSlot>()
        for (day in 1..7) {
            var index = 0
            while (index < rows.size) {
                val raw = rows[index].cell("z$day")
                if (raw == null) { index++; continue }
                var last = index
                // Exact adjacent repeated contents are merged by the school's mergeCells_table.
                while (last + 1 < rows.size && rows[last + 1].cell("z$day") == raw) last++
                val firstPeriod = numbers[index]; val lastPeriod = numbers[last]
                if ((index..last).any { numbers[it] != firstPeriod?.plus(it - index) })
                    throw SchoolDataException("研究生课程跨越无法识别的节次")
                val text = plainHtml(raw)
                val heads = courseHeading.findAll(text).toList()
                if (heads.isEmpty() || text.substring(0, heads.first().range.first).isNotBlank()) throw SchoolDataException("研究生课程名称格式暂不支持")
                heads.forEachIndexed { n, head ->
                    val chunk = text.substring(head.range.last + 1, heads.getOrNull(n + 1)?.range?.first ?: text.length).trim()
                    val nameEnd = classHeading.find(chunk)?.range?.first ?: chunk.indexOf('[').takeIf { it >= 0 }
                        ?: throw SchoolDataException("研究生课程缺少周次安排")
                    val name = chunk.substring(0, nameEnd).trim().takeIf { it.isNotEmpty() }
                        ?: throw SchoolDataException("研究生课程缺少名称")
                    val body = chunk.substring(nameEnd).trim()
                    val start = body.indexOf('[')
                    if (start < 0) throw SchoolDataException("研究生课程缺少周次安排")
                    val prefix = body.substring(0, start).trim()
                    if (prefix.isNotBlank() && !prefix.startsWith("班级:") && !prefix.startsWith("班级：")) throw SchoolDataException("研究生课程字段暂不支持")
                    val schedule = body.substring(start)
                    val matches = courseSchedule.findAll(schedule).toList()
                    var consumed = 0
                    if (matches.isEmpty()) throw SchoolDataException("研究生课程安排暂不支持")
                    for (match in matches) {
                        if (schedule.substring(consumed, match.range.first).isNotBlank()) throw SchoolDataException("研究生课程存在无法识别的安排")
                        val teacher = match.groupValues[3].trim(); val room = match.groupValues[4].trim()
                        val activeWeeks = weeks(match.groupValues[1], match.groupValues[2])
                        val id = recordId(digest, "graduate-", listOf(semester, day, firstPeriod, lastPeriod, name, teacher, room, activeWeeks.sorted().joinToString(",")))
                        result += CourseSlot(id, "", name, teacher, room, day, firstPeriod, lastPeriod, activeWeeks, null, null, semester)
                        consumed = match.range.last + 1
                    }
                    if (schedule.substring(consumed).isNotBlank()) throw SchoolDataException("研究生课程末尾存在无法识别的安排")
                }
                index = last + 1
            }
        }
        return result.distinctBy { it.id }
    }

    fun exams(data: JsonElement): List<Exam> {
        val digest = MessageDigest.getInstance("SHA-256")
        val rows = data as? JsonArray ?: throw SchoolDataException("研究生考试格式不正确")
        if (rows.size > 2000) throw SchoolDataException("研究生考试条数异常")
        return rows.map { element ->
            val row = element as? JsonObject ?: throw SchoolDataException("研究生考试记录格式不正确")
            val name = row.text("kcmc") ?: throw SchoolDataException("研究生考试缺少课程名称")
            val rawDate = row.text("ksrq")
            val date = rawDate?.let { value ->
                examDates.firstNotNullOfOrNull { format ->
                    runCatching { LocalDate.parse(value, format) }.getOrNull()
                } ?: throw SchoolDataException("研究生考试日期格式待核对")
            }
            val rawTime = row.text("kssj")
            val range = rawTime?.let { examTime.matchEntire(it) }
            if (rawTime != null && range == null) throw SchoolDataException("研究生考试时间格式待核对")
            fun clock(value: String?): LocalTime? = value?.takeIf { it.isNotEmpty() }?.let {
                runCatching { LocalTime.parse(it, if (it.count { c -> c == ':' } == 2) secondClock else minuteClock) }
                    .getOrElse { throw SchoolDataException("研究生考试时刻不正确") }
            }
            val start = clock(range?.groupValues?.get(1)); val end = clock(range?.groupValues?.get(2))
            if (start != null && end != null && end <= start) throw SchoolDataException("研究生考试起止时间不正确")
            val id = recordId(digest, "graduate-exam-", listOf(row.text("termname"), row.text("kcbh"), name, date, start, end, row.text("dz"), row.text("zwh")))
            Exam(id, name, row.text("termname").orEmpty(), date, start, end, row.text("dz").orEmpty(), row.text("zwh"),
                if (start != null && end == null) "学校仅提供开始时间：$rawTime，结束时间待确认" else null)
        }
    }
    private fun recordId(digest: MessageDigest, prefix: String, fields: List<Any?>): String {
        val identity = fields.joinToString("") { value -> value?.toString()?.let { "${it.length}:$it" } ?: "-1:" }
        return prefix + digest.digest(identity.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
    }
}

class GraduateApi(
    private val connection: SchoolTransport,
    private val monday: (String) -> LocalDate?,
    private val campusPeriods: () -> List<Period>,
) {
    private suspend fun schedule(): Pair<GraduateTerm, JsonElement> {
        connection.get(GraduateRequests.COURSE_ENTRY)
        val term = GraduateParser.selectedTerm(GraduateCodec.decode(connection.get(GraduateRequests.SEMESTERS)))
        val data = GraduateCodec.decode(connection.post(GraduateRequests.courses(term.id)))
        GraduateParser.scheduleRows(data) // A dictionary alone is not proof of a student session.
        return term to data
    }
    suspend fun verifyLogin(): String = schedule().first.code
    suspend fun sync(): LiveSchoolData {
        val (term, data) = schedule()
        val firstMonday = monday(term.code) ?: throw GraduateCalendarRequired(term.code, term.name)
        val periods = campusPeriods().takeIf { it.isNotEmpty() }
            ?: throw SchoolConnectionError("研究生节次时间尚未就绪，请稍后重试")
        val courses = GraduateParser.courses(data, term.code)
        val periodNumbers = periods.mapTo(hashSetOf()) { it.number }
        if (courses.any { c -> c.arranged && (c.firstPeriod!!..c.lastPeriod!!).any { it !in periodNumbers } })
            throw SchoolDataException("研究生课表引用了作息表之外的节次")
        val exams = GraduateParser.exams(GraduateCodec.decode(connection.post(GraduateRequests.exams())))
        return LiveSchoolData(Semester(term.code, firstMonday), periods, courses, exams, java.time.Instant.now().toString())
    }
}
