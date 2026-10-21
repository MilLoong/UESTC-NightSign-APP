package edu.uestc.nightsign.data.location

import android.location.Address
import android.location.Geocoder
import edu.uestc.nightsign.data.network.ApiConstants
import edu.uestc.nightsign.domain.Gcj02
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 用腾讯地图把 GCJ-02 坐标解析成地址。
 * 未配置 Key，或腾讯地图失败时，改用系统地理编码。
 */
class MapAddressResolver(
    private val context: android.content.Context,
    private val tencentKey: String,
) {
    fun resolve(latitude: Double, longitude: Double): String {
        val errors = mutableListOf<String>()
        if (tencentKey.isNotBlank()) {
            runCatching { return queryTencent(latitude, longitude) }
                .onFailure { errors += it.message ?: "腾讯地图解析失败" }
        }
        runCatching { return querySystem(latitude, longitude) }
            .onFailure { errors += it.message ?: "系统地图解析失败" }
        throw IOException(errors.lastOrNull() ?: "地图未返回地址，请手动填写")
    }

    private fun queryTencent(latitude: Double, longitude: Double): String {
        val url = "https://apis.map.qq.com/ws/geocoder/v1/".toHttpUrl().newBuilder()
            .addQueryParameter("location", "$latitude,$longitude")
            .addQueryParameter("key", tencentKey)
            .addQueryParameter("get_poi", "0")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", ApiConstants.USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("腾讯地图请求失败：HTTP ${response.code}")
            return parseTencentGeocode(raw)
        }
    }

    private fun querySystem(latitude: Double, longitude: Double): String {
        if (!Geocoder.isPresent()) throw IOException("当前设备没有地图地址服务，请手动填写")
        val (lat, lng) = Gcj02.toWgs84(latitude, longitude)
        val geocoder = Geocoder(context, Locale.CHINA)
        val line = lookup(geocoder, lat, lng).firstOrNull()?.readable()
        if (line.isNullOrBlank()) throw IOException("地图未返回地址，请手动填写")
        return line
    }

    @Suppress("DEPRECATION")
    private fun lookup(geocoder: Geocoder, latitude: Double, longitude: Double): List<Address> =
        geocoder.getFromLocation(latitude, longitude, 1) ?: emptyList()

    private fun Address.readable(): String {
        getAddressLine(0)?.takeIf { it.isNotBlank() }?.let { return it }
        return listOfNotNull(adminArea, locality, subLocality, thoroughfare, featureName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("")
    }

    private companion object {
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
