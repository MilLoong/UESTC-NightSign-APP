package edu.uestc.nightsign.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class CheckinPolicyTest {
    private val settings = SignSettings("30.1", "103.1", "测试地址", 1320, true)
    private val schedule = CheckinSchedule(1260, 1410, true)
    private fun at(time: String) = ZonedDateTime.parse("2026-10-21T${time}:00+08:00[Asia/Shanghai]")

    @Test fun doesNotRunBeforeTarget() {
        assertFalse(CheckinPolicy.shouldRun(settings, schedule, at("21:59")))
    }
    @Test fun runsAtTarget() {
        assertTrue(CheckinPolicy.shouldRun(settings, schedule, at("22:00")))
    }
    @Test fun doesNotRunAfterWindow() {
        assertFalse(CheckinPolicy.shouldRun(settings, schedule, at("23:31")))
    }
    @Test fun honorsDisabledScheduleAndSwitch() {
        assertFalse(CheckinPolicy.shouldRun(settings.copy(autoEnabled = false), schedule, at("22:00")))
        assertFalse(CheckinPolicy.shouldRun(settings, schedule.copy(effective = false), at("22:00")))
    }
    @Test fun usesShanghaiNotDeviceTimezone() {
        assertTrue(CheckinPolicy.shouldRun(settings, schedule, ZonedDateTime.parse("2026-10-21T14:00:00Z")))
    }
    @Test fun rejectsInvalidTimes() {
        listOf("24:00", "22:60", "-1:00", "9:00", "22:0", "", "NaN").forEach { assertNull(parseMinute(it)) }
        assertEquals(1320, parseMinute("22:00"))
        assertEquals("23:30", formatMinute(1410))
    }
    @Test fun rejectsInvalidCoordinates() {
        listOf("NaN" to "103", "Infinity" to "103", "91" to "103", "30" to "181", "" to "103", "0" to "0").forEach { (lat, lng) ->
            assertTrue(runCatching { settings.copy(latitude = lat, longitude = lng).coordinates() }.isFailure)
        }
    }
    @Test fun keepsLatitudeAndLongitudeOrder() {
        assertEquals(30.1 to 103.1, settings.coordinates())
    }
}
