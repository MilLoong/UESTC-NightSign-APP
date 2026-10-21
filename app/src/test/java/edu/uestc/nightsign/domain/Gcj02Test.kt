package edu.uestc.nightsign.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Gcj02Test {
    @Test fun leavesOverseasCoordinatesUntouched() {
        assertEquals(40.0 to -74.0, Gcj02.fromWgs84(40.0, -74.0))
        assertEquals(40.0 to -74.0, Gcj02.toWgs84(40.0, -74.0))
    }

    @Test fun shiftsCoordinatesInsideChina() {
        val (lat, lng) = Gcj02.fromWgs84(30.67, 104.06)
        assertTrue(abs(lat - 30.67) > 0.001)
        assertTrue(abs(lng - 104.06) > 0.001)
    }

    @Test fun roundTripsBackToWgs84() {
        val wgs = 30.754 to 103.929
        val gcj = Gcj02.fromWgs84(wgs.first, wgs.second)
        val back = Gcj02.toWgs84(gcj.first, gcj.second)
        assertEquals(wgs.first, back.first, 1e-6)
        assertEquals(wgs.second, back.second, 1e-6)
    }
}
