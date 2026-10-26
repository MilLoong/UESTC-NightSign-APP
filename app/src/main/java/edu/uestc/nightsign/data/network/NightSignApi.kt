package edu.uestc.nightsign.data.network

import edu.uestc.nightsign.domain.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.time.format.DateTimeFormatter

class SessionExpiredException(val diagnostic: ApiDiagnostic? = null) :
    IOException(diagnostic?.summary() ?: "登录状态已过期，请重新登录")

data class ApiDiagnostic(
    val endpoint: String,
    val httpStatus: Int,
    val businessCode: Int?,
    val category: String,
    val safeMessage: String,
) {
    fun summary() = "HTTP $httpStatus code=${businessCode ?: "unknown"} [$category] $endpoint：$safeMessage"
}

class CheckinAuthorizationException(val diagnostic: ApiDiagnostic) : IOException(diagnostic.summary())

// Only retain recognized public messages; arbitrary server text may echo credentials or personal data.
private fun safePlatformMessage(message: String): String = when (message.trim()) {
    "请从微信小程序重新进入打卡应用后打卡",
    "请从微信小程序重新进入打卡应用后打卡。",
    "登录无效或已过期",
    "暂未开通打卡" -> message.trim()
    else -> if (message.isBlank()) "平台未提供提示" else "平台提示已隐藏（避免记录敏感信息）"
}

