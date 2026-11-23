package cn.nuist.pocket.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalTime
import kotlinx.serialization.json.*

internal class CourseOverrideStore(context: Context, private val track: StudentTrack) {
    private val file = AtomicFile(File(context.filesDir, "course-overrides-${track.name}.json"))

    fun load(semester: String): CourseOverrides = synchronized(lock) {
        read()[semester]?.jsonObject?.let(::decode) ?: CourseOverrides()
    }

    fun update(semester: String, change: (CourseOverrides) -> CourseOverrides): CourseOverrides {
        lateinit var result: CourseOverrides
        schoolSnapshots.mutate(track) {
            synchronized(lock) {
                val terms = read()
                result = change(terms[semester]?.jsonObject?.let(::decode) ?: CourseOverrides())
                val document = JsonObject(terms + (semester to encode(result))).toString()
                val output = file.startWrite()
                try {
                    output.write(document.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                    throw error
                }
            }
        }
        return result
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun read(): JsonObject {
        val bytes = try { file.readFully() } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return JsonObject(emptyMap())
        }
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    private companion object { val lock = Any() }
}

private fun encode(rules: CourseOverrides) = buildJsonObject {
    putJsonObject("appearances") {
        rules.appearances.forEach { (key, value) -> putJsonObject(key) {
            value.shortName?.let { put("shortName", it) }
            value.customColorHex?.let { put("customColorHex", it) }
        } }
    }
    putJsonArray("sessions") { rules.sessions.values.forEach { value -> addJsonObject {
        putJsonObject("origin") {
            put("courseKey", value.origin.courseKey)
            put("week", value.origin.week)
            put("weekday", value.origin.weekday)
            put("firstPeriod", value.origin.firstPeriod)
            put("lastPeriod", value.origin.lastPeriod)
        }
        put("action", value.action.name)
        if (value.action == SessionAction.RESCHEDULE) {
            put("targetWeek", value.targetWeek)
            put("targetWeekday", value.targetWeekday)
            put("targetFirstPeriod", value.targetFirstPeriod)
            put("targetLastPeriod", value.targetLastPeriod)
            value.targetStart?.let { put("targetStart", it.toString()) }
            value.targetEnd?.let { put("targetEnd", it.toString()) }
            value.targetRoom?.let { put("targetRoom", it) }
        }
    } } }
    putJsonArray("customCourses") { rules.customCourses.forEach { value -> addJsonObject {
        put("id", value.id)
        put("name", value.name)
        put("room", value.room)
        put("teacher", value.teacher)
        put("weeks", JsonArray(value.weeks.sorted().map(::JsonPrimitive)))
        put("weekday", value.weekday)
        put("firstPeriod", value.firstPeriod)
        put("lastPeriod", value.lastPeriod)
        value.start?.let { put("start", it.toString()) }
        value.end?.let { put("end", it.toString()) }
        value.customColorHex?.let { put("customColorHex", it) }
    } } }
}

private fun decode(json: JsonObject): CourseOverrides {
    val appearances = json.getValue("appearances").jsonObject.mapValues { (_, element) ->
        val row = element.jsonObject
        CourseAppearanceOverride(row.text("shortName"), row.text("customColorHex"))
    }
    val sessions = json.getValue("sessions").jsonArray.map { element ->
        val row = element.jsonObject
        val source = row.getValue("origin").jsonObject
        val origin = SessionOrigin(source.text("courseKey")!!, source.number("week"), source.number("weekday"),
            source.number("firstPeriod"), source.number("lastPeriod"))
        val action = SessionAction.valueOf(row.text("action")!!)
        if (action == SessionAction.SUSPEND) SessionException(origin, action)
        else SessionException(origin, action, row.number("targetWeek"), row.number("targetWeekday"),
            row.number("targetFirstPeriod"), row.number("targetLastPeriod"),
            row.text("targetStart")?.let(LocalTime::parse), row.text("targetEnd")?.let(LocalTime::parse), row.text("targetRoom"))
    }.associateBy { it.origin }
    val customs = json.getValue("customCourses").jsonArray.map { element ->
        val row = element.jsonObject
        CustomCourse(row.text("id")!!, row.text("name")!!, row.text("room")!!, row.text("teacher")!!,
            row.getValue("weeks").jsonArray.map { it.jsonPrimitive.int }.toSet(),
            row.number("weekday"), row.number("firstPeriod"), row.number("lastPeriod"),
            row.text("start")?.let(LocalTime::parse), row.text("end")?.let(LocalTime::parse), row.text("customColorHex"))
    }
    return CourseOverrides(appearances, sessions, customs)
}

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content
private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
