package cn.nuist.pocket

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cn.nuist.pocket.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class SchoolLoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled") // Required by the school's own login/portal SPA; no JS bridge or credential inspection.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val track = StudentTrack.read(intent.getStringExtra("studentTrack") ?: AppPreferences(this).studentTrack.name)
        val portal = if (track == StudentTrack.GRADUATE) GraduateRequests.PORTAL else SchoolRequests.PORTAL
        enableEdgeToEdge()
        setContent {
            PocketTheme(AppPreferences(this).themeAccent) {
                val scope = rememberCoroutineScope()
                var currentHost by remember { mutableStateOf(if (track == StudentTrack.GRADUATE) "nxdyjs.nuist.edu.cn" else "jwxt.nuist.edu.cn") }
                var busy by remember { mutableStateOf(false) }
                var loading by remember { mutableStateOf(true) }
                var progress by remember { mutableIntStateOf(0) }
                var documentFinished by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("请在学校原版页面登录，完成后点击“验证登录”。") }
                var browser by remember { mutableStateOf<WebView?>(null) }
                var loadHints by remember { mutableStateOf(listOf<String>()) }
                var showLoadHints by remember { mutableStateOf(false) }
                var structure by remember { mutableStateOf("尚未检查文档结构。") }
                var httpsCompatibility by remember { mutableStateOf("尚未初始化") }
                var usbDebugging by remember { mutableStateOf(false) }
                DisposableEffect(Unit) {
                    WebView.setWebContentsDebuggingEnabled(false)
                    onDispose { WebView.setWebContentsDebuggingEnabled(false) }
                }
                val webViewVersion = remember { WebView.getCurrentWebViewPackage()?.versionName ?: "未知" }
                fun addLoadHint(text: String) {
                    if (loadHints.lastOrNull() != text) loadHints = (loadHints + text).takeLast(16)
                }
                fun inspectStructure() {
                    structure = "正在检查文档结构…"
                    val view = browser
                    if (view == null || !LoginNavigation.allowed(view.url.orEmpty(), true, view.url, track)) {
                        structure = "学校页面尚未就绪。"
                    } else view.evaluateJavascript(LoginDiagnostics.structureProbe) {
                        structure = LoginDiagnostics.structureSummary(it)
                    }
                }
                BackHandler {
                    if (browser?.canGoBack() == true) browser?.goBack() else finish()
                }
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { finish() }) { Text("关闭") }
                            TextButton(enabled = !busy, onClick = { browser?.reload() }) { Text("刷新") }
                            Button(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        val api = SchoolApi(SchoolConnection(track) { WebSettings.getDefaultUserAgent(this@SchoolLoginActivity) }, track)
                                        val term = api.verifyLogin()
                                        CookieManager.getInstance().flush()
                                        setResult(Activity.RESULT_OK, android.content.Intent().putExtra("semester", term).putExtra("studentTrack", track.name))
                                        finish()
                                    } catch (error: CancellationException) { throw error }
                                    catch (_: Exception) {
                                        message = "尚未验证成功，请确认已进入教务主页，再重试；若已登录仍失败，请反馈页面情况。"
                                    } finally { busy = false }
                                }
                            }) { Text(if (busy) "验证中…" else "验证登录") }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("学校页面 · $currentHost", fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showLoadHints = true; inspectStructure() }) { Text("加载诊断", fontSize = 12.sp) }
                        }
                        Text(message, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                            WebView(context).apply {
                                // Resource cache only; retain cookies and DOM storage for school login.
                                try { clearCache(true) } catch (_: RuntimeException) { /* Retry on release/next open. */ }
                                browser = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                                // Also restores the platform's normal JavaScript alert/confirm/prompt handling.
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress.coerceIn(0, 100) }
                                    override fun onConsoleMessage(console: ConsoleMessage): Boolean {
                                        val compatibilityHint = when (console.message()) {
                                            "NUIST_HTTPS_POLICY_READY" -> "认证页HTTPS资源升级策略已安装"
                                            "NUIST_HTTPS_UPGRADE_XHR" -> "已将学校HTTP接口升级为HTTPS（XHR）"
                                            "NUIST_HTTPS_UPGRADE_FETCH" -> "已将学校HTTP接口升级为HTTPS（fetch）"
                                            "NUIST_MFA_LAYOUT_REPAIRED" -> "已修正学校多因子页面容器高度"
                                            else -> null
                                        }
                                        if (compatibilityHint != null) addLoadHint(compatibilityHint)
                                        if (console.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                                            console.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                                            addLoadHint("${LoginDiagnostics.consoleSummary(console.message())} · ${LoginNavigation.destinationLabel(console.sourceId().orEmpty())} · 行${console.lineNumber().coerceAtLeast(0)}")
                                        }
                                        // Consume even informational messages: do not forward raw console data to Logcat.
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val blocked = !LoginNavigation.allowed(request.url.toString(), request.isForMainFrame, view.url, track)
                                        if (blocked) {
                                            val scope = if (request.isForMainFrame) "页面" else "内嵌组件"
                                            addLoadHint("$scope 跳转受限：${LoginNavigation.destinationLabel(request.url.toString())}")
                                            message = if (request.isForMainFrame) "该登录方式的跳转暂不支持，可查看加载诊断。"
                                                else "有认证组件未能加载，可查看加载诊断后反馈。"
                                        }
                                        return blocked
                                    }
                                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                        loading = true
                                        progress = 0
                                        documentFinished = false
                                        currentHost = LoginNavigation.destinationLabel(url.orEmpty())
                                    }
                                    override fun onPageFinished(view: WebView, url: String?) { loading = false; documentFinished = true }
                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (request.isForMainFrame) { loading = false; message = "学校页面加载失败，请检查网络后刷新。" }
                                        addLoadHint("${if (request.isForMainFrame) "页面" else "资源"} 加载错误${error.errorCode}：${LoginNavigation.destinationLabel(request.url.toString())}")
                                    }
                                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                        addLoadHint("HTTP ${response.statusCode}：${LoginNavigation.destinationLabel(request.url.toString())}")
                                    }
                                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                        handler.cancel()
                                        addLoadHint("证书校验未通过：${LoginNavigation.destinationLabel(error.url.orEmpty())}")
                                        message = "学校验证组件的安全连接失败，请稍后重试或反馈加载诊断。"
                                    }
                                }
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                    val compatibilityScript = context.assets.open("login-https-compat.js").bufferedReader().use { it.readText() }
                                    WebViewCompat.addDocumentStartJavaScript(this, compatibilityScript, setOf("https://authserver.nuist.edu.cn"))
                                    val layoutScript = context.assets.open("login-mfa-layout.js").bufferedReader().use { it.readText() }
                                    WebViewCompat.addDocumentStartJavaScript(this, layoutScript, setOf("https://authserver.nuist.edu.cn"))
                                    httpsCompatibility = "文档开始时启用，仅限学校认证来源"
                                } else {
                                    httpsCompatibility = "当前WebView不支持文档开始脚本，可暂用学校短信登录"
                                }
                                loadUrl(portal)
                            }
                        }, onRelease = { view ->
                            browser = null
                            view.stopLoading()
                            view.webViewClient = WebViewClient()
                            view.webChromeClient = null
                            try { view.clearCache(true) } catch (_: RuntimeException) { /* Retry next open. */ }
                            view.destroy()
                        })
                    }
                }
                if (showLoadHints) AlertDialog(onDismissRequest = { showLoadHints = false },
                    title = { Text("加载诊断") }, text = {
                        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (BuildConfig.DEBUG) Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("USB 排查", modifier = Modifier.weight(1f))
                                Switch(checked = usbDebugging, onCheckedChange = {
                                    usbDebugging = it
                                    WebView.setWebContentsDebuggingEnabled(it)
                                })
                            }
                            if (BuildConfig.DEBUG) Text(if (usbDebugging) "已允许授权电脑调试当前登录页，关闭登录窗口后自动停用。" else "默认关闭；仅在与开发者进行 USB 排查时开启。", fontSize = 12.sp)
                            Text("App ${BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE}\nWebView $webViewVersion\nHTTPS兼容：$httpsCompatibility\n页面进度：$progress%\n${if (documentFinished) "收到文档加载完成回调（不代表认证组件已成功）" else "尚未收到文档加载完成回调"}")
                            Text(structure)
                            Text(if (loadHints.isEmpty()) "尚未捕获网络或脚本警告；这不代表页面功能正常。" else loadHints.joinToString("\n\n"))
                            Text("此处只显示错误类别、来源域名和文档结构，不显示账号、验证码或地址参数。", fontSize = 12.sp)
                        }
                    },
                    dismissButton = { TextButton(onClick = { inspectStructure() }) { Text("重新检查") } },
                    confirmButton = { TextButton(onClick = { showLoadHints = false }) { Text("关闭") } })
            }
        }
    }
}
