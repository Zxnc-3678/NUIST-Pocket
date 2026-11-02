package cn.nuist.pocket.data

import java.io.File
import java.nio.file.Files

/** Owns only wallpaper import scratch files; never traverses persistent app data. */
internal object CacheMaintenance {
    const val STALE_AFTER_MS = 24L * 60 * 60 * 1000
    private val lock = Any()
    private val activeImports = mutableSetOf<String>()

    fun createImageImport(directory: File): File = synchronized(lock) {
        File.createTempFile("wallpaper-import-", ".tmp", directory.canonicalFile).also {
            activeImports.add(it.absolutePath)
        }
    }

    fun finishImageImport(file: File) = synchronized(lock) {
        if (activeImports.remove(file.absolutePath)) {
            try { file.delete() } catch (_: SecurityException) { /* Retry after expiry on next launch. */ }
        }
        Unit
    }

    fun removeStaleImageImports(directory: File, now: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        val root = try { directory.canonicalFile } catch (_: Exception) { return@synchronized 0 }
        val candidates = try { root.listFiles() } catch (_: SecurityException) { null }
        var removed = 0
        candidates.orEmpty().forEach { file ->
            try {
                if (!file.name.startsWith("wallpaper-import-") || !file.name.endsWith(".tmp")) return@forEach
                if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.canonicalFile.parentFile != root) return@forEach
                if (file.absolutePath in activeImports) return@forEach
                val modified = file.lastModified()
                if (modified > 0 && now >= modified && now - modified >= STALE_AFTER_MS && file.delete()) removed++
            } catch (_: Exception) { /* Best effort; one unreadable file must not prevent app startup. */ }
        }
        removed
    }
}
