package cn.nuist.pocket.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.InputStream
import java.util.UUID
import org.json.JSONArray

internal data class WallpaperLibraryState(
    val paths: List<String> = emptyList(),
    val activeWallpaperPath: String? = null,
    val lastRandomWallpaperPath: String? = null,
    val randomWallpaperOnLaunch: Boolean = false,
)

internal class WallpaperLibrary(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("timetable_wallpaper_library", Context.MODE_PRIVATE)

    fun initializeForLaunch(): WallpaperLibraryState = synchronized(lock) {
        val state = readOrMigrate()
        if (initializedInProcess) return@synchronized state
        val next = if (state.randomWallpaperOnLaunch && state.paths.size >= 2) chooseAnother(state) else state
        initializedInProcess = true
        next
    }

    fun load(path: String): Bitmap? = image(path).load()
    fun thumbnail(path: String): Bitmap? = image(path).loadPreview(maxEdge = 240)

    fun add(input: InputStream): WallpaperLibraryState = synchronized(lock) {
        val state = readOrMigrate()
        input.use {
            require(state.paths.size < LIMIT) { "壁纸库最多保存 4 张图片" }
            val path = "wallpapers/${UUID.randomUUID()}.webp"
            image(path).import(it)
            write(state.copy(paths = state.paths + path, activeWallpaperPath = path))
        }
    }

    fun select(path: String): WallpaperLibraryState = synchronized(lock) {
        val state = readOrMigrate()
        write(state.copy(activeWallpaperPath = path))
    }

    fun remove(path: String): WallpaperLibraryState = synchronized(lock) {
        val state = readOrMigrate()
        val remaining = state.paths - path
        val next = write(state.copy(
            paths = remaining,
            activeWallpaperPath = if (state.activeWallpaperPath == path) remaining.firstOrNull() else state.activeWallpaperPath,
            lastRandomWallpaperPath = state.lastRandomWallpaperPath?.takeUnless { it == path },
        ))
        image(path).clear()
        next
    }

    fun setRandomOnLaunch(enabled: Boolean): WallpaperLibraryState = synchronized(lock) {
        val state = readOrMigrate()
        check(preferences.edit().putBoolean(RANDOM, enabled).commit()) { "无法保存壁纸设置" }
        state.copy(randomWallpaperOnLaunch = enabled)
    }

    fun randomize(): WallpaperLibraryState = synchronized(lock) {
        val state = readOrMigrate()
        if (state.paths.size < 2) state else chooseAnother(state)
    }

    private fun chooseAnother(state: WallpaperLibraryState): WallpaperLibraryState {
        val previous = state.activeWallpaperPath ?: state.lastRandomWallpaperPath
        val next = state.paths.filterNot { it == previous }.random()
        return write(state.copy(activeWallpaperPath = next, lastRandomWallpaperPath = next))
    }

    private fun image(path: String): WallpaperStore = WallpaperStore.timetableFile(context, path)

    private fun readOrMigrate(): WallpaperLibraryState {
        val encoded = preferences.getString(PATHS, null)
        if (encoded != null) {
            val array = JSONArray(encoded)
            val paths = (0 until array.length()).map(array::getString)
            return WallpaperLibraryState(paths, preferences.getString(ACTIVE, null), preferences.getString(LAST_RANDOM, null),
                preferences.getBoolean(RANDOM, paths.size >= 2))
        }
        // The fixed destination also recovers a move interrupted before the index was saved.
        val target = File(context.filesDir, MIGRATED)
        val path = if (target.isFile || WallpaperStore(context).moveTo(target)) MIGRATED else null
        return write(WallpaperLibraryState(listOfNotNull(path), activeWallpaperPath = path))
    }

    private fun write(state: WallpaperLibraryState): WallpaperLibraryState {
        check(preferences.edit().putString(PATHS, JSONArray(state.paths).toString())
            .putString(ACTIVE, state.activeWallpaperPath)
            .putString(LAST_RANDOM, state.lastRandomWallpaperPath)
            .commit()) { "无法保存壁纸库" }
        return state.copy(randomWallpaperOnLaunch = preferences.getBoolean(RANDOM, state.paths.size >= 2))
    }

    companion object {
        const val LIMIT = 4
        private const val PATHS = "wallpaperPaths"
        private const val ACTIVE = "activeWallpaperPath"
        private const val LAST_RANDOM = "lastRandomWallpaperPath"
        private const val RANDOM = "randomWallpaperOnLaunch"
        private const val MIGRATED = "wallpapers/legacy.webp"
        private val lock = Any()
        private var initializedInProcess = false
    }
}
