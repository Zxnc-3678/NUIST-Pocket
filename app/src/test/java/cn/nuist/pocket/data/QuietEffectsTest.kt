package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test

class QuietEffectsTest {
    private class Device(var ringer: Int = RINGER_NORMAL) : QuietDevice {
        override var hasPolicyAccess = true
        val operations = mutableListOf<String>()
        val rules = mutableMapOf("another-app" to true)
        var failAfterRingerChange = false
        var lastRuleUpdateFromUser = false
        override fun ringerMode() = ringer
        override fun setRingerMode(mode: Int) {
            operations += "ringer:$mode"; ringer = mode
            if (failAfterRingerChange) throw IllegalStateException("simulated interruption")
        }
        override fun ensureRule(): String { operations += "ensure-rule"; rules.putIfAbsent("our-rule", false); return "our-rule" }
        override fun setRuleActive(id: String, active: Boolean, fromUser: Boolean) {
            operations += "$id:$active"; rules[id] = active; lastRuleUpdateFromUser = fromUser
        }
    }
    @Test fun baselineIsCapturedAtActivationAndWrittenBeforeMuting() {
        val device = Device(RINGER_VIBRATE)
        val lease = beginQuiet(QuietMode.SILENT, 100, device) { device.operations += "persist:${it.previousRinger}" }
        assertEquals(listOf("persist:1", "ringer:0"), device.operations)
        assertEquals(RINGER_VIBRATE, lease.previousRinger)
        assertEquals(QuietRestore.VIBRATE, restoreQuiet(lease, device))
        assertEquals(RINGER_VIBRATE, device.ringer)
    }
    @Test fun failedPersistenceNeverChangesSystemAudio() {
        val device = Device()
        assertThrows(IllegalStateException::class.java) { beginQuiet(QuietMode.SILENT, 100, device) { error("disk failed") } }
        assertEquals(RINGER_NORMAL, device.ringer); assertTrue(device.operations.isEmpty())
    }
    @Test fun aCrashAfterSystemChangeStillHasRecoveryOwnership() {
        val device = Device().apply { failAfterRingerChange = true }
        var saved: QuietLease? = null
        assertThrows(IllegalStateException::class.java) { beginQuiet(QuietMode.SILENT, 100, device) { saved = it } }
        assertEquals(RINGER_SILENT, device.ringer); assertNotNull(saved)
        device.failAfterRingerChange = false
        assertEquals(QuietRestore.NORMAL, restoreQuiet(requireNotNull(saved), device))
        assertEquals(RINGER_NORMAL, device.ringer)
    }
    @Test fun anAlreadySilentPhoneIsNotForcedToRingAfterClass() {
        val device = Device(RINGER_SILENT)
        val lease = beginQuiet(QuietMode.SILENT, 100, device) { }
        assertTrue(device.operations.isEmpty())
        assertEquals(QuietRestore.UNCHANGED, restoreQuiet(lease, device)); assertEquals(RINGER_SILENT, device.ringer)
    }
    @Test fun manualChangesTakePrecedenceEvenWhenTheOriginalStateWasSilent() {
        for (original in listOf(RINGER_NORMAL, RINGER_VIBRATE, RINGER_SILENT)) {
            val device = Device(original)
            val lease = beginQuiet(QuietMode.SILENT, 100, device) { }
            device.ringer = RINGER_VIBRATE; device.operations.clear()
            assertEquals(QuietRestore.MANUAL, restoreQuiet(lease, device))
            assertEquals(RINGER_VIBRATE, device.ringer); assertTrue(device.operations.isEmpty())
        }
    }
    @Test fun permissionLossDoesNotSilentlyDiscardTheNeedToRestore() {
        val device = Device()
        val lease = beginQuiet(QuietMode.SILENT, 100, device) { }
        device.hasPolicyAccess = false
        assertThrows(IllegalStateException::class.java) { restoreQuiet(lease, device) }
        assertEquals(RINGER_SILENT, device.ringer)
        device.hasPolicyAccess = true
        assertEquals(QuietRestore.NORMAL, restoreQuiet(lease, device))
    }
    @Test fun noPermissionNeverBeginsAnEffect() {
        val device = Device().apply { hasPolicyAccess = false }
        assertThrows(IllegalStateException::class.java) { beginQuiet(QuietMode.DND, 100, device) { error("must not persist") } }
        assertTrue(device.operations.isEmpty())
    }
    @Test fun DndUsesOnlyTheOwnedRuleAndPersistsBeforeActivation() {
        val device = Device()
        val lease = beginQuiet(QuietMode.DND, 100, device) { device.operations += "persist:${it.ruleId}" }
        assertEquals(listOf("ensure-rule", "persist:our-rule", "our-rule:true"), device.operations)
        assertEquals(QuietRestore.DND, restoreQuiet(lease, device))
        assertEquals(false, device.rules["our-rule"]); assertEquals(true, device.rules["another-app"])
        assertEquals(RINGER_NORMAL, device.ringer)
    }
    @Test fun aDndPersistenceFailureLeavesTheRuleInactive() {
        val device = Device()
        assertThrows(IllegalStateException::class.java) { beginQuiet(QuietMode.DND, 100, device) { error("disk failed") } }
        assertEquals(false, device.rules["our-rule"]); assertEquals(true, device.rules["another-app"])
    }
    @Test fun anExplicitCancelIsDistinguishedFromAScheduledDndEnd() {
        val device = Device()
        val lease = beginQuiet(QuietMode.DND, 100, device) { }
        restoreQuiet(lease, device)
        assertFalse(device.lastRuleUpdateFromUser)
        restoreQuiet(lease, device, fromUser = true)
        assertTrue(device.lastRuleUpdateFromUser)
        assertEquals(true, device.rules["another-app"])
    }
    @Test fun repeatedRecoveryDoesNotOverwriteACompletedRestore() {
        val device = Device()
        val lease = beginQuiet(QuietMode.SILENT, 100, device) { }
        restoreQuiet(lease, device); device.operations.clear()
        assertEquals(QuietRestore.MANUAL, restoreQuiet(lease, device)); assertTrue(device.operations.isEmpty())
    }
}
