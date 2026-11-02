package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test

class LaunchRequestsTest {
    @Test fun freshLaunchAppliesPreferenceOverRestoredTab() {
        val requests = LaunchRequests()
        var tab = 0 // Android restored the last visible daily page.
        val launch = requests.open(3)
        if (requests.consume(launch)) tab = launch.tab
        assertEquals(3, tab)
    }
    @Test fun retainedLaunchDoesNotOverrideManualTabAfterConfigurationRecreation() {
        val requests = LaunchRequests()
        val launch = requests.open(3)
        assertTrue(requests.consume(launch))
        var tab = 2 // User switched to exams before rotating the screen.
        if (requests.consume(launch)) tab = launch.tab
        assertEquals(2, tab)
    }
    @Test fun launcherReuseUsesNewPreferenceAndWidgetKeepsDateTarget() {
        val requests = LaunchRequests()
        val first = requests.open(0)
        assertTrue(requests.consume(first))
        val launcher = requests.open(3)
        assertTrue(requests.consume(launcher)); assertEquals(3, launcher.tab)
        assertFalse(requests.consume(first))
        val widget = requests.open(0, "2026-10-23")
        assertTrue(requests.consume(widget))
        assertEquals(0, widget.tab); assertEquals("2026-10-23", widget.date)
    }
}
