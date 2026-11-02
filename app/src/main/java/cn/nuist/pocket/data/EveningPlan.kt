package cn.nuist.pocket.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class EveningMessage(val title: String, val body: String, val date: LocalDate, val greeting: String)

private val earlyEightGreetings = listOf(
    "明天早八，今晚早点休息，早餐和路上的时间也别忘了留。",
    "早八预告：闹钟设好，东西收好，明早就能少赶一点。",
    "明天要早起，今天就早点收工吧。祝明早不用一路小跑。",
    "明天第一节就有课，睡前看好教室，早上从容出发。",
    "又是早八的一天。提前准备好，明早还能留点时间吃早餐。",
    "明天八点上课，出门时间可以先算好。今晚好好休息。",
    "早八已确认，闹钟可以安排了。希望明天起床顺利。",
    "明天早课见，今晚少熬一会儿，给早晨留点精神。",
)
private val earlyTenGreetings = listOf(
    "明天早十，早晨可以从容一点，吃好早餐再出发。",
    "好消息，明天不用赶早八。不过早十也记得留好出门时间。",
    "明天十点多开始上课，早餐和路程都可以提前安排。",
    "明天第一节是早十，早上多了一点自由安排的时间。",
    "早十预告：睡前看一眼教室，明早少一点手忙脚乱。",
    "明天早十见，今晚好好休息，早晨按自己的节奏准备。",
    "明天上午有课，十点多开场。别忘了把要带的东西收好。",
    "早十已排好，闹钟也可以设好了。明天从容去上课。",
)
private val afternoonGreetings = listOf(
    "明天从下午开始上课，上午可以留给自己的安排。",
    "明天下午有课，午饭和出门时间别挤在一起。",
    "下午课预告：如果打算午睡，别忘了设个闹钟。",
    "明天第一节课在午后，上午可以慢慢准备。",
    "明天下午见，出门前再看一眼时间和教室。",
    "明天上午暂时没有排课，下午的安排已经整理好了。",
    "明天下午有课，午休留一点，路上也留一点。",
    "下午的课已经排好，明天按时出发就行。今晚早点收工吧。",
)
private val eveningGreetings = listOf(
    "明晚有课，晚饭可以提前安排，出门不用太赶。",
    "明天第一节课在晚上，白天可以留给自己的计划。",
    "晚课预告：提前看好教室，吃完晚饭再从容出发。",
    "明晚的课别漏了，白天暂时没有已排课程。",
    "明天晚上记得去上课，也给晚饭留点时间。",
    "明晚有课堂安排，其他计划记得错开这个时段。",
    "晚课已经整理好了，出门前再确认一下教室。",
    "明天从晚课开始，白天可以安排得宽松一些。",
)
private val classGreetings = listOf(
    "明天有课，睡前看一眼安排，出门就不用临时翻课表了。",
    "明天的课程已经整理好了，时间和教室都在这里。",
    "把明天要用的东西收好，早上可以少忙一点。",
    "明日课程提醒到了，看完安排就早点休息吧。",
    "明天继续上课，提前留好路程时间，从容一点。",
    "今天辛苦了，明天的安排先看一眼，剩下的明天再说。",
    "明天有课堂安排，别忘了确认上课时间和地点。",
    "明天的课已排好，今晚好好休息，祝明天顺利。",
)
private val noClassGreetings = listOf(
    "明天暂时没有排课，可以按自己的节奏安排一天。",
    "明天课表暂时空着，读书、散步，或者好好休息，都不错。",
    "暂时没有已排课程，明天可以从容一点。今晚好好休息。",
    "明天暂时不用赶课，有空做点一直想做的小事了。",
    "明天课表留白，自习或休息，留给自己安排。",
    "明天暂时没有排课，希望是轻松又充实的一天。",
    "明日暂无已排课程，可以给自己的计划留点时间。",
    "课表暂时没有新安排，明天不妨换个节奏。晚安。",
)
private fun List<String>.onDate(date: LocalDate): String = get(Math.floorMod(date.toEpochDay(), size.toLong()).toInt())

/** A late alarm never becomes a surprise morning notification. */
fun reminderWindow(now: LocalDateTime, time: LocalTime): Boolean =
    now.toLocalTime() >= time

fun nextEvening(now: LocalDateTime, time: LocalTime): LocalDateTime {
    val candidate = now.toLocalDate().atTime(time)
    return if (candidate > now) candidate else candidate.plusDays(1)
}

fun eveningMessage(snapshot: WidgetSchedule, tomorrow: LocalDate): EveningMessage? {
    // A known term boundary is required before making a no-class claim.
    val week = snapshot.semester.weekOn(tomorrow)
    val lastWeek = maxOf(20, snapshot.courses.flatMap { it.weeks }.maxOrNull() ?: 0)
    if (week !in 1..lastWeek || snapshot.courses.isEmpty()) return null
    val courses = snapshot.coursesOn(tomorrow)
    val suffix = if (snapshot.loginRequired) "\n按已保存课表提醒；学校连接待验证，请及时同步。" else "\n按本机已保存课表提醒。"
    if (courses.isEmpty()) {
        // Unarranged rows could still acquire a time; don't present them as confirmed free time.
        val pending = snapshot.courses.any { !it.arranged }
        val greeting = noClassGreetings.onDate(tomorrow)
        return EveningMessage(if (tomorrow in snapshot.suspendedDates) "明日课表 · 已设置停课" else "明日课表 · 暂无已排课程", greeting +
            (if (pending) "\n还有待排课程，请留意学校后续安排。" else "") + suffix, tomorrow, greeting)
    }
    val first = courses.minWithOrNull(compareBy<CourseSlot> { it.times(snapshot.periods).first ?: LocalTime.MAX }
        .thenBy { it.firstPeriod })!!
    val start = first.times(snapshot.periods).first
    val label = when (start) {
        LocalTime.of(8, 0) -> "明天早八"
        LocalTime.of(10, 10) -> "明天早十"
        null -> "明天有课"
        else -> "明天 ${start.format(DateTimeFormatter.ofPattern("HH:mm"))} 上课"
    }
    val greetings = when {
        start == LocalTime.of(8, 0) -> earlyEightGreetings
        start == LocalTime.of(10, 10) -> earlyTenGreetings
        start != null && start >= LocalTime.of(18, 0) -> eveningGreetings
        start != null && start >= LocalTime.of(12, 0) -> afternoonGreetings
        else -> classGreetings
    }
    val greeting = greetings.onDate(tomorrow)
    return EveningMessage("$label · ${courses.size}段课程", "${first.name} · ${first.room.ifBlank { "地点待定" }}\n$greeting$suffix", tomorrow, greeting)
}
