package cn.nuist.pocket.data

import android.content.Context
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class PocketStore(private val context: Context, private val track: StudentTrack = AppPreferences(context).studentTrack) {
    private val prefs = context.getSharedPreferences(track.importName, Context.MODE_PRIVATE)
    private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
    val periods get() = SchoolParser.periods(asset("periods.json"))
    val demoCourses get() = SchoolParser.courses(asset("courses-demo.json"))
    val demoRooms get() = SchoolParser.classrooms(asset("rooms-demo.json"))
    val demoExams get() = SchoolParser.exams(asset("exams-demo.json"))
    fun savedCourses(): List<CourseSlot>? = prefs.getString("courses", null)?.let {
        runCatching { SchoolParser.courses(it) }.getOrNull()
    }
    fun firstMonday(): LocalDate = runCatching {
        LocalDate.parse(prefs.getString("first_monday", "2026-08-31"))
    }.getOrDefault(LocalDate.of(2026, 8, 31))
    fun saveFirstMonday(date: LocalDate) { schoolSnapshots.mutate(track) { prefs.edit().putString("first_monday", date.toString()).apply() } }
    fun importCourses(stream: InputStream): List<CourseSlot> {
        val bytes = stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 1_048_576) throw SchoolDataException("文件超过1MB，请只导出课表响应")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.size > 1_048_576) throw SchoolDataException("文件超过1MB，请只导出课表响应")
        val courses = SchoolParser.courses(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        if (courses.isEmpty()) throw SchoolDataException("课表没有记录，原课表未被替换")
        val semesters = courses.map { it.semester }.distinct()
        if (semesters.size != 1 || semesters.single().isBlank()) throw SchoolDataException("请导入同一学期的课表记录")
        val safe = SchoolParser.safeCourseJson(courses)
        schoolSnapshots.mutate(track) { prefs.edit().putString("courses", safe).apply() }
        return courses
    }
    fun clear() { schoolSnapshots.mutate(track) { prefs.edit().remove("courses").remove("first_monday").apply() } }
}
