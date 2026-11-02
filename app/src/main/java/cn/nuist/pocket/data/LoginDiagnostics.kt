package cn.nuist.pocket.data

import kotlinx.serialization.json.*

/** Only fixed labels and bounded numbers may leave the WebView diagnostics boundary. */
object LoginDiagnostics {
    fun consoleSummary(message: String): String {
        val category = consoleCategory(message)
        if (category != "混合内容限制") return category
        // Parse only HTTP origins from Chromium's warning, never retain the raw URL/message.
        val targets = Regex("http://[^\\s\"'<>]+", RegexOption.IGNORE_CASE).findAll(message)
            .map { LoginNavigation.destinationLabel(it.value) }.distinct().take(4).toList()
        val kind = when {
            message.contains("XMLHttpRequest", true) -> "XHR接口"
            message.contains("fetch", true) -> "fetch接口"
            message.contains("script", true) -> "脚本"
            message.contains("image", true) -> "图片"
            message.contains("stylesheet", true) -> "样式"
            message.contains("frame", true) -> "内嵌页面"
            else -> "资源"
        }
        val outcome = when {
            message.contains("has been blocked", true) || message.contains("was blocked", true) -> "已拦截"
            message.contains("automatically upgraded", true) -> "浏览器已自动升级"
            else -> "状态未明确"
        }
        return "$category · $kind · $outcome" + if (targets.isEmpty()) "" else " · 目标 ${targets.joinToString()}"
    }

    fun consoleCategory(message: String): String = when {
        message.contains("Content Security Policy", true) || message.contains("Refused to frame", true) -> "页面安全策略限制"
        message.contains("CORS policy", true) || message.contains("cross-origin", true) -> "跨来源访问限制"
        message.contains("Mixed Content", true) -> "混合内容限制"
        message.contains("Failed to generate fingerprint", true) -> "浏览器指纹初始化失败"
        else -> listOf("TypeError", "ReferenceError", "SyntaxError", "SecurityError", "RangeError", "NetworkError")
            .firstOrNull { message.startsWith(it) || message.startsWith("Uncaught $it") || message.startsWith("Uncaught (in promise) $it") }
            ?: "网页脚本或资源错误"
    }

    // Run only when the user opens diagnostics. No input values, text, cookies, storage,
    // URLs or page HTML are read. No page listeners or JavaScript interface are installed.
    val structureProbe = """
        (function() {
          var frames = document.querySelectorAll('iframe');
          var items = [];
          for (var i = 0; i < Math.min(frames.length, 8); i++) {
            var r = frames[i].getBoundingClientRect();
            var state = 'restricted';
            try { var d = frames[i].contentDocument; if (d) state = d.readyState; } catch (_) {}
            items.push({width: Math.round(r.width), height: Math.round(r.height), state: state});
          }
          return JSON.stringify({state: document.readyState, frames: frames.length,
            scripts: document.scripts.length, items: items});
        })()
    """.trimIndent()

    fun structureSummary(result: String?): String = runCatching {
        val encoded = Json.parseToJsonElement(result ?: "null").jsonPrimitive.content
        val root = Json.parseToJsonElement(encoded).jsonObject
        fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.intOrNull?.coerceIn(0, 10000) ?: 0
        fun state(value: JsonElement?): String = when (value?.jsonPrimitive?.contentOrNull) {
            "loading" -> "加载中"
            "interactive" -> "文档可交互"
            "complete" -> "文档加载完成"
            else -> "不可读取（跨来源或未就绪）"
        }
        buildString {
            appendLine("主文档：${state(root["state"])}")
            appendLine("脚本数量：${root.number("scripts")}；内嵌页面：${root.number("frames")}")
            root["items"]?.jsonArray?.take(8)?.forEachIndexed { index, item ->
                val frame = item.jsonObject
                appendLine("内嵌页面${index + 1}：${frame.number("width")}×${frame.number("height")}，${state(frame["state"])}")
            }
        }.trimEnd()
    }.getOrElse { "未取得文档结构；页面可能正在跳转或未就绪。" }
}
