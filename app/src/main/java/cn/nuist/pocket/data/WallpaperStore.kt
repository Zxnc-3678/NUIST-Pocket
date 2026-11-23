package cn.nuist.pocket.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

/** Copy a bounded, downsampled image into app storage; no gallery access is retained. */
class WallpaperStore private constructor(context: Context, path: File, private val maxEdge: Int, private val quality: Int) {
    constructor(context: Context) : this(context, File(context.filesDir, "timetable-wallpaper.webp"), 1600, 88)
    companion object {
        private val ioLock = Any()
        @JvmStatic fun widget(context: Context): WallpaperStore =
            WallpaperStore(context, File(context.filesDir, "widget-wallpaper.webp"), 1600, 92)
        internal fun timetableFile(context: Context, path: String): WallpaperStore =
            WallpaperStore(context, File(context.filesDir, path), 1600, 88)
    }
    private val file = AtomicFile(path)
    private val cacheDirectory = context.cacheDir
    fun load(): Bitmap? = synchronized(ioLock) {
        runCatching { file.openRead().use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }
    fun loadPreview(maxEdge: Int = 800): Bitmap? = synchronized(ioLock) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            file.openRead().use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxEdge) options.inSampleSize *= 2
            file.openRead().use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }
    internal fun moveTo(destination: File): Boolean = synchronized(ioLock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized false
        file.openRead().close()
        destination.parentFile?.mkdirs()
        Files.move(file.baseFile.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        true
    }
    fun import(stream: InputStream) {
        var source: File? = null
        try {
            stream.use { input ->
                val temporary = CacheMaintenance.createImageImport(cacheDirectory)
                source = temporary
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var size = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > 16 * 1024 * 1024) throw IllegalArgumentException("图片超过16MB，请选择较小的图片")
                        output.write(buffer, 0, count)
                    }
                }
            }
            // Decode from a bounded cache file, avoiding two full-size input byte arrays.
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(checkNotNull(source))) { decoder, info, _ ->
                val scale = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).roundToInt()), maxOf(1, (info.size.height * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                synchronized(ioLock) {
                    val output = file.startWrite()
                    try {
                        check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, output)) { "图片保存失败" }
                        file.finishWrite(output)
                    } catch (error: Exception) { file.failWrite(output); throw error }
                }
            } finally { bitmap.recycle() }
        } finally { source?.let(CacheMaintenance::finishImageImport) }
    }
    fun clear() { synchronized(ioLock) { file.delete() } }
}