/** 只负责晚安成电协议，不依赖界面或本地存储。 */
class NightSignApi(
    private val client: OkHttpClient,
    private val origin: String = ApiConstants.PLATFORM_ORIGIN,
    private val onDiagnostic: (ApiDiagnostic) -> Unit = {},
    private val checkinTicketProvider: (() -> String)? = null,
) {
    private val businessClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private fun request(path: String, body: JsonObject? = null, portal: Boolean = false,
                        platform: String = "h5", ticket: String? = null): JsonObject {
        val builder = Request.Builder().url(origin + path)
            .header("Platform", platform)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", origin)
            .header("Referer", if (portal) "$origin/" else "$origin/daily-checkin-h5/index.html?appId=294")
        if (portal) builder.header("Content-Type", "application/json;charset=UTF-8")
        if (ticket != null) {
            require(Regex("[A-Za-z0-9_-]{43}").matches(ticket)) { "打卡授权响应无效" }
            builder.header("X-Checkin-Ticket", ticket)
        }
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        businessClient.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            val code = runCatching { json?.get("code")?.asString?.toIntOrNull() }.getOrNull()
            val message = runCatching { json?.text("msg").orEmpty().ifBlank { json?.text("message").orEmpty() } }
                .getOrDefault("")
            fun diagnostic(category: String, fallback: String): ApiDiagnostic {
                val result = ApiDiagnostic(path.substringBefore('?'), response.code, code, category,
                    if (message.isBlank()) fallback else safePlatformMessage(message))
                runCatching { onDiagnostic(result) }
                return result
            }
            val operation = path.substringBefore('?') in setOf(ApiConstants.CHECKIN,
                "/daily-checkin/api/student-checkin-record/report")
            if (operation && (code in setOf(46001, 46002, 46003) ||
                    message.trim().removeSuffix("。") == "请从微信小程序重新进入打卡应用后打卡")) {
                throw CheckinAuthorizationException(diagnostic("WECHAT_AUTH", "需要微信小程序打卡授权"))
            }
            if (response.code in listOf(301, 302, 303, 307, 308, 401) || code == 4001 ||
                (code == 5000 && listOf("未登录", "会话已失效", "会话无效").any { message.contains(it) }) ||
                response.request.url.host != origin.toHttpUrl().host) {
                throw SessionExpiredException(diagnostic("SESSION", "登录状态已过期，请重新登录"))
            }
            if (!response.isSuccessful) throw IOException(diagnostic("HTTP", "平台拒绝请求").summary())
            if (json == null) throw IOException(diagnostic("FORMAT", "平台未返回有效 JSON").summary())
            if (!portal && (code != 0 || json.get("ok")?.asBoolean == false)) {
                throw IOException(diagnostic("BUSINESS", "平台操作失败").summary())
            }
            return json
        }
    }

    fun warmUp() {
        request("${ApiConstants.WARMUP}?_t=${System.currentTimeMillis()}", portal = true)
    }

    fun sessionFromWechat(code: String): String {
        require(Regex("[A-Za-z0-9_-]{24,128}").matches(code))
        val body = JsonObject().apply { addProperty("code", code); addProperty("sn", "") }
        val cleanApi = NightSignApi(client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build(), origin)
        val data = cleanApi.request("/platform-app/api/auth/check-bind", body, platform = "mp-weixin")
            .getAsJsonObject("data") ?: throw IOException("学校登录响应无效")
        if (data.get("bind")?.asBoolean != true) throw IOException("请先在成电智慧学工小程序绑定学校身份")
        return data.text("sessionId").also {
            if (!Regex("[A-Za-z0-9._-]{10,256}").matches(it)) throw IOException("学校会话响应无效")
        }
    }

    fun requestCheckinTicket(code: String): String {
        require(Regex("[A-Za-z0-9_-]{24,128}").matches(code)) { "微信登录凭证格式无效" }
        val body = JsonObject().apply { addProperty("code", code) }
        val result = request(ApiConstants.CHECKIN_TICKET, body, platform = "mp-weixin")
        val ticket = runCatching { result.getAsJsonObject("data").text("ticket") }.getOrDefault("")
        if (!Regex("[A-Za-z0-9_-]{43}").matches(ticket)) throw IOException("打卡授权响应无效")
        return ticket
    }

    fun enterApp() {
        val request = Request.Builder().url(origin + ApiConstants.APP_ENTRY)
            .header("Referer", "$origin/").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.request.url.host != origin.toHttpUrl().host ||
                !response.request.url.encodedPath.startsWith("/daily-checkin-h5/")) {
                val diagnostic = ApiDiagnostic(ApiConstants.APP_ENTRY.substringBefore('?'), response.code,
                    null, "SESSION", "未能进入打卡应用，请重新登录")
                runCatching { onDiagnostic(diagnostic) }
                throw SessionExpiredException(diagnostic)
            }
        }
    }

    fun today(): CheckinStatus {
        val data = request(ApiConstants.TODAY).getAsJsonObject("data")
        return CheckinStatus(
            data.get("businessDate").asString, data.get("recordStatusCode").asString,
            data.text("checkinTime").takeUnless { it.isBlank() },
            data.text("checkinLocationName"), data.get("abnormalFlag")?.asBoolean ?: false,
            data.text("studentSn"), data.text("studentName"),
        )
    }

    fun schedule(): CheckinSchedule {
        val data = request(ApiConstants.SCHEDULE).getAsJsonObject("data")
        val start = data.get("studentCheckinStartOffsetMinute").asInt
        val end = data.get("studentCheckinEndOffsetMinute").asInt
        require(start in 0..1439 && end in start..1439) { "平台签到时段格式已变化" }
        return CheckinSchedule(start, end, data.get("effective")?.asBoolean ?: false)
    }

    fun locations(): List<CheckinLocation> {
        val items = request(ApiConstants.LOCATIONS).getAsJsonArray("data")
        return items.map { element ->
            val item = element.asJsonObject
            CheckinLocation(item.get("locationName").asString, item.get("latitude").asDouble,
                item.get("longitude").asDouble, item.get("radiusMeter").asDouble)
        }
    }

    fun reverse(lat: Double, lng: Double): String {
        val data = request("${ApiConstants.REVERSE}?lat=$lat&lng=$lng").get("data")
        return when {
            data.isJsonPrimitive && data.asJsonPrimitive.isString -> data.asString
            data.isJsonObject -> listOf("address", "reverseGeocode", "formattedAddress")
                .firstNotNullOfOrNull { data.asJsonObject.text(it).takeIf(String::isNotBlank) }.orEmpty()
            else -> ""
        }.also { require(it.isNotBlank()) { "未能获取地址，请手动填写" } }
    }

    fun submit(settings: SignSettings) {
        val (lat, lng) = settings.coordinates()
        require(settings.address.isNotBlank()) { "请先填写或解析地址" }
        val payload = JsonObject().apply {
            addProperty("latitude", lat)
            addProperty("longitude", lng)
            addProperty("reverseGeocode", settings.address)
            addProperty("clientTime", CheckinPolicy.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            addProperty("userAgent", ApiConstants.USER_AGENT)
            addProperty("coordinateSystemCode", "gcj02")
            addProperty("accuracy", 86)
            addProperty("locationDurationMs", 1189)
            addProperty("networkTypeCode", "4g")
            addProperty("locationEnabled", false)
            addProperty("locationAuthorized", "")
            addProperty("locationAccuracy", "")
            addProperty("errorMessage", "")
        }
        val result = request(ApiConstants.CHECKIN, payload, ticket = checkinTicketProvider?.invoke())
        if (result.get("data")?.asBoolean != true) throw IOException("平台未确认提交，请刷新今日状态")
    }
}

private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
