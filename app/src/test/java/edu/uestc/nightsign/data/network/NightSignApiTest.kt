package edu.uestc.nightsign.data.network

import edu.uestc.nightsign.domain.SignSettings
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NightSignApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: NightSignApi
    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = NightSignApi(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }
    @After fun tearDown() { server.shutdown() }
    private fun respond(body: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))

    @Test fun parsesOnlyRequiredStatusFields() {
        respond("""{"code":0,"data":{"businessDate":"2026-10-21","recordStatusCode":"PENDING","checkinTime":null,"studentMobile":"not-to-be-kept"}}""")
        val status = api.today()
        assertEquals("2026-10-21", status.businessDate)
        assertNull(status.checkinTime)
        assertFalse(status.checkedIn)
        assertFalse(status.toString().contains("not-to-be-kept"))
        assertEquals(ApiConstants.TODAY, server.takeRequest().path)
    }
    @Test fun classifiesExpiredBusinessSession() {
        respond("""{"code":4001,"msg":"登录无效或已过期"}""")
        assertTrue(runCatching { api.today() }.exceptionOrNull() is SessionExpiredException)
    }
    @Test fun classifiesUnauthorizedHttp() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { api.today() }.exceptionOrNull() is SessionExpiredException)
    }
    @Test fun rejectsHtmlChallenge() {
        respond("<html>请登录</html>")
        assertTrue(runCatching { api.today() }.isFailure)
    }
    @Test fun readsDynamicSchedule() {
        respond("""{"code":0,"data":{"effective":true,"studentCheckinStartOffsetMinute":1270,"studentCheckinEndOffsetMinute":1400}}""")
        assertEquals("21:10 - 23:20", api.schedule().label)
    }
    @Test fun doesNotTreatMissingScheduleAsEnabled() {
        respond("""{"code":0,"data":{"studentCheckinStartOffsetMinute":1260,"studentCheckinEndOffsetMinute":1410}}""")
        assertFalse(api.schedule().effective)
    }
    @Test fun warmupDoesNotAssumeDailyApiEnvelope() {
        respond("""{"success":true,"data":false}""")
        api.warmUp()
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("${ApiConstants.WARMUP}?_t="))
        assertEquals(server.url("/").toString(), request.getHeader("Referer"))
    }
    @Test fun entersMicroAppByFollowingRedirect() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/daily-checkin-h5/index.html?appId=294"))
        server.enqueue(MockResponse().setBody("<html></html>"))
        api.enterApp()
        assertEquals(ApiConstants.APP_ENTRY, server.takeRequest().path)
    }
    @Test fun rejectsPortalRedirectAsAppAuthorization() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/"))
        server.enqueue(MockResponse().setBody("<html>portal</html>"))
        assertTrue(runCatching { api.enterApp() }.exceptionOrNull() is SessionExpiredException)
    }
    @Test fun postsExpectedBodyAndHeaders() {
        respond("""{"code":0,"ok":true,"data":true}""")
        api.submit(SignSettings("30.1", "103.1", "测试地址"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(ApiConstants.CHECKIN, request.path)
        assertEquals("h5", request.getHeader("Platform"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(30.1, body.get("latitude").asDouble, 0.00001)
        assertEquals("gcj02", body.get("coordinateSystemCode").asString)
        assertEquals("测试地址", body.get("reverseGeocode").asString)
        assertEquals(ApiConstants.USER_AGENT, body.get("userAgent").asString)
    }
    @Test fun doesNotPostWithMissingAddress() {
        assertTrue(runCatching { api.submit(SignSettings("30.1", "103.1", "")) }.isFailure)
        assertEquals(0, server.requestCount)
    }
    @Test fun doesNotAcceptFalseSubmissionResult() {
        respond("""{"code":0,"data":false}""")
        assertTrue(runCatching { api.submit(SignSettings("30.1", "103.1", "测试地址")) }.isFailure)
    }
    @Test fun reverseSupportsStructuredAddress() {
        respond("""{"code":0,"data":{"formattedAddress":"测试地址"}}""")
        assertEquals("测试地址", api.reverse(30.1, 103.1))
    }
    @Test fun retainsAbnormalFlagEvenWhenCheckedIn() {
        respond("""{"code":0,"data":{"businessDate":"2026-10-21","recordStatusCode":"CHECKED_IN","abnormalFlag":true}}""")
        val status = api.today()
        assertTrue(status.checkedIn)
        assertTrue(status.abnormal)
    }
}
