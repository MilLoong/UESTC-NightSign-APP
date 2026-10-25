package edu.uestc.nightsign.data.network

import com.google.gson.JsonParser
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WechatCodeClientTest {
    private lateinit var server: MockWebServer
    private val token = "a".repeat(43)
    private val code = "b".repeat(32)
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }
    private fun settings() = WechatCodeSettings(server.url("/").toString(), token, mode = "yyb", accountRef = "1")

    @Test fun fetchesOnlyCodeWithoutSendingSchoolCookies() {
        val jar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("SESSION")
                .value("school-secret").hostOnlyDomain(url.host).build())
        }
        val client = WechatCodeClient(OkHttpClient.Builder().cookieJar(jar).build())
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"result":{"code":"$code"}}}"""))
        assertEquals(code, client.fetchCode(settings()))
        val request = server.takeRequest()
        assertEquals("/wxapp/getCode", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("X-Checkin-Ticket"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(setOf("app_id", "ref"), body.keySet())
    }

    @Test fun rejectsWrongAppAndInvalidCodeWithoutEchoingResponse() {
        for (body in listOf("""{"appId":"wrong","code":"$code"}""",
            """{"appId":"${ApiConstants.WECHAT_APP_ID}","code":"secret-short"}""")) {
            server.enqueue(MockResponse().setBody(body))
            val error = runCatching { WechatCodeClient().fetchCode(settings()) }.exceptionOrNull()!!
            assertFalse(error.message!!.contains("secret-short"))
            assertFalse(error.message!!.contains(code))
        }
    }

    @Test fun rejectsRedirectsAndDoesNotForwardPairingToken() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect")))
        assertTrue(runCatching { WechatCodeClient().fetchCode(settings()) }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun remoteConnectionsRequireHttpsAndNeverEmbedSecretsInUrl() {
        for (url in listOf("http://example.com", "http://127.0.0.1.evil.test", "https://user:pass@example.com",
            "https://example.com?token=secret", "https://example.com/#secret")) {
            assertTrue(runCatching { WechatCodeSettings(url, token).validatedUrl() }.isFailure)
        }
        assertEquals("example.com", WechatCodeSettings("https://example.com", token).validatedUrl().host)
        assertTrue(runCatching { WechatCodeSettings(accessToken = token).validatedUrl() }.isFailure)
        assertEquals("127.0.0.1", WechatCodeSettings("http://127.0.0.1:18767", token).validatedUrl().host)
        assertFalse(settings().toString().contains(token))
    }

    @Test fun emptyOrMalformedPairingTokenDoesNotSendRequest() {
        for (value in listOf("", "invalid")) {
            assertTrue(runCatching { WechatCodeClient().fetchCode(settings().copy(accessToken = value)) }.isFailure)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun unknownErrorsAndOversizedResponsesAreNotDisplayed() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"secret-cookie"}"""))
        val error = runCatching { WechatCodeClient().fetchCode(settings()) }.exceptionOrNull()!!
        assertFalse(error.message!!.contains("secret-cookie"))
        server.enqueue(MockResponse().setBody("s".repeat(4097)))
        assertTrue(runCatching { WechatCodeClient().fetchCode(settings()) }.isFailure)
    }

    @Test fun yybProtocolUsesTargetAppAndAccountWithoutSchoolCookies() {
        val config = settings().copy(mode = "yyb", accountRef = "1")
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"openid":"fixture","result":{"code":"$code","errMsg":"login:ok"}}}"""))
        assertEquals(code, WechatCodeClient().fetchCode(config))
        val request = server.takeRequest()
        assertEquals("/wxapp/getCode", request.path)
        assertNull(request.getHeader("Cookie"))
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(ApiConstants.WECHAT_APP_ID, body.get("app_id").asString)
        assertEquals("1", body.get("ref").asString)
        assertEquals(setOf("ref", "app_id"), body.keySet())
    }

    @Test fun yybRejectsMissingAccountAndDoesNotExposeRemoteErrors() {
        assertTrue(runCatching { WechatCodeClient().fetchCode(settings().copy(mode = "yyb", accountRef = "")) }.isFailure)
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"secret-session"}"""))
        val error = runCatching { WechatCodeClient().fetchCode(settings().copy(mode = "yyb", accountRef = "1")) }.exceptionOrNull()!!
        assertFalse(error.message!!.contains("secret-session"))
        assertTrue(error.message!!.contains("重新扫码"))
    }
}
