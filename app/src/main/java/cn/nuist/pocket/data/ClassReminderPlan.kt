package cn.nuist.pocket.data

import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

internal val ClassZone: ZoneId = ZoneId.of("Asia/Shanghai")
internal const val CLASS_REMINDER_LEAD_MS = 20 * 60_000L
internal const val CLASS_QUIET_MARGIN_MS = 60_000L
internal const val MAX_QUIET_RESERVATIONS = 64
internal const val MAX_CLASS_WAKE_TIMES = 192
internal enum class QuietMode(val title: String) { SILENT("静音"), DND("勿扰") }

internal data class ClassEvent(
    val key: String, val track: StudentTrack, val date: LocalDate, val title: String,
    val room: String, val startsAt: Long, val endsAt: Long,
) { val remindAt: Long get() = startsAt - CLASS_REMINDER_LEAD_MS }

/** Stable occurrence identity excludes rooms/teachers so a location update doesn't cancel a booking. */
internal fun classEvents(schedule: WidgetSchedule, track: StudentTrack, date: LocalDate, accountGeneration: Long = 0): List<ClassEvent> {
    val digest = MessageDigest.getInstance("SHA-256")
    return schedule.coursesOn(date)
        .mapNotNull { course ->
            val (start, end) = course.times(schedule.periods)
            if (start == null || end == null || !end.isAfter(start)) null else Triple(course, start, end)
        }
        .groupBy { (course, start, end) ->
            listOf(course.code, course.name, start.toString(), end.toString(), course.id.takeIf { course.isCustom })
        }
        .map { (_, entries) ->
            val (course, start, end) = entries.first()
            val startsAt = date.atTime(start).atZone(ClassZone).toInstant().toEpochMilli()
            val endsAt = date.atTime(end).atZone(ClassZone).toInstant().toEpochMilli()
            val identity = (listOf(track.name, accountGeneration.toString(), schedule.semester.code, date.toString(), course.code, course.name,
                startsAt.toString(), endsAt.toString()) + listOfNotNull(course.id.takeIf { course.isCustom }))
                .joinToString("") { "${it.length}:$it" }
            val hex = "0123456789abcdef"
            val key = buildString(64) {
                digest.digest(identity.toByteArray(Charsets.UTF_8)).forEach {
                    val byte = it.toInt() and 255
                    append(hex[byte ushr 4]); append(hex[byte and 15])
                }
            }
            ClassEvent(key, track, date, course.displayName,
                entries.map { it.first.room.ifBlank { "地点待定" } }.distinct().joinToString(" / "), startsAt, endsAt)
        }.sortedWith(compareBy<ClassEvent> { it.startsAt }.thenBy { it.title })
}

internal data class QuietReservation(
    val key: String, val track: StudentTrack, val title: String,
    val startsAt: Long, val endsAt: Long, val mode: QuietMode,
) {
    val activatesAt: Long get() = startsAt - CLASS_QUIET_MARGIN_MS
    val restoresAt: Long get() = endsAt + CLASS_QUIET_MARGIN_MS
}

internal data class QuietWindow(val startsAt: Long, val endsAt: Long, val mode: QuietMode,
    val titles: List<String>, val keys: Set<String>)

internal fun ClassEvent.reservation(mode: QuietMode) = QuietReservation(key, track, title, startsAt, endsAt, mode)

internal fun validReservations(reservations: List<QuietReservation>, events: List<ClassEvent>, now: Long): List<QuietReservation> {
    val current = events.associateBy { it.key }
    return reservations.filter { saved ->
        val event = current[saved.key]
        event != null && saved.track == event.track && saved.startsAt == event.startsAt &&
            saved.endsAt == event.endsAt && saved.restoresAt > now
    }.distinctBy { it.key }.take(MAX_QUIET_RESERVATIONS)
}

internal fun bookingProblem(event: ClassEvent, reservations: List<QuietReservation>, mode: QuietMode, now: Long): String? = when {
    now >= event.startsAt -> "这堂课已经开始，预约已过期"
    event.endsAt <= event.startsAt -> "课程时间不完整，暂不能预约"
    reservations.none { it.key == event.key } && reservations.size >= MAX_QUIET_RESERVATIONS -> "预约数量已达上限，请先取消其他预约"
    reservations.any { it.key != event.key && it.mode != mode && it.activatesAt < event.endsAt + CLASS_QUIET_MARGIN_MS &&
        event.startsAt - CLASS_QUIET_MARGIN_MS < it.restoresAt } ->
        "重叠时段已有不同模式的预约，请先取消或统一模式"
    else -> null
}

/** Touching/overlapping bookings with the same mode form one lease: never briefly unmute in between. */
internal fun activeQuietWindow(reservations: List<QuietReservation>, now: Long): QuietWindow? {
    val sorted = reservations.sortedBy { it.activatesAt }
    var index = 0
    while (index < sorted.size) {
        val first = sorted[index++]
        var end = first.restoresAt
        val titles = mutableListOf(first.title)
        val keys = mutableSetOf(first.key)
        while (index < sorted.size && sorted[index].activatesAt <= end && sorted[index].mode == first.mode) {
            val next = sorted[index++]; end = maxOf(end, next.restoresAt); titles += next.title; keys += next.key
        }
        if (now >= first.activatesAt && now < end) return QuietWindow(first.activatesAt, end, first.mode, titles.distinct(), keys)
    }
    return null
}

internal data class ClassDelivery(val key: String, val endsAt: Long, val dismissed: Boolean = false)

/** Re-evaluate local data once per day even when the next teaching week is outside the horizon. */
internal fun nextClassWake(events: List<ClassEvent>, reservations: List<QuietReservation>, deliveries: List<ClassDelivery>, now: Long): Long {
    return classWakeTimes(events, reservations, deliveries, now).first()
}

/** Register future occurrences together, rather than relying on every earlier alarm succeeding. */
internal fun classWakeTimes(events: List<ClassEvent>, reservations: List<QuietReservation>, deliveries: List<ClassDelivery>, now: Long): List<Long> {
    val delivered = deliveries.map { it.key }.toSet()
    val day = java.time.Instant.ofEpochMilli(now).atZone(ClassZone).toLocalDate()
    val tomorrow = day.plusDays(1).atStartOfDay(ClassZone).toInstant().toEpochMilli() + 1_000
    val times = (events.filter { it.key !in delivered }.map { it.remindAt } +
        events.map { it.startsAt } + reservations.flatMap { listOf(it.activatesAt, it.restoresAt) })
        .filter { it > now && it != tomorrow }.distinct().sorted().take(MAX_CLASS_WAKE_TIMES - 1)
    // Reserve one slot for rollover even when an unusually crowded import reaches the limit.
    return (times + tomorrow).sorted()
}
