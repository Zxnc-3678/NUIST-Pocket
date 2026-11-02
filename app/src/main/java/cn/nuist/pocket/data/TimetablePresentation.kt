package cn.nuist.pocket.data

/** Every column uses the same period height, including the measured longest full course name. */
fun timetableRequiredPeriodHeight(first: Int, last: Int, contentHeightDp: Float): Int {
    require(first >= 1 && last >= first && contentHeightDp.isFinite() && contentHeightDp >= 0)
    val breaks = (if (first <= 4 && last > 4) 22 else 0) + (if (first <= 8 && last > 8) 22 else 0)
    return kotlin.math.ceil((contentHeightDp - breaks) / (last - first + 1)).toInt().coerceAtLeast(68)
}
