package cn.nuist.pocket.data

import kotlinx.serialization.json.*
import java.time.*
import java.time.temporal.ChronoUnit

class SchoolDataException(message: String) : IllegalArgumentException(message)

data class Period(val number: Int, val start: LocalTime, val end: LocalTime, val enabled: Boolean)
data class CourseSlot(
    val id: String, val code: String, val name: String, val teacher: String,
    val room: String, val weekday: Int?, val firstPeriod: Int?, val lastPeriod: Int?,
    val weeks: Set<Int>, val start: LocalTime?, val end: LocalTime?, val semester: String,
    val shortName: String? = null, val customColorHex: String? = null,
    val rescheduledFrom: SessionOrigin? = null, val isCustom: Boolean = false,
    val locallyRemovedWeeks: Set<Int> = emptySet(),
) {
    val displayName: String get() = shortName ?: name
    val isRescheduled: Boolean get() = rescheduledFrom != null
    val arranged: Boolean get() = weekday != null && firstPeriod != null && lastPeriod != null && weeks.isNotEmpty()
    fun occurs(week: Int, day: Int) = arranged && week in weeks && weekday == day
    fun times(periods: List<Period>): Pair<LocalTime?, LocalTime?> =
        (start ?: periods.find { it.number == firstPeriod }?.start) to
            (end ?: periods.find { it.number == lastPeriod }?.end)
}

enum class Occupancy { NO_MARK, OCCUPIED, UNKNOWN }

data class Classroom(
    val code: String, val name: String, val building: String, val type: String,
    val floor: Int?, val seats: Int?, val cells: Map<Int, String?>,
) {
    private val decodedCells = cells.mapValues { decodeOccupancy(it.value) }
    fun status(period: Int): Occupancy = decodedCells[period] ?: Occupancy.UNKNOWN
    fun status(range: IntRange): Occupancy {
        if (range.isEmpty()) return Occupancy.UNKNOWN
        var hasUnknown = false
        for (period in range) {
            when (status(period)) {
                Occupancy.OCCUPIED -> return Occupancy.OCCUPIED
                Occupancy.UNKNOWN -> hasUnknown = true
                Occupancy.NO_MARK -> Unit
            }
        }
        return if (hasUnknown) Occupancy.UNKNOWN else Occupancy.NO_MARK
    }
}

/** 只识别已观察到的0/1标记；不推断01/04对应的业务类型。无标记不等于开放。 */
private val OccupancyToken = Regex("[01]_[0-9]+")
fun decodeOccupancy(value: String?): Occupancy {
    if (value.isNullOrBlank()) return Occupancy.UNKNOWN
    val tokens = value.split(',')
    if (tokens.any { !it.matches(OccupancyToken) }) return Occupancy.UNKNOWN
    return if (tokens.any { it.startsWith("1_") }) Occupancy.OCCUPIED else Occupancy.NO_MARK
}

data class ClassroomPage(val rows: List<Classroom>, val total: Int?, val page: Int, val pageSize: Int) {
    val complete: Boolean get() = total != null && page == 1 && rows.size >= total
}

enum class ExamState { UPCOMING, ONGOING, ENDED, UNARRANGED }
data class Exam(
    val id: String, val name: String, val batch: String, val date: LocalDate?,
    val start: LocalTime?, val end: LocalTime?, val room: String, val seat: String?, val note: String?,
) {
    fun state(now: LocalDateTime): ExamState {
        if (date == null || start == null || end == null) return ExamState.UNARRANGED
        return when {
            now < date.atTime(start) -> ExamState.UPCOMING
            now < date.atTime(end) -> ExamState.ONGOING
            else -> ExamState.ENDED
        }
    }
}

