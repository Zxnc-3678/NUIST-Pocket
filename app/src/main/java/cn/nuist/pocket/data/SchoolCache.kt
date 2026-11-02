package cn.nuist.pocket.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.*
import java.time.LocalDate

class SchoolCache(context: Context, private val track: StudentTrack = AppPreferences(context).studentTrack) {
    private val prefs = context.getSharedPreferences(track.cacheName, Context.MODE_PRIVATE)
    val loginRequired: Boolean get() = prefs.getBoolean("login_required", false)
    fun markLoginRequired() { schoolSnapshots.mutate(track) { prefs.edit { putBoolean("login_required", true) } } }
    fun load(): LiveSchoolData? = runCatching {
        val raw = prefs.getString("snapshot", null) ?: return null
        val root = Json.parseToJsonElement(raw).jsonObject
        LiveSchoolData(
            Semester(root.getValue("semester").jsonPrimitive.content, LocalDate.parse(root.getValue("firstMonday").jsonPrimitive.content)),
            SchoolParser.periods(root.getValue("periods").toString()),
            SchoolParser.courses(root.getValue("courses").toString()),
            SchoolParser.exams(root.getValue("exams").toString()),
            root.getValue("fetchedAt").jsonPrimitive.content,
        )
    }.getOrNull()
    fun save(data: LiveSchoolData, verified: Boolean = true) {
        val safe = buildJsonObject {
            put("semester", data.semester.code); put("firstMonday", data.semester.firstMonday.toString()); put("fetchedAt", data.fetchedAt)
            put("courses", Json.parseToJsonElement(SchoolParser.safeCourseJson(data.courses)))
            put("exams", Json.parseToJsonElement(SchoolParser.safeExamJson(data.exams)))
            putJsonObject("periods") { put("code", "0"); putJsonObject("datas") { putJsonObject("jc") { putJsonArray("rows") {
                data.periods.forEach { period -> addJsonObject {
                    put("DM", period.number); put("KSSJ", period.start.toString()); put("JSSJ", period.end.toString()); put("SFSY", if (period.enabled) 1 else 0)
                } }
            } } } }
        }.toString()
        schoolSnapshots.mutate(track) {
            prefs.edit { putString("snapshot", safe); if (verified) remove("login_required") }
        }
    }
    fun clear() { schoolSnapshots.mutate(track) { prefs.edit { clear() } } }
}
