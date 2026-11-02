package cn.nuist.pocket.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LoginDiagnosticsTest {
    @Test fun mixedContentReportsTargetAndTypeWithoutTokens() {
        val raw = "Mixed Content: The page at 'https://authserver.nuist.edu.cn/login?ticket=SECRET' requested an insecure XMLHttpRequest endpoint 'http://authserver.nuist.edu.cn/api/private?code=TOKEN'. This request has been blocked."
        val safe = LoginDiagnostics.consoleSummary(raw)
        assertTrue(safe.contains("XHR接口")); assertTrue(safe.contains("已拦截"))
        assertTrue(safe.contains("目标 http://authserver.nuist.edu.cn"))
        listOf("SECRET", "TOKEN", "private", "ticket=", "code=").forEach { assertFalse(safe.contains(it)) }
    }

    @Test fun autoUpgradedImageIsNotReportedAsBlocked() {
        val raw = "Mixed Content: requested an insecure image 'http://images.example/picture?secret=TOKEN'. This request was automatically upgraded to HTTPS."
        val safe = LoginDiagnostics.consoleSummary(raw)
        assertTrue(safe.contains("图片")); assertTrue(safe.contains("浏览器已自动升级"))
        assertFalse(safe.contains("已拦截")); assertFalse(safe.contains("TOKEN"))
    }

    @Test fun consolePayloadIsReducedToFixedCategories() {
        val messages = listOf(
            "Uncaught TypeError: SECRET at https://example.org/?ticket=TOKEN" to "TypeError",
            "Uncaught (in promise) ReferenceError: SECRET" to "ReferenceError",
            "Refused to frame SECRET because of Content Security Policy" to "页面安全策略限制",
            "Failed to generate fingerprint: SECRET" to "浏览器指纹初始化失败",
            "Unknown payload SECRET TOKEN" to "网页脚本或资源错误"
        )
        messages.forEach { (raw, expected) ->
            val safe = LoginDiagnostics.consoleCategory(raw)
            assertEquals(expected, safe)
            assertFalse(safe.contains("SECRET")); assertFalse(safe.contains("TOKEN"))
        }
    }

    @Test fun structureReportsBlockedAndZeroSizeFramesWithoutRawText() {
        val raw = """{"state":"complete","frames":2,"scripts":6,"ticket":"SECRET","items":[
            {"width":0,"height":0,"state":"interactive","src":"SECRET"},
            {"width":360,"height":600,"state":"restricted"}]}"""
        val safe = LoginDiagnostics.structureSummary(JsonPrimitive(raw).toString())
        assertTrue(safe.contains("文档加载完成"))
        assertTrue(safe.contains("内嵌页面：2"))
        assertTrue(safe.contains("0×0")); assertTrue(safe.contains("跨来源或未就绪"))
        assertFalse(safe.contains("SECRET"))
    }

    @Test fun untrustedProbeStringsAreNeverDisplayed() {
        val raw = """{"state":"SECRET","frames":-9,"scripts":999999,"items":[{"width":-1,"height":999999,"state":"TOKEN"}]}"""
        val safe = LoginDiagnostics.structureSummary(JsonPrimitive(raw).toString())
        assertFalse(safe.contains("SECRET")); assertFalse(safe.contains("TOKEN"))
        assertTrue(safe.contains("内嵌页面：0")); assertTrue(safe.contains("0×10000"))
    }

    @Test fun malformedOrMissingProbeResultHasSafeFallback() {
        listOf(null, "null", "SECRET", "{}", "\"not json SECRET\"").forEach {
            assertEquals("未取得文档结构；页面可能正在跳转或未就绪。", LoginDiagnostics.structureSummary(it))
        }
    }
}
