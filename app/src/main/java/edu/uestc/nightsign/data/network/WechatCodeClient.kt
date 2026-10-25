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
    val endpoint: String = "",
    val accessToken: String = "",
    val mode: String? = "yyb",
    val accountRef: String? = "",
) {
    val enabled: Boolean get() = accessToken.isNotBlank() && !accountRef.isNullOrBlank()
    override fun toString() = "WechatCodeSettings(configured=$enabled)"

    fun validate() {
        require(mode == "yyb") { "请重新配置应用宝协议服务" }
        validatedUrl()
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(accountRef.orEmpty())) { "请填写应用宝扫码账号 ID" }
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
        settings.validate()
        val base = settings.validatedUrl()
        require(settings.enabled) { "请先配置微信取码服务" }
        val payload = JsonObject().apply {
            addProperty("app_id", ApiConstants.WECHAT_APP_ID)
            addProperty("ref", settings.accountRef)
        }
        val path = "wxapp/getCode"
        val request = Request.Builder().url(base.newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer ${settings.accessToken}")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val requestClient = client.newBuilder()
            .readTimeout(65, TimeUnit.SECONDS).callTimeout(70, TimeUnit.SECONDS)
            .apply { if (base.host in setOf("127.0.0.1", "localhost")) proxy(java.net.Proxy.NO_PROXY) }.build()
        requestClient.newCall(request).execute().use { response ->
            val bytes = response.peekBody(4097).bytes()
            if (bytes.size > 4096) throw IOException("取码服务响应过大")
            val json = runCatching { JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject }.getOrNull()
            if (!response.isSuccessful) {
                throw IOException(when (response.code) {
                    401, 403 -> "服务器绑定失效或账号无权限，请重新绑定并完成微信授权"
                    409 -> "应用宝登录状态失效，请重新扫码授权"
                    else -> "应用宝取码失败（HTTP ${response.code}），请检查账号状态"
                })
            }
            val code = runCatching {
                require(json?.get("code")?.asInt == 0) { "应用宝协议请求失败" }
                json?.getAsJsonObject("data")?.getAsJsonObject("result")?.get("code")?.asString
            }.getOrNull()
            if (code == null || !Regex("[A-Za-z0-9_-]{24,128}").matches(code)) {
                throw IOException("取码服务响应无效")
            }
            return code
        }
    }
}
