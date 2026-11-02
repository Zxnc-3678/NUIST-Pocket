package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SchoolSnapshotGuardTest {
    private val undergraduate = StudentTrack.UNDERGRADUATE
    private val graduate = StudentTrack.GRADUATE

    @Test fun unchangedSnapshotCanPublish() {
        val guard = SchoolSnapshotGuard()
        val token = guard.capture { undergraduate }
        var published = false
        assertTrue(guard.publish(token, { undergraduate }) { published = true })
        assertTrue(published)
    }

    @Test fun newDataOrClearingAnAccountRejectsTheOldJob() {
        val guard = SchoolSnapshotGuard()
        val token = guard.capture { undergraduate }
        guard.mutate(undergraduate) { }
        assertFalse(guard.publish(token, { undergraduate }) { fail("Stale data was published") })
        assertTrue(guard.publish(guard.capture { undergraduate }, { undergraduate }) { })
    }

    @Test fun switchingAwayAndBackDoesNotReviveTheOldJob() {
        val guard = SchoolSnapshotGuard()
        var track = undergraduate
        val token = guard.capture { track }
        guard.mutate { track = graduate }
        guard.mutate { track = undergraduate }
        assertFalse(guard.publish(token, { track }) { fail("Previous session was revived") })
    }

    @Test fun updatingInactiveTrackDoesNotCancelCurrentTrack() {
        val guard = SchoolSnapshotGuard()
        val token = guard.capture { undergraduate }
        guard.mutate(graduate) { }
        assertTrue(guard.publish(token, { undergraduate }) { })
    }

    @Test fun currentTrackIsCheckedEvenIfCallerForgotToInvalidate() {
        val guard = SchoolSnapshotGuard()
        val token = guard.capture { undergraduate }
        assertFalse(guard.publish(token, { graduate }) { fail("Wrong track was published") })
    }

    @Test fun partialMutationFailureStillInvalidatesOldReaders() {
        val guard = SchoolSnapshotGuard()
        val token = guard.capture { undergraduate }
        try { guard.mutate(undergraduate) { throw IllegalStateException("write interrupted") } }
        catch (_: IllegalStateException) { }
        assertFalse(guard.publish(token, { undergraduate }) { fail("Reader predates a failed write") })
    }

    @Test fun jobWhichFinishesIoAfterDataChangesCannotPublish() {
        val guard = SchoolSnapshotGuard()
        val track = AtomicReference(undergraduate)
        val readStarted = CountDownLatch(1)
        val finishIo = CountDownLatch(1)
        val published = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                val token = guard.capture { track.get() }
                readStarted.countDown()
                check(finishIo.await(2, TimeUnit.SECONDS))
                guard.publish(token, { track.get() }) { published.incrementAndGet() }
            }
            assertTrue(readStarted.await(2, TimeUnit.SECONDS))
            guard.mutate(undergraduate) { }
            finishIo.countDown()
            assertFalse(result.get(2, TimeUnit.SECONDS))
            assertEquals(0, published.get())
        } finally { finishIo.countDown(); worker.shutdownNow() }
    }
}
