package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesPolicyTest {
    @Test fun upgradeFromVersionWithoutNotesShowsCurrentRelease() {
        assertTrue(shouldShowReleaseNotes(30, 0, true))
    }
    @Test fun dismissedReleaseDoesNotAppearAgain() {
        assertFalse(shouldShowReleaseNotes(30, 30, true))
    }
    @Test fun freshInstallDoesNotCompeteWithOnboarding() {
        assertFalse(shouldShowReleaseNotes(30, 0, false))
    }
    @Test fun laterUpgradeShowsNewNotes() {
        assertTrue(shouldShowReleaseNotes(31, 30, true))
    }
    @Test fun reinstallingOlderReleaseDoesNotRepeatNotes() {
        assertFalse(shouldShowReleaseNotes(30, 31, true))
    }
}
