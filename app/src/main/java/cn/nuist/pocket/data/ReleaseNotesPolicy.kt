package cn.nuist.pocket.data

/** A fresh install uses the onboarding guide; upgrades get one local release notice. */
internal fun shouldShowReleaseNotes(current: Int, seen: Int, isUpgrade: Boolean): Boolean =
    isUpgrade && current > seen
