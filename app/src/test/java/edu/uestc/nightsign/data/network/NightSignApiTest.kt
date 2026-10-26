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
    private val diagnostics = mutableListOf<ApiDiagnostic>()
    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = NightSignApi(OkHttpClient(), server.url("/").toString().trimEnd('/'), diagnostics::add)
    }
    @After fun tearDown() { server.shutdown() }
    private fun respond(body: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))

    @Test fun wechatSessionBootstrapNeverSendsOldCookie() {
        val jar = InMemoryCookieJar()
        jar.saveFromResponse(server.url("/"), listOf(okhttp3.Cookie.Builder()
            .name("SESSION").value("old-session-value").hostOnlyDomain(server.hostName).path("/").build()))
        val bootstrap = NightSignApi(OkHttpClient.Builder().cookieJar(jar).build(), server.url("/").toString().trimEnd('/'))
        respond("""{"code":0,"data":{"bind":true,"sessionId":"new-session-value"}}""")
        assertEquals("new-session-value", bootstrap.sessionFromWechat("c".repeat(32)))
        val request = server.takeRequest()
        assertNull(request.getHeader("Cookie"))
        assertEquals("mp-weixin", request.getHeader("Platform"))
        assertEquals("/platform-app/api/auth/check-bind", request.path)
        assertEquals("", JsonParser.parseString(request.body.readUtf8()).asJsonObject.get("sn").asString)
    }

    @Test fun wechatSessionRejectsUnboundIdentity() {
        respond("""{"code":0,"data":{"bind":false}}""")
        assertTrue(runCatching { api.sessionFromWechat("c".repeat(32)) }.isFailure)
    }

    @Test fun wechatSessionRejectsMalformedSession() {
        respond("""{"code":0,"data":{"bind":true,"sessionId":"bad"}}""")
        assertTrue(runCatching { api.sessionFromWechat("c".repeat(32)) }.isFailure)
    }

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

    @Test fun distinguishesWechatAuthorizationFromExpiredSession() {
        for (code in listOf(46001, 46002, 46003)) {
            respond("""{"code":$code,"msg":"请从微信小程序重新进入打卡应用后打卡"}""")
            val error = runCatching { api.submit(SignSettings("30.1", "103.1", "测试地址")) }.exceptionOrNull()
            assertTrue(error is CheckinAuthorizationException)
            assertFalse(error is SessionExpiredException)
            assertEquals(code, diagnostics.last().businessCode)
            assertEquals(200, diagnostics.last().httpStatus)
            assertTrue(error!!.message!!.contains("code=$code"))
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun readsWechatErrorEnvelopeBeforeHttp403Handling() {
        server.enqueue(MockResponse().setResponseCode(403)
            .setBody("""{"code":46002,"message":"请从微信小程序重新进入打卡应用后打卡"}"""))
        val error = runCatching { api.submit(SignSettings("30.1", "103.1", "测试地址")) }.exceptionOrNull()
        assertTrue(error is CheckinAuthorizationException)
        assertEquals(403, diagnostics.single().httpStatus)
    }

    @Test fun classifiesKnownMessageWithoutInventingBusinessCode() {
        respond("""{"msg":"请从微信小程序重新进入打卡应用后打卡"}""")
        val error = runCatching { api.submit(SignSettings("30.1", "103.1", "测试地址")) }.exceptionOrNull()
        assertTrue(error is CheckinAuthorizationException)
        assertNull(diagnostics.single().businessCode)
    }

    @Test fun diagnosticsNeverRetainQueriesHeadersBodiesOrUnknownMessages() {
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Set-Cookie", "SESSION=secret-cookie")
            .setBody("""{"code":4999,"msg":"token=secret-token ticket=secret-ticket 学号123456","data":{"password":"secret-password"}}"""))
        val error = runCatching { api.reverse(30.12345, 103.54321) }.exceptionOrNull()!!
        val output = diagnostics.single().toString() + error.message
        for (secret in listOf("secret-cookie", "secret-token", "secret-ticket", "secret-password", "123456", "30.12345", "103.54321")) {
            assertFalse(output.contains(secret))
        }
        assertEquals(ApiConstants.REVERSE, diagnostics.single().endpoint)
        assertEquals(4999, diagnostics.single().businessCode)
    }

    @Test fun forbiddenWithoutTicketErrorIsNotAssumedToBeExpiredSession() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<html>secret</html>"))
        val error = runCatching { api.today() }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error is SessionExpiredException)
        assertEquals("HTTP", diagnostics.single().category)
    }

    @Test fun malformedJsonProducesSafeFormatDiagnostic() {
        respond("{secret-invalid-json")
        val error = runCatching { api.today() }.exceptionOrNull()!!
        assertEquals("FORMAT", diagnostics.single().category)
        assertFalse(error.message!!.contains("secret"))
    }

    @Test fun exchangesCodeForTicketWithoutSubmittingCheckin() {
        val ticket = "t".repeat(43)
        respond("""{"code":0,"ok":true,"data":{"ticket":"$ticket"}}""")
        assertEquals(ticket, api.requestCheckinTicket("c".repeat(32)))
        val request = server.takeRequest()
        assertEquals(ApiConstants.CHECKIN_TICKET, request.path)
        assertEquals("mp-weixin", request.getHeader("Platform"))
        assertNull(request.getHeader("X-Checkin-Ticket"))
        assertEquals("c".repeat(32), JsonParser.parseString(request.body.readUtf8()).asJsonObject.get("code").asString)
        assertEquals(1, server.requestCount)
        assertTrue(diagnostics.isEmpty())
    }

    @Test fun rejectsInvalidTicketResponsesAndInvalidInputWithoutLeakingCredentials() {
        respond("""{"code":0,"data":{"ticket":"secret-invalid-ticket"}}""")
        val error = runCatching { api.requestCheckinTicket("c".repeat(32)) }.exceptionOrNull()!!
        assertFalse(error.message!!.contains("secret-invalid-ticket"))
        assertTrue(runCatching { api.requestCheckinTicket("invalid") }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun onlySubmissionUsesFreshTicketAndNeverFallsBackAfterProviderFailure() {
        var calls = 0
        val api = NightSignApi(OkHttpClient(), server.url("/").toString().trimEnd('/'),
            checkinTicketProvider = { calls++; "t".repeat(43) })
        respond("""{"code":0,"data":{"businessDate":"2026-10-24","recordStatusCode":"PENDING"}}""")
        api.today()
        assertEquals(0, calls)
        assertNull(server.takeRequest().getHeader("X-Checkin-Ticket"))
        repeat(2) {
            respond("""{"code":0,"data":true}""")
            api.submit(SignSettings("30.1", "103.1", "测试地址"))
            assertEquals("t".repeat(43), server.takeRequest().getHeader("X-Checkin-Ticket"))
        }
        assertEquals(2, calls)
        val failing = NightSignApi(OkHttpClient(), server.url("/").toString().trimEnd('/'),
            checkinTicketProvider = { throw java.io.IOException("授权失败") })
        assertTrue(runCatching { failing.submit(SignSettings("30.1", "103.1", "测试地址")) }.isFailure)
        assertEquals(3, server.requestCount)
    }
}
