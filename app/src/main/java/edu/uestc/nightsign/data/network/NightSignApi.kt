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

class SessionExpiredException : IOException("登录状态已过期，请重新登录")

/** 只负责晚安成电协议，不依赖界面或本地存储。 */
class NightSignApi(
    private val client: OkHttpClient,
    private val origin: String = ApiConstants.PLATFORM_ORIGIN,
) {
    private val businessClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private fun request(path: String, body: JsonObject? = null, portal: Boolean = false): JsonObject {
        val builder = Request.Builder().url(origin + path)
            .header("Platform", "h5")
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", origin)
            .header("Referer", if (portal) "$origin/" else "$origin/daily-checkin-h5/index.html?appId=294")
        if (portal) builder.header("Content-Type", "application/json;charset=UTF-8")
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        businessClient.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (response.code in listOf(301, 302, 401, 403) || response.request.url.host != origin.toHttpUrl().host) {
                throw SessionExpiredException()
            }
            if (!response.isSuccessful) throw IOException("平台请求失败：HTTP ${response.code}")
            if (!raw.trimStart().startsWith("{")) throw IOException("平台未返回 JSON，请尝试 Web 登录")
            val json = JsonParser.parseString(raw).asJsonObject
            val code = json.get("code")?.asInt ?: -1
            if (code == 4001) throw SessionExpiredException()
            if (!portal && (code != 0 || json.get("ok")?.asBoolean == false)) {
                throw IOException(json.text("msg").ifBlank { "平台操作失败" }.take(160))
            }
            return json
        }
    }

    fun warmUp() {
        request("${ApiConstants.WARMUP}?_t=${System.currentTimeMillis()}", portal = true)
    }

    fun enterApp() {
        val request = Request.Builder().url(origin + ApiConstants.APP_ENTRY)
            .header("Referer", "$origin/").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.request.url.host != origin.toHttpUrl().host ||
                !response.request.url.encodedPath.startsWith("/daily-checkin-h5/")) {
                throw SessionExpiredException()
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
        val result = request(ApiConstants.CHECKIN, payload)
        if (result.get("data")?.asBoolean != true) throw IOException("平台未确认提交，请刷新今日状态")
    }
}

private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
