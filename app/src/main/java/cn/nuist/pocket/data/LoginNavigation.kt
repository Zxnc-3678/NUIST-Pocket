package cn.nuist.pocket.data

import java.net.URL
import java.util.Locale

/** WebView frame policy, separate from native HTTP redirects. Never inspect or retain query values. */
object LoginNavigation {
    private fun httpsHost(value: String): String? = runCatching {
        if ('\\' in value) return null
        val url = URL(value)
        if (!url.protocol.equals("https", true) || url.userInfo != null || url.port !in listOf(-1, 443)) return null
        url.host.lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun allowed(url: String, mainFrame: Boolean, currentPage: String?, track: StudentTrack = StudentTrack.UNDERGRADUATE): Boolean {
        val permitted = track.loginHosts
        val host = httpsHost(url)
        if (mainFrame) return host in permitted
        if (httpsHost(currentPage.orEmpty()) !in permitted) return false
        // Embedded bootstrap documents are not top-level destinations. WebView handles their origin.
        if (url.substringBefore('#') in setOf("about:blank", "about:srcdoc")) return true
        if (host == "nuist.edu.cn" || host?.endsWith(".nuist.edu.cn") == true) return true
        if (url.startsWith("blob:https://")) {
            val owner = httpsHost(url.removePrefix("blob:"))
            return owner == "nuist.edu.cn" || owner?.endsWith(".nuist.edu.cn") == true
        }
        return false
    }

    /** Retain only scheme/host, never path, userinfo, challenge token, ticket, query or fragment. */
    fun destinationLabel(value: String): String = runCatching {
        val url = URL(value)
        "${url.protocol.lowercase(Locale.ROOT)}://${url.host.lowercase(Locale.ROOT)}"
    }.getOrElse {
        when (value.substringBefore('#')) {
            "about:blank", "about:srcdoc" -> value.substringBefore('#')
            else -> Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").find(value)?.value ?: "未知地址"
        }
    }
}