data class Semester(val code: String, val firstMonday: LocalDate) {
    init { require(firstMonday.dayOfWeek == DayOfWeek.MONDAY) { "第一周起点需要是周一" } }
    fun weekOn(date: LocalDate): Int = Math.floorDiv(ChronoUnit.DAYS.between(firstMonday, date).toInt(), 7) + 1
    fun dateOf(week: Int, weekday: Int): LocalDate {
        require(week >= 1 && weekday in 1..7)
        return firstMonday.plusWeeks((week - 1).toLong()).plusDays((weekday - 1).toLong())
    }
}

/** Keep known dates/start times visible even if the school has not supplied an end time. */
fun examTimeLabel(exam: Exam): String {
    if (exam.date == null && exam.start == null && exam.end == null) return "考试时间尚未排定"
    val clock = when {
        exam.start != null && exam.end != null -> "${exam.start}–${exam.end}"
        exam.start != null -> "${exam.start}开始，结束时间待定"
        exam.end != null -> "开始时间待定，${exam.end}结束"
        else -> "时刻待定"
    }
    return "${exam.date?.toString() ?: "日期待定"}  $clock"
}

object SchoolParser {
    private val json = Json { ignoreUnknownKeys = true }
    private fun module(raw: String, name: String): JsonObject {
        val root = try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
            ?: throw SchoolDataException("文件不是有效的JSON响应")
        if (root.string("code") != "0") throw SchoolDataException("接口未返回成功，请确认登录状态与导出内容")
        val data = root["datas"] as? JsonObject
        return data?.get(name) as? JsonObject ?: throw SchoolDataException("响应缺少$name 数据")
    }
    private fun rows(module: JsonObject): List<JsonObject> {
        val ext = module["extParams"] as? JsonObject
        if (ext?.get("code") != null && ext.string("code") != "1")
            throw SchoolDataException("查询未成功，不能将此响应当作空列表")
        val rows = module["rows"] as? JsonArray ?: throw SchoolDataException("响应缺少记录列表")
        return rows.map { it as? JsonObject ?: throw SchoolDataException("记录格式不正确") }
    }
    fun currentSemester(raw: String): String = rows(module(raw, "dqxnxq")).firstOrNull()?.string("DM")
        ?.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{4}-[12]")) }
        ?: throw SchoolDataException("无法确认当前学期")

    fun safeExamJson(exams: List<Exam>): String = buildJsonObject {
        put("code", "0")
        putJsonObject("datas") { putJsonObject("cxxsksap") { putJsonArray("rows") {
            exams.forEach { exam -> addJsonObject {
                put("KSRWID", exam.id); put("KCM", exam.name); put("KSMC", exam.batch); put("JASMC", exam.room)
                exam.date?.let { put("KSRQ", it.toString()) }; exam.start?.let { put("KSKSSJ", it.toString()) }
                exam.end?.let { put("KSJSSJ", it.toString()) }; exam.seat?.let { put("ZWH", it) }; exam.note?.let { put("BZ", it) }
            } }
        } } }
    }.toString()
    fun periods(raw: String): List<Period> = rows(module(raw, "jc")).map { row ->
        val number = row.int("DM") ?: throw SchoolDataException("节次缺少编号")
        val start = row.time("KSSJ") ?: throw SchoolDataException("节次缺少开始时间")
        val end = row.time("JSSJ") ?: throw SchoolDataException("节次缺少结束时间")
        if (number < 1 || end <= start) throw SchoolDataException("节次范围不正确")
        Period(number, start, end, row.int("SFSY") == 1)
    }.also { list ->
        if (list.isEmpty()) throw SchoolDataException("学校未返回节次时间，本次未保存")
        if (list.map { it.number }.distinct().size != list.size) throw SchoolDataException("节次编号重复")
    }.sortedBy { it.number }

