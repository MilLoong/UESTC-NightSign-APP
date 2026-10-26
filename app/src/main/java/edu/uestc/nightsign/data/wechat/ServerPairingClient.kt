package edu.uestc.nightsign.data.wechat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.uestc.nightsign.data.network.WechatCodeSettings
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ServerPairingOffer(val endpoint: String, val code: String, val serverId: String? = null) {
    override fun toString() = "ServerPairingOffer(redacted)"
    companion object {
        fun parse(raw: String): ServerPairingOffer = try {
            require(raw.length <= 4096)
            val data = JsonParser.parseString(raw).asJsonObject
            require(data.get("kind").asString == "uestc-nightsign-pair" && data.get("version").toString() == "1")
            val url = data.get("server").asString.toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
                url.encodedPath == "/" && url.query == null && url.fragment == null)
            val code = data.get("code").asString
            require(Regex("[A-Za-z0-9_-]{43}").matches(code))
            val serverId = data.get("server_id")?.takeUnless { it.isJsonNull }?.asString
            require(serverId == null || Regex("[A-Za-z0-9_-]{43}").matches(serverId))
            ServerPairingOffer(url.toString().trimEnd('/'), code, serverId)
        } catch (_: Exception) { throw IllegalArgumentException("不是有效的晚安成电服务器绑定码") }
    }
}

data class ServerAuthorizationStatus(val authorized: Boolean, val ref: String?, val status: String)

class ServerPairingClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    fun pair(offer: ServerPairingOffer, installationKey: String? = null,
        previous: WechatCodeSettings? = null): WechatCodeSettings {
        // Validate even when the offer was constructed without using the scanner parser.
        val checked = ServerPairingOffer.parse(JsonObject().apply {
            addProperty("kind", "uestc-nightsign-pair"); addProperty("version", 1)
            addProperty("server", offer.endpoint); addProperty("code", offer.code)
            offer.serverId?.let { addProperty("server_id", it) }
        }.toString())
        val body = JsonObject().apply {
            addProperty("code", checked.code)
            installationKey?.let { key ->
                require(Regex("[A-Za-z0-9_-]{43}").matches(key))
                addProperty("installation_key", key)
                previous?.accessToken?.takeIf { Regex("[A-Za-z0-9_-]{43,128}").matches(it) }?.let { token ->
                    addProperty("previous_proof", PairingIdentity.previousProof(token, checked.code, key))
                }
            }
        }
        val request = Request.Builder().url(checked.endpoint + "/v1/pair")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(if (response.code == 400) "绑定码已过期或已使用，请刷新服务端页面" else "连接服务器失败（HTTP ${response.code}）")
            val bytes = response.peekBody(4097).bytes()
            if (bytes.size > 4096) throw IOException("服务器绑定响应过大")
            val issued = try {
                val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                require(json.get("code").asInt == 0)
                val data = json.getAsJsonObject("data")
                val token = data.get("token").asString
                require(Regex("[A-Za-z0-9_-]{43,128}").matches(token))
                val ref = data.get("ref")?.takeUnless { it.isJsonNull }?.asString
                require(ref == null || Regex("[A-Za-z0-9_-]{1,128}").matches(ref))
                token to ref
            } catch (_: Exception) { throw IOException("服务器绑定响应无效") }
            return WechatCodeSettings(endpoint = checked.endpoint, accessToken = issued.first, accountRef = issued.second.orEmpty())
        }
    }

    fun status(settings: WechatCodeSettings): ServerAuthorizationStatus {
        val request = Request.Builder().url(settings.validatedUrl().newBuilder().encodedPath("/v1/device").build())
            .header("Authorization", "Bearer ${settings.accessToken}").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(if (response.code in listOf(401, 403))
                "服务器绑定已失效，请重新扫码连接" else "同步授权状态失败（HTTP ${response.code}）")
            val bytes = response.peekBody(4097).bytes()
            if (bytes.size > 4096) throw IOException("服务器状态响应过大")
            return try {
                val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                require(json.get("code").asInt == 0)
                val data = json.getAsJsonObject("data")
                val authorized = data.get("authorized").asBoolean
                val ref = data.get("ref")?.takeUnless { it.isJsonNull }?.asString
                require(if (authorized) Regex("[A-Za-z0-9_-]{1,128}").matches(ref.orEmpty()) else ref.isNullOrEmpty())
                ServerAuthorizationStatus(authorized, ref, data.get("status").asString)
            } catch (_: Exception) { throw IOException("服务器授权状态无效") }
        }
    }

    fun revoke(settings: WechatCodeSettings) {
        val request = Request.Builder().url(settings.validatedUrl().newBuilder().encodedPath("/v1/device").build())
            .header("Authorization", "Bearer ${settings.accessToken}").delete().build()
        client.newCall(request).execute().use { if (!it.isSuccessful) throw IOException("服务器未确认撤销绑定") }
    }
}
