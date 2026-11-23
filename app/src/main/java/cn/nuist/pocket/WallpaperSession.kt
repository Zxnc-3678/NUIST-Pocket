package cn.nuist.pocket

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import cn.nuist.pocket.data.WallpaperLibrary
import cn.nuist.pocket.data.WallpaperLibraryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class WallpaperSession(context: Context, private val scope: CoroutineScope) {
    val store = WallpaperLibrary(context)
    var library by mutableStateOf<WallpaperLibraryState?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private data class Frame(val path: String, val images: TimetableWallpaperImages)
    private data class Glass(val viewport: IntSize, val bitmap: ImageBitmap)
    private var frame by mutableStateOf<Frame?>(null)
    private val glassByPath = mutableMapOf<String, Glass>()

    val images: TimetableWallpaperImages?
        get() = frame?.takeIf { it.path in library?.paths.orEmpty() }?.images

    fun change(operation: WallpaperLibrary.() -> WallpaperLibraryState) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) { store.operation() }
                glassByPath.keys.retainAll(next.paths.toSet())
                library = next
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = if (failure is IllegalArgumentException) failure.message else "壁纸操作未完成，请重试。"
            } finally {
                busy = false
            }
        }
    }

    suspend fun display(path: String?, viewport: IntSize) {
        if (path == null) {
            frame = null
            return
        }
        val original = frame?.takeIf { it.path == path }?.images?.original
            ?: withContext(Dispatchers.IO) { store.load(path) } ?: return
        val glass = glassByPath[path]?.takeIf { it.viewport == viewport }
        val prepared = if (glass != null) TimetableWallpaperImages(original, glass.bitmap)
        else withContext(Dispatchers.Default) {
            prepareTimetableWallpaper(original, viewport.width, viewport.height)
        }
        // Publish the full image and its matching glass layer together, including after rapid changes.
        if (library?.activeWallpaperPath == path) {
            if (glass == null) glassByPath[path] = Glass(viewport, prepared.glass)
            frame = Frame(path, prepared)
        }
    }
}

@Composable
internal fun rememberWallpaperSession(): WallpaperSession {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(context, scope) { WallpaperSession(context, scope) }
    val configuration = LocalConfiguration.current
    val viewport = IntSize(configuration.screenWidthDp, configuration.screenHeightDp)
    val path = session.library?.activeWallpaperPath
    LaunchedEffect(session) { session.change { initializeForLaunch() } }
    LaunchedEffect(session, path, viewport) { session.display(path, viewport) }
    return session
}
