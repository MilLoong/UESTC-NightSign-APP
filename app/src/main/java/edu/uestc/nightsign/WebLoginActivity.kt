package edu.uestc.nightsign

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import edu.uestc.nightsign.data.network.ApiConstants
import edu.uestc.nightsign.ui.theme.UestcHelperTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 使用同源 WebView 完成风控/短信认证，手动导入学校 Cookie。 */
class WebLoginActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var importing by mutableStateOf(false)
    private var message by mutableStateOf("完成学校网页登录后，点右侧导入会话")

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UestcHelperTheme {
                Scaffold(topBar = {
                    TopAppBar(title = { Text("学校网页登录") }, navigationIcon = {
                        TextButton(onClick = { finish() }) { Text("返回") }
                    }, actions = {
                        TextButton(onClick = ::importSession, enabled = !importing) { Text("导入会话") }
                    })
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        AndroidView(factory = { createBrowser() }, modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowser(): WebView = WebView(this).apply {
        browser = this
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.userAgentString = ApiConstants.USER_AGENT
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                return request.url.scheme != "https" || !(host == "uestc.edu.cn" || host.endsWith(".uestc.edu.cn"))
            }
        }
        lifecycleScope.launch {
            val manager = CookieManager.getInstance()
            (application as NightSignApp).repository.jar.snapshot().forEach { cookie ->
                val host = cookie.domain.removePrefix(".")
                if (host == "idas.uestc.edu.cn" || host == "jzsz.uestc.edu.cn") {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        manager.setCookie("https://$host${cookie.path}", cookie.toString()) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
            }
            loadUrl(ApiConstants.CAS_SERVICE_RAW)
        }
    }

    private fun importSession() {
        if (importing) return
        val manager = CookieManager.getInstance()
        manager.flush()
        val headers = mapOf(
            "${ApiConstants.CAS_ORIGIN}/authserver/" to manager.getCookie("${ApiConstants.CAS_ORIGIN}/authserver/").orEmpty(),
            ApiConstants.CAS_SERVICE_RAW to manager.getCookie(ApiConstants.CAS_SERVICE_RAW).orEmpty(),
        )
        importing = true
        lifecycleScope.launch {
            try {
                (application as NightSignApp).repository.importWebCookies(headers)
                setResult(RESULT_OK)
                finish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "导入未通过校验，请确认已经进入晚安成电页面后重试" }
            finally { importing = false }
        }
    }

    override fun onDestroy() {
        browser?.apply { stopLoading(); destroy() }
        browser = null
        super.onDestroy()
    }
}