    fun courses(raw: String): List<CourseSlot> = rows(module(raw, "cxxszhxqkb")).mapIndexed { index, row ->
        val name = row.string("KCM") ?: throw SchoolDataException("第${index + 1}条记录缺少课程名")
        val mask = row.string("SKZC")
        val weeks = parseWeeks(mask)
        val first = row.int("KSJC")?.takeIf { it > 0 }
        val last = row.int("JSJC")?.takeIf { it > 0 }
        if (first != null && last != null && last < first) throw SchoolDataException("课程结束节次早于开始节次")
        val start = row.time("KSSJ")
        val end = row.time("JSSJ")
        if (start != null && end != null && end <= start) throw SchoolDataException("课程起止时间不正确")
        CourseSlot(
            row.string("WID") ?: row.string("KBID") ?: "import-$index",
            row.string("KCH").orEmpty(), name, row.string("SKJS").orEmpty(),
            row.string("JASMC").orEmpty(), row.int("SKXQ")?.takeIf { it in 1..7 },
            first, last, weeks, start, end, row.string("XNXQDM").orEmpty(),
        )
    }

    fun classrooms(raw: String): ClassroomPage {
        val data = module(raw, "cxjsqk")
        val rooms = rows(data).map { row ->
            Classroom(
                row.string("JASDM") ?: throw SchoolDataException("教室缺少编码"),
                row.string("JASMC") ?: "未命名教室", row.string("JXLDM").orEmpty(),
                row.string("JASLXDM_DISPLAY").orEmpty(), row.int("LC"), row.int("SKZWS"),
                (1..20).associateWith { row.string("JC$it") },
            )
        }
        return ClassroomPage(rooms, data.int("totalSize")?.takeIf { it >= 0 },
            data.int("pageNumber") ?: 1, data.int("pageSize") ?: rooms.size)
    }

    fun exams(raw: String): List<Exam> = rows(module(raw, "cxxsksap")).mapIndexed { index, row ->
        val date = row.date("KSRQ")
        val start = row.time("KSKSSJ")
        val end = row.time("KSJSSJ")
        if (start != null && end != null && end <= start) throw SchoolDataException("考试起止时间不正确")
        Exam(row.string("KSRWID") ?: "exam-$index", row.string("KCM") ?: "未命名考试",
            row.string("KSMC").orEmpty(), date, start, end, row.string("JASMC").orEmpty(),
            row.string("ZWH"), row.string("BZ"))
    }

    fun parseWeeks(mask: String?): Set<Int> {
        if (mask.isNullOrBlank()) return emptySet()
        if (!mask.matches(Regex("[01]+")) || mask.length > 64) throw SchoolDataException("上课周次编码不正确")
        return mask.mapIndexedNotNull { index, bit -> if (bit == '1') index + 1 else null }.toSet()
    }

    /** 仅保存课表展示需要的字段，不持久化原始响应中的学号、姓名等。 */
    fun safeCourseJson(courses: List<CourseSlot>): String = buildJsonObject {
        put("code", "0")
        putJsonObject("datas") { putJsonObject("cxxszhxqkb") {
            putJsonArray("rows") { courses.forEach { course -> addJsonObject {
                put("WID", course.id); put("KCH", course.code); put("KCM", course.name)
                put("SKJS", course.teacher); put("JASMC", course.room); put("XNXQDM", course.semester)
                course.weekday?.let { put("SKXQ", it) }
                course.firstPeriod?.let { put("KSJC", it) }; course.lastPeriod?.let { put("JSJC", it) }
                course.start?.let { put("KSSJ", it.toString()) }; course.end?.let { put("JSSJ", it.toString()) }
                put("SKZC", (1..(course.weeks.maxOrNull() ?: 0)).joinToString("") { if (it in course.weeks) "1" else "0" })
            } } }
        } }
    }.toString()
}

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
private fun JsonObject.time(key: String): LocalTime? = string(key)?.let {
    try { LocalTime.parse(it) } catch (_: Exception) { throw SchoolDataException("$key 时间格式不正确") }
}
private fun JsonObject.date(key: String): LocalDate? = string(key)?.let {
    try { LocalDate.parse(it) } catch (_: Exception) { throw SchoolDataException("$key 日期格式不正确") }
}
