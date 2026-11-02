package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheMaintenanceTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private val expired = now - CacheMaintenance.STALE_AFTER_MS
    private fun scratch(root: File, name: String, modified: Long): File = File(root, name).apply {
        writeText("test")
        check(setLastModified(modified))
    }

    @Test fun removesOnlyExpiredOwnedFiles() {
        val root = temp.newFolder()
        val old = scratch(root, "wallpaper-import-old.tmp", expired)
        val fresh = scratch(root, "wallpaper-import-fresh.tmp", expired + 1)
        assertEquals(1, CacheMaintenance.removeStaleImageImports(root, now))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test fun leavesUnrelatedAndPersistentLookingFilesAlone() {
        val root = temp.newFolder()
        val files = listOf("personal-todos.json", "snapshot", "Cookies", "timetable-wallpaper.webp",
            "widget-wallpaper.webp", "something.tmp", "wallpaper-import-old.webp").map { scratch(root, it, expired) }
        assertEquals(0, CacheMaintenance.removeStaleImageImports(root, now))
        assertTrue(files.all { it.exists() })
    }

    @Test fun doesNotTraverseDirectoriesOrSiblings() {
        val root = temp.newFolder()
        val child = File(root, "wallpaper-import-directory.tmp").apply { mkdir() }
        val nested = scratch(child, "wallpaper-import-nested.tmp", expired)
        val sibling = scratch(temp.root, "wallpaper-import-sibling.tmp", expired)
        assertEquals(0, CacheMaintenance.removeStaleImageImports(root, now))
        assertTrue(child.isDirectory)
        assertTrue(nested.exists())
        assertTrue(sibling.exists())
    }

    @Test fun activeImportSurvivesSweepEvenWhenOld() {
        val root = temp.newFolder()
        val active = CacheMaintenance.createImageImport(root)
        try {
            check(active.setLastModified(expired))
            assertEquals(0, CacheMaintenance.removeStaleImageImports(root, now))
            assertTrue(active.exists())
        } finally { CacheMaintenance.finishImageImport(active) }
        assertFalse(active.exists())
    }

    @Test fun canonicalAliasesStillProtectActiveImport() {
        val root = temp.newFolder()
        val active = CacheMaintenance.createImageImport(File(root, "."))
        try {
            check(active.setLastModified(expired))
            assertEquals(0, CacheMaintenance.removeStaleImageImports(root, now))
        } finally { CacheMaintenance.finishImageImport(active) }
    }

    @Test fun finalCleanupDeletesBothSuccessAndFailureScratchFiles() {
        val root = temp.newFolder()
        val active = CacheMaintenance.createImageImport(root)
        try { active.writeText("partial image"); throw IllegalArgumentException("decode failed") }
        catch (_: IllegalArgumentException) { /* Simulate importer failure. */ }
        finally { CacheMaintenance.finishImageImport(active) }
        CacheMaintenance.finishImageImport(active)
        assertFalse(active.exists())
        assertEquals(0, CacheMaintenance.removeStaleImageImports(root, now))
    }

    @Test fun futureAndUnknownModificationTimesAreKept() {
        val root = temp.newFolder()
        val future = scratch(root, "wallpaper-import-future.tmp", now + 1)
        val unknown = scratch(root, "wallpaper-import-unknown.tmp", 0)
        assertEquals(0, CacheMaintenance.removeStaleImageImports(root, now))
        assertTrue(future.exists())
        assertTrue(unknown.exists())
    }

    @Test fun missingOrInvalidCacheDirectoryIsHarmless() {
        assertEquals(0, CacheMaintenance.removeStaleImageImports(File(temp.root, "missing"), now))
        assertEquals(0, CacheMaintenance.removeStaleImageImports(temp.newFile(), now))
    }

    @Test fun repeatSweepIsIdempotent() {
        val root = temp.newFolder()
        scratch(root, "wallpaper-import-old.tmp", expired - 1)
        assertEquals(1, CacheMaintenance.removeStaleImageImports(root, now))
        assertEquals(0, CacheMaintenance.removeStaleImageImports(root, now))
    }
}
