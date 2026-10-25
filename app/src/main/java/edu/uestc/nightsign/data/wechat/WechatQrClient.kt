package edu.uestc.nightsign.data.wechat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.uestc.nightsign.data.network.WechatCodeSettings
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class WechatQr(val id: String, val image: String) {
    override fun toString() = "WechatQr(redacted)"
}

class WechatQrRequestException(val retryable: Boolean, message: String) : IOException(message)

/** QR operations use the configured private HTTPS gateway. */
class WechatQrClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .proxy(java.net.Proxy.NO_PROXY)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(55, TimeUnit.SECONDS).callTimeout(65, TimeUnit.SECONDS).build()

    private fun call(config: WechatCodeSettings, path: String, post: Boolean): JsonObject {
        val base = config.validatedUrl()
        require(Regex("[A-Za-z0-9_-]{43,128}").matches(config.accessToken)) { "请先扫码连接服务器" }
        val request = Request.Builder().url(base.toString().trimEnd('/') + path)
            .header("Authorization", "Bearer ${config.accessToken}")
        if (post) request.post("{}".toRequestBody("application/json".toMediaType()))
        client.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw WechatQrRequestException(
                response.code == 409 || response.code == 408 || response.code == 429 || response.code in 500..599,
                when (response.code) {
                404 -> "二维码已失效，请重新生成"
                409 -> "微信授权尚未完成，请确认后重试"
                401, 403 -> "服务器绑定失效或无权限，请重新扫码连接"
                else -> "${qrFailureDetail(path, response.peekBody(8192).string())}（HTTP ${response.code}）"
            })
            val raw = response.peekBody(1_048_577).bytes()
            require(raw.size <= 1_048_576) { "二维码响应过大" }
            return try {
                val json = JsonParser.parseString(raw.toString(Charsets.UTF_8)).asJsonObject
                require(json.get("code").asInt == 0)
                json.getAsJsonObject("data")
            } catch (_: Exception) { throw WechatQrRequestException(false, "微信授权响应无效") }
        }
    }

    fun create(config: WechatCodeSettings): WechatQr {
        val data = call(config, "/wx/qrcodeauth?as_base64=true", true)
        val id = data.get("session_id").asString
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(id)) { "二维码标识无效" }
        val image = data.get("image_base64").asString
        require(Regex("data:image/(jpeg|jpg|png);base64,[A-Za-z0-9+/=]+").matches(image)) { "二维码图片无效" }
        return WechatQr(id, image)
    }

    fun poll(config: WechatCodeSettings, id: String): String {
        validId(id)
        return call(config, "/wx/qrcodeauth/$id/poll", false).get("status").asString
    }

    fun confirm(config: WechatCodeSettings, id: String): String {
        validId(id)
        val account = call(config, "/wx/qrcodeauth/$id/confirm", true).get("id").asLong
        require(account > 0) { "微信账号保存失败" }
        return account.toString()
    }

    fun cancel(config: WechatCodeSettings, id: String) {
        validId(id)
        call(config, "/wx/qrcodeauth/$id/cancel", true)
    }

    private fun validId(id: String) { require(Regex("[A-Za-z0-9_-]{1,128}").matches(id)) }
}
