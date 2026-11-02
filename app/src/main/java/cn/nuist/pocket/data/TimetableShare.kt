package cn.nuist.pocket.data

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Only timetable data travels in the code; school credentials and personal todos do not. */
internal fun encodeSharedTimetable(schedule: WidgetSchedule): String {
    val json = buildJsonObject {
        put("semester", schedule.semester.code)
        put("firstMonday", schedule.semester.firstMonday.toString())
        put("courses", Json.parseToJsonElement(SchoolParser.safeCourseJson(schedule.courses)))
        putJsonArray("periods") { schedule.periods.forEach { period -> addJsonObject {
            put("number", period.number); put("start", period.start.toString()); put("end", period.end.toString()); put("enabled", period.enabled)
        } } }
        putJsonArray("suspendedDates") { schedule.suspendedDates.sorted().forEach { add(it.toString()) } }
    }.toString()
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).use { it.write(json.toByteArray(Charsets.UTF_8)) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
}

internal fun decodeSharedTimetable(payload: String): WidgetSchedule {
    val bytes = Base64.getUrlDecoder().decode(payload)
    val text = GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
    val json = Json.parseToJsonElement(text).jsonObject
    val semester = Semester(json.getValue("semester").jsonPrimitive.content, LocalDate.parse(json.getValue("firstMonday").jsonPrimitive.content))
    val periods = json.getValue("periods").jsonArray.map { item ->
        val p = item.jsonObject
        Period(p.getValue("number").jsonPrimitive.int, LocalTime.parse(p.getValue("start").jsonPrimitive.content),
            LocalTime.parse(p.getValue("end").jsonPrimitive.content), p.getValue("enabled").jsonPrimitive.boolean)
    }
    return WidgetSchedule(semester, SchoolParser.courses(json.getValue("courses").toString()), periods, "收到的课表",
        suspendedDates = json.getValue("suspendedDates").jsonArray.map { LocalDate.parse(it.jsonPrimitive.content) }.toSet())
}

internal const val TIMETABLE_SHARE_LIFETIME_MS = 30 * 60_000L
internal const val TIMETABLE_QR_PART_SIZE = 2600
internal fun timetableShareFrames(payload: String, now: Long = System.currentTimeMillis()): List<String> {
    val parts = payload.chunked(TIMETABLE_QR_PART_SIZE)
    val id = UUID.randomUUID().toString()
    val expiresAt = now + TIMETABLE_SHARE_LIFETIME_MS
    return parts.mapIndexed { index, part -> "NP1|$id|$expiresAt|${parts.size}|$index|$part" }
}

/** The same time-limited payload in one file, independent of QR capacity. */
internal fun timetableShareFile(payload: String, now: Long = System.currentTimeMillis()): String =
    "NP1|${UUID.randomUUID()}|${now + TIMETABLE_SHARE_LIFETIME_MS}|1|0|$payload"

internal class TimetableShareCollector {
    private var id = ""
    private var count = 0
    private val parts = mutableMapOf<Int, String>()
    val received: Int get() = parts.size
    val total: Int get() = count
    var expired: Boolean = false
        private set
    fun accept(frame: String, now: Long = System.currentTimeMillis()): String? {
        val fields = frame.split('|', limit = 6)
        expired = now >= fields[2].toLong()
        if (expired) { parts.clear(); return null }
        if (id != fields[1]) { id = fields[1]; count = fields[3].toInt(); parts.clear() }
        parts[fields[4].toInt()] = fields[5]
        return if (parts.size == count) (0 until count).joinToString("") { parts.getValue(it) } else null
    }
}
