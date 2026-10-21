package edu.uestc.nightsign.data.location

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.abs

data class PickedPlace(val latitude: Double, val longitude: Double, val address: String)

/** 解析腾讯选点组件跳回的地址。坐标按纬度、经度，并在明显反了时对调。 */
fun parseTencentPickerCallback(url: String): PickedPlace {
    val http = url.toHttpUrl()
    val pair = http.queryParameter("latlng").orEmpty().split(',')
    require(pair.size == 2) { "地图没有返回坐标" }
    var latitude = pair[0].trim().toDouble()
    var longitude = pair[1].trim().toDouble()
    if (abs(latitude) > 90 && abs(longitude) <= 90) {
        val swap = latitude
        latitude = longitude
        longitude = swap
    }
    require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "地图返回的坐标无效" }
    val addressLine = sequenceOf("addr", "address", "poiaddress")
        .mapNotNull { http.queryParameter(it)?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()
    val name = sequenceOf("name", "poiname")
        .mapNotNull { http.queryParameter(it)?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()
    val address = when {
        addressLine.isNullOrEmpty() -> name.orEmpty()
        name.isNullOrEmpty() || addressLine.contains(name) -> addressLine
        else -> "$addressLine（$name）"
    }
    return PickedPlace(latitude, longitude, address)
}
