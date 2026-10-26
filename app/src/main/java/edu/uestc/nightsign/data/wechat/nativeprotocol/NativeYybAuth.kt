package edu.uestc.nightsign.data.wechat.nativeprotocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.uestc.nightsign.data.network.InMemoryCookieJar
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Direct phone-local OAuth. School cookies are sent only to the fixed school origin. */
internal class NativeYybAuth(private val cancelled: AtomicBoolean, private val progress: (String) -> Unit,
    private val showQr: (String) -> Unit, private val save: (String) -> Unit) {
    companion object {
        private const val CALLBACK = "https://yybadaccess.3g.qq.com/pc_yyb/pcyyb_oauth"
        private const val AUTH = "https://yybadaccess.3g.qq.com/pc_yyb_auth/"
        internal fun signature(body: String, timestamp: String, nonce: String): String =
            MessageDigest.getInstance("MD5").digest((body + timestamp + "wgrdg373hy26ww2" + nonce).toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private val jar = InMemoryCookieJar()
    private val client = OkHttpClient.Builder().cookieJar(jar).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).retryOnConnectionFailure(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS).build()
    private val schoolClient = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    private fun checkCancelled() { check(!cancelled.get() && !Thread.currentThread().isInterrupted) { "AUTH_CANCELLED" } }
    private fun request(request: Request, school: Boolean = false): Pair<Int, ByteArray> {
        checkCancelled()
        val url = request.url
        require(url.isHttps && url.port == 443 && url.host in setOf("open.weixin.qq.com", "long.open.weixin.qq.com", "yybadaccess.3g.qq.com", "jzsz.uestc.edu.cn")) {
            "AUTH_URL_BLOCKED"
        }
        require(school == (url.host == "jzsz.uestc.edu.cn")) { "COOKIE_SCOPE_INVALID" }
        (if (school) schoolClient else client).newCall(request).execute().use { response ->
            val bytes = response.peekBody(2_000_001).bytes()
            require(bytes.size <= 2_000_000) { "AUTH_RESPONSE_TOO_LARGE" }
            checkCancelled()
            return response.code to bytes
        }
    }
    private fun jsonGet(url: String, session: String): JsonObject {
        require(Regex("[A-Za-z0-9._~-]{10,512}").matches(session)) { "SCHOOL_SESSION_INVALID" }
        val (status, body) = request(Request.Builder().url(url).header("Cookie", "SESSION=$session").header("platform", "h5").build(), true)
        require(status == 200) { "SCHOOL_IDENTITY_HTTP_FAILED" }
        val result = JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject
        require(result["code"]?.asInt == 0 && result["ok"]?.asBoolean != false) { "SCHOOL_SESSION_INVALID" }
        return result.getAsJsonObject("data").getAsJsonObject("userData")
    }
    private fun signed(operation: String, payload: JsonObject, credentials: JsonObject? = null): JsonObject {
        val body = payload.toString()
        val timestamp = System.currentTimeMillis().toString()
        val nonce = SecureRandom().nextInt(10000).toString()
        val builder = Request.Builder().url(AUTH + operation).header("Accept", "application/json")
            .header("Ual-Access-Businessid", "pc_yyb_auth").header("Ual-Access-Timestamp", timestamp)
            .header("Ual-Access-Nonce", nonce).header("Ual-Access-Signature", signature(body, timestamp, nonce))
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (credentials != null) {
            val values = listOf("openid", "access_token", "refresh_token").map { credentials[it]?.asString.orEmpty() }
            require(values.all { it.length <= 8192 && it.none { ch -> ch < ' ' || ch in ";\"\\" } }) { "CREDENTIAL_INVALID" }
            builder.header("Cookie", "openid=${values[0]}; accesstoken=${values[1]}; refreshtoken=${values[2]}")
        }
        val (status, raw) = request(builder.build())
        require(status == 200) { "YYB_HTTP_FAILED" }
        val result = JsonParser.parseString(raw.toString(Charsets.UTF_8)).asJsonObject
        require(result["code"]?.asInt == 0) { "WECHAT_CREDENTIAL_REJECTED" }
        return result
    }
    private fun authorize(userId: String): JsonObject {
        progress("正在生成微信授权二维码…")
        val url = "https://open.weixin.qq.com/connect/qrconnect".toHttpUrl().newBuilder()
            .addQueryParameter("appid", "wxd44977328b36e647").addQueryParameter("redirect_uri", "$CALLBACK?login_type=WX")
            .addQueryParameter("response_type", "code").addQueryParameter("scope", "snsapi_login,snsapi_runtime_pcsdk")
            .addQueryParameter("state", "web").addQueryParameter("fast_login", "1").addQueryParameter("self_redirect", "true").build()
        val (status, page) = request(Request.Builder().url(url).build())
        val uuid = Regex("/connect/qrcode/([A-Za-z0-9._~-]{1,256})").find(page.toString(Charsets.UTF_8))?.groupValues?.get(1)
        require(status == 200 && uuid != null) { "QR_UNAVAILABLE" }
        val (imageStatus, image) = request(Request.Builder().url("https://open.weixin.qq.com/connect/qrcode/$uuid").build())
        require(imageStatus == 200 && image.isNotEmpty()) { "QR_IMAGE_FAILED" }
        showQr(Base64.getEncoder().encodeToString(image))
        progress("请用本人微信扫码并确认授权，确认后返回 APP…")
        val deadline = NativeNetwork.now() + 210000
        var oauthCode: String? = null
        var failures = 0
        while (NativeNetwork.now() < deadline) {
            checkCancelled()
            val pollUrl = "https://long.open.weixin.qq.com/connect/l/qrconnect".toHttpUrl().newBuilder()
                .addQueryParameter("uuid", uuid).addQueryParameter("_", System.currentTimeMillis().toString()).build()
            val result = try {
                val (http, body) = request(Request.Builder().url(pollUrl).build())
                require(http == 200) { "QR_POLL_HTTP_FAILED" }
                body.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                checkCancelled()
                if (++failures > 3) throw e
                progress("二维码状态连接中断，正在重连，请保留原二维码…")
                Thread.sleep(1000)
                continue
            }
            when (Regex("wx_errcode\\s*=\\s*(\\d+)").find(result)?.groupValues?.get(1)?.toInt()) {
                405 -> {
                    oauthCode = Regex("wx_code\\s*=\\s*['\"]([A-Za-z0-9._~-]+)['\"]").find(result)?.groupValues?.get(1)
                    require(oauthCode != null) { "QR_CODE_MISSING" }
                    break
                }
                404 -> progress("已扫码，请在微信确认授权后返回 APP…")
                408 -> Unit
                else -> error("QR_EXPIRED_OR_CANCELLED")
            }
            Thread.sleep(1000)
        }
        require(oauthCode != null) { "QR_EXPIRED" }
        progress("微信已确认，正在接收授权凭据…")
        var callback = CALLBACK.toHttpUrl().newBuilder().addQueryParameter("login_type", "WX")
            .addQueryParameter("code", oauthCode).addQueryParameter("state", "web").build()
        repeat(6) {
            checkCancelled()
            client.newCall(Request.Builder().url(callback).build()).execute().use { response ->
                val values = jar.loadForRequest(CALLBACK.toHttpUrl()).associate { cookie -> cookie.name to cookie.value }
                if (!values["openid"].isNullOrBlank() && !values["accesstoken"].isNullOrBlank()) {
                    return JsonObject().apply {
                        addProperty("openid", values["openid"])
                        addProperty("access_token", values["accesstoken"])
                        addProperty("refresh_token", values["refreshtoken"].orEmpty())
                        addProperty("expires_at", System.currentTimeMillis() / 1000 + (values["expires_in"]?.toLongOrNull() ?: 7200L))
                        addProperty("school_user_id", userId)
                    }
                }
                require(response.code in setOf(301, 302, 303, 307, 308)) { "OAUTH_CREDENTIALS_MISSING" }
                val next = callback.resolve(response.header("Location").orEmpty()) ?: error("OAUTH_REDIRECT_INVALID")
                require(next.isHttps && next.host == "yybadaccess.3g.qq.com" && next.port == 443 && next.username.isEmpty() && next.password.isEmpty()) { "OAUTH_REDIRECT_BLOCKED" }
                callback = next
            }
        }
        error("OAUTH_REDIRECT_LIMIT")
    }
    fun getCode(session: String, cache: String?, allowQr: Boolean): String {
        progress("正在核对学校登录身份…")
        val userId = jsonGet("https://jzsz.uestc.edu.cn/daily-checkin/api/auth/user-info", session)["id"].asString
        var credentials = runCatching { JsonParser.parseString(cache.orEmpty()).asJsonObject }.getOrNull()
            ?.takeIf { it["school_user_id"]?.asString == userId }
        if (credentials == null) {
            check(allowQr) { "AUTHORIZATION_REQUIRED" }
            credentials = authorize(userId)
        } else if ((credentials["expires_at"]?.asLong ?: 0) <= System.currentTimeMillis() / 1000 + 60) {
            renew(credentials)
        }
        checkCancelled()
        save(credentials.toString())
        return NativeMmtls(cancelled).getCode(freshBuffer(credentials), progress)
    }

    internal fun freshBuffer(credentials: JsonObject): String {
        val list = JsonObject()
        for ((name, value) in listOf("unionid" to credentials["openid"].asString, "user_id" to credentials["openid"].asString,
            "access_token" to credentials["access_token"].asString)) {
            list.add(name, JsonObject().apply { add("value", com.google.gson.JsonArray().apply { add(value) }) })
        }
        val ext = JsonObject().apply {
            add("listS", list)
            add("listI", JsonObject().apply { add("user_type", JsonObject().apply { add("value", com.google.gson.JsonArray().apply { add(0) }) }) })
        }
        progress("正在连接微信…")
        val result = signed("pcyyb_get_wx_login_buffer_auth", JsonObject().apply { add("extInfo", ext) }, credentials)
        return result.getAsJsonObject("ext_info").getAsJsonObject("list_s").getAsJsonObject("login_buffer").getAsJsonArray("value")[0].asString
    }

    internal fun renew(credentials: JsonObject) {
        progress("正在刷新微信授权…")
        val info = JsonObject().apply {
            addProperty("openId", credentials["openid"].asString)
            addProperty("refreshToken", credentials["refresh_token"].asString)
            addProperty("accessToken", credentials["access_token"].asString)
            addProperty("loginType", "WX")
        }
        val refreshed = signed("pcyyb_refresh_token_auth", JsonObject().apply { add("userInfo", info) }).getAsJsonObject("user_info")
        credentials.addProperty("access_token", refreshed["access_token"].asString)
        refreshed["refresh_token"]?.asString?.takeIf { it.isNotEmpty() }?.let { credentials.addProperty("refresh_token", it) }
        credentials.addProperty("expires_at", System.currentTimeMillis() / 1000 + refreshed["expires_in"].asLong)
    }
}
