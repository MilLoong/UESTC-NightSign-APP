package edu.uestc.nightsign.data.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class WechatCodeSettings(
    val endpoint: String = "http://127.0.0.1:18765",
    val accessToken: String = "",
    val mode: String? = null,
) {
    val rootMode: Boolean get() = mode == "root" || (mode == null && accessToken.isBlank())
    val enabled: Boolean get() = rootMode || accessToken.isNotBlank()
    override fun toString() = "WechatCodeSettings(rootMode=$rootMode, configured=$enabled)"

    fun validate() {
        require(mode == null || mode in setOf("root", "broker")) { "取码模式无效" }
        if (!rootMode) validatedUrl()
    }

    fun validatedUrl(): HttpUrl {
        val url = endpoint.toHttpUrlOrNull() ?: throw IllegalArgumentException("取码服务地址无效")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "取码服务地址不能包含账号、密码或查询参数"
        }
        require(url.isHttps || url.host in setOf("127.0.0.1", "localhost")) {
            "远程取码必须使用 HTTPS，本机 HTTP 只允许回环地址"
        }
        require(accessToken.isEmpty() || Regex("[A-Za-z0-9_-]{43,128}").matches(accessToken)) {
            "配对密钥格式无效"
        }
        return url
    }
}

/** A separate client prevents school cookies and school authorization from reaching the broker. */
class WechatCodeClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()

    fun fetchCode(settings: WechatCodeSettings): String {
        require(!settings.rootMode) { "本机 Root 模式不能使用远程取码服务" }
        val base = settings.validatedUrl()
        require(settings.enabled) { "请先配置微信取码服务" }
        val payload = JsonObject().apply { addProperty("appId", ApiConstants.WECHAT_APP_ID) }
        val request = Request.Builder().url(base.newBuilder().addPathSegments("v1/code").build())
            .header("Authorization", "Bearer ${settings.accessToken}")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val bytes = response.peekBody(4097).bytes()
            if (bytes.size > 4096) throw IOException("取码服务响应过大")
            val json = runCatching { JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject }.getOrNull()
            if (!response.isSuccessful) {
                val error = runCatching { json?.get("error")?.asString }.getOrNull()
                throw IOException(when (error) {
                    "UNAUTHORIZED" -> "取码服务配对密钥不匹配"
                    "TARGET_NOT_OPEN" -> "请在取码服务所在设备的微信中打开成电智慧学工"
                    "BUSY" -> "取码服务繁忙，请稍后再试"
                    "CDP_UNAVAILABLE", "CDP_CONNECT_TIMEOUT" -> "取码服务的微信调试通道未就绪"
                    else -> "取码服务不可用（HTTP ${response.code}）"
                })
            }
            val appId = runCatching { json?.get("appId")?.asString }.getOrNull()
            val code = runCatching { json?.get("code")?.asString }.getOrNull()
            if (appId != ApiConstants.WECHAT_APP_ID || code == null || !Regex("[A-Za-z0-9_-]{24,128}").matches(code)) {
                throw IOException("取码服务响应无效")
            }
            return code
        }
    }
}
