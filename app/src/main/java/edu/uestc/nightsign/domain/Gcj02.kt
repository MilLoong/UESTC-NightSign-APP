package edu.uestc.nightsign.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS-84 与 GCJ-02 互转。手机定位是 WGS-84，签到接口使用 GCJ-02。 */
object Gcj02 {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun fromWgs84(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (outOfChina(latitude, longitude)) return latitude to longitude
        var dLat = transformLat(longitude - 105.0, latitude - 35.0)
        var dLng = transformLng(longitude - 105.0, latitude - 35.0)
        val radLat = latitude / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return latitude + dLat to longitude + dLng
    }

    fun toWgs84(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (outOfChina(latitude, longitude)) return latitude to longitude
        var wgsLat = latitude
        var wgsLng = longitude
        repeat(8) {
            val (lat, lng) = fromWgs84(wgsLat, wgsLng)
            wgsLat -= lat - latitude
            wgsLng -= lng - longitude
        }
        return wgsLat to wgsLng
    }

    private fun outOfChina(latitude: Double, longitude: Double) =
        longitude !in 72.004..137.8347 || latitude !in 0.8293..55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        value += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        value += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        value += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return value
    }

    private fun transformLng(x: Double, y: Double): Double {
        var value = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        value += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        value += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        value += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return value
    }
}
