package cn.nuist.pocket.data

import java.time.LocalDate

/** Only semester starts checked against the school's calendar are used automatically. */
object AcademicCalendar {
    fun semester(code: String?): Semester? = when (code) {
        "2026-2027-1" -> Semester(code, LocalDate.of(2026, 8, 31))
        "2026-2027-2" -> Semester(code, LocalDate.of(2027, 2, 22))
        else -> null
    }
}

/** Both tracks use the shared school calendar; a graduate's explicit correction takes priority. */
fun graduateFirstMonday(code: String, configured: LocalDate?): LocalDate? =
    configured?.takeIf { it.dayOfWeek == java.time.DayOfWeek.MONDAY } ?: AcademicCalendar.semester(code)?.firstMonday

/** Follow the current day only while the user is viewing that day, not a manually selected one. */
fun followToday(selected: LocalDate, previousToday: LocalDate, today: LocalDate): LocalDate =
    if (selected == previousToday) today else selected
