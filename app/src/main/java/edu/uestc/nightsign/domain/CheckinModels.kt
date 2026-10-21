package edu.uestc.nightsign.domain

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId

data class CheckinStatus(
    val businessDate: String,
    val recordStatusCode: String,
    val checkinTime: String?,
    val locationName: String,
    val abnormal: Boolean,
    val studentSn: String = "",
    val studentName: String = "",
) {
    val checkedIn: Boolean get() = recordStatusCode == "CHECKED_IN"
}

data class CheckinSchedule(val startMinute: Int, val endMinute: Int, val effective: Boolean) {
    fun contains(minute: Int): Boolean = effective && minute in startMinute..endMinute
    val label: String get() = "${formatMinute(startMinute)} - ${formatMinute(endMinute)}"
}

data class CheckinLocation(val name: String, val latitude: Double, val longitude: Double, val radius: Double)
data class CheckinOverview(val status: CheckinStatus, val schedule: CheckinSchedule, val locations: List<CheckinLocation>)

data class SignSettings(
    val latitude: String = "",
    val longitude: String = "",
    val address: String = "",
    val targetMinute: Int = 22 * 60,
    val autoEnabled: Boolean = false,
) {
    fun coordinates(): Pair<Double, Double> {
        val lat = latitude.toDoubleOrNull()
        val lng = longitude.toDoubleOrNull()
        require(lat != null && lat.isFinite() && lat in -90.0..90.0) { "请填写有效纬度" }
        require(lng != null && lng.isFinite() && lng in -180.0..180.0) { "请填写有效经度" }
        require(lat != 0.0 || lng != 0.0) { "请填写实际签到位置" }
        return lat to lng
    }
}

object CheckinPolicy {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    fun now(): ZonedDateTime = ZonedDateTime.now(zone)
    fun today(): String = LocalDate.now(zone).toString()
    fun shouldRun(settings: SignSettings, schedule: CheckinSchedule, now: ZonedDateTime): Boolean {
        val local = now.withZoneSameInstant(zone)
        val minute = local.hour * 60 + local.minute
        return settings.autoEnabled && minute >= settings.targetMinute && schedule.contains(minute)
    }
}

fun formatMinute(value: Int): String = "%02d:%02d".format(value / 60, value % 60)
fun parseMinute(value: String): Int? {
    if (!Regex("\\d{2}:\\d{2}").matches(value)) return null
    val (h, m) = value.split(":").map(String::toInt)
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}
