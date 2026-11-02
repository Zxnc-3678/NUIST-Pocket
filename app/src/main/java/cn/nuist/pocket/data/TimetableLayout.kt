package cn.nuist.pocket.data

/** Overlapping alternatives share one tappable block instead of hiding one another. */
data class TimetableBlock(val first: Int, val last: Int, val groups: List<OverviewCourseGroup>)

fun timetableBlocks(courses: List<CourseSlot>, day: Int, week: Int, showInactive: Boolean): List<TimetableBlock> {
    val groups = overviewGroups(courses).filter {
        it.course.weekday == day && (showInactive || it.activePlacements(week).isNotEmpty())
    }.sortedWith(compareBy<OverviewCourseGroup> { it.course.firstPeriod }.thenBy { it.course.lastPeriod })
    val blocks = mutableListOf<TimetableBlock>()
    for (group in groups) {
        val first = group.course.firstPeriod!!
        val last = group.course.lastPeriod!!
        val previous = blocks.lastOrNull()
        if (previous != null && first <= previous.last) {
            blocks[blocks.lastIndex] = previous.copy(last = maxOf(previous.last, last), groups = previous.groups + group)
        } else blocks += TimetableBlock(first, last, listOf(group))
    }
    return blocks.map { block -> block.copy(groups = block.groups.sortedWith(
        compareBy<OverviewCourseGroup> { it.activePlacements(week).isEmpty() }.thenBy { it.course.firstPeriod }.thenBy { it.course.name })) }
}

fun timetableTop(period: Int, periodHeight: Int = 68): Int =
    (period - 1) * periodHeight + (if (period > 4) 22 else 0) + (if (period > 8) 22 else 0)
fun timetableHeight(first: Int, last: Int, periodHeight: Int = 68): Int =
    timetableTop(last, periodHeight) - timetableTop(first, periodHeight) + periodHeight
