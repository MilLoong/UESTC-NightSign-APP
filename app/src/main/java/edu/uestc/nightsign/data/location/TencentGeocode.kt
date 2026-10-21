package edu.uestc.nightsign.data.location

import com.google.gson.JsonParser
import java.io.IOException

/** 解析腾讯地图逆地址编码响应，优先使用推荐地址。 */
fun parseTencentGeocode(raw: String): String {
    val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse {
        throw IOException("腾讯地图返回了无法识别的内容")
    }
    val status = json.get("status")?.takeUnless { it.isJsonNull }?.asInt ?: -1
    if (status != 0) {
        val message = json.get("message")?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(80)
        throw IOException(if (message.isBlank()) "腾讯地图解析失败" else "腾讯地图：$message")
    }
    val result = json.getAsJsonObject("result")
    val formatted = result?.getAsJsonObject("formatted_addresses")
    val recommend = formatted?.get("recommend")?.takeUnless { it.isJsonNull }?.asString
    val address = result?.get("address")?.takeUnless { it.isJsonNull }?.asString
    return recommend?.takeIf { it.isNotBlank() }
        ?: address?.takeIf { it.isNotBlank() }
        ?: throw IOException("腾讯地图未返回地址")
}
