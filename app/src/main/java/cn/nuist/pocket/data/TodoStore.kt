package cn.nuist.pocket.data

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Separate from account caches: logging out never removes personal tasks. */
class TodoStore(context: Context) {
    companion object { private val lock = Any() }
    private val file = AtomicFile(File(context.filesDir, "personal-todos.json"))
    fun load(): List<TodoItem> = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized emptyList()
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= TodoCodec.MAX_BYTES) { "待办文件过大" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        TodoCodec.decode(bytes.toString(Charsets.UTF_8))
    }
    fun save(items: List<TodoItem>): Unit = synchronized(lock) {
        val bytes = TodoCodec.encode(items).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}
