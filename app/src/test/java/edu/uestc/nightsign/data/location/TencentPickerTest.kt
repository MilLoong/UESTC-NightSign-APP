package edu.uestc.nightsign.data.location

import org.junit.Assert.assertEquals
import org.junit.Test

class TencentPickerTest {
    @Test fun readsLatitudeLongitudeAndAddress() {
        val place = parseTencentPickerCallback(
            "https://nightsign.local/picked?latlng=30.753123,103.929456&addr=四川省成都市郫都区&name=清水河校区"
        )
        assertEquals(30.753123, place.latitude, 0.000001)
        assertEquals(103.929456, place.longitude, 0.000001)
        assertEquals("四川省成都市郫都区（清水河校区）", place.address)
    }

    @Test fun swapsWhenLongitudeComesFirst() {
        val place = parseTencentPickerCallback(
            "https://nightsign.local/picked?latlng=103.929456,30.753123&poiaddress=四川省成都市郫都区"
        )
        assertEquals(30.753123, place.latitude, 0.000001)
        assertEquals(103.929456, place.longitude, 0.000001)
        assertEquals("四川省成都市郫都区", place.address)
    }
}
