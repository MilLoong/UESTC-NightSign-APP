package edu.uestc.nightsign.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentGeocodeTest {
    @Test fun prefersRecommendedAddress() {
        val raw = """
            {"status":0,"message":"query ok","result":{
              "address":"四川省成都市郫都区",
              "formatted_addresses":{"recommend":"电子科技大学清水河校区","rough":"郫都区"}
            }}
        """.trimIndent()
        assertEquals("电子科技大学清水河校区", parseTencentGeocode(raw))
    }

    @Test fun fallsBackToPlainAddress() {
        val raw = """{"status":0,"result":{"address":"四川省成都市郫都区"}}"""
        assertEquals("四川省成都市郫都区", parseTencentGeocode(raw))
    }

    @Test fun reportsTencentError() {
        val error = runCatching { parseTencentGeocode("""{"status":311,"message":"key格式错误"}""") }.exceptionOrNull()
        assertTrue(error?.message?.contains("key格式错误") == true)
    }
}
