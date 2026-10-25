package edu.uestc.nightsign.data.wechat

import edu.uestc.nightsign.data.network.WechatCodeSettings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class WechatQrClientTest {
    @Test fun supportsPrivateHttpsGatewayBeforeAccountExists() {
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https", chain.request().url.scheme)
            assertEquals("private.example.org", chain.request().url.host)
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(okhttp3.ResponseBody.create(null,
                    """{"code":0,"data":{"session_id":"remote-id","image_base64":"data:image/jpeg;base64,YQ=="}}""")).build()
        }.build()
        assertEquals("remote-id", WechatQrClient(client).create(
            WechatCodeSettings("https://private.example.org", "a".repeat(43), accountRef = "")).id)
    }
    @Test fun completeQrFlow() {
        MockWebServer().use { server ->
            server.start()
            val config = WechatCodeSettings(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "a".repeat(43))
            val client = WechatQrClient()
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"session_id":"test-id","image_base64":"data:image/jpeg;base64,YQ=="}}"""))
            assertEquals("test-id", client.create(config).id)
            val create = server.takeRequest()
            assertEquals("/wx/qrcodeauth?as_base64=true", create.path)
            assertEquals("POST", create.method)
            assertEquals("Bearer ${config.accessToken}", create.getHeader("Authorization"))
            assertNull(create.getHeader("Cookie"))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"status":"authorized"}}"""))
            assertEquals("authorized", client.poll(config, "test-id"))
            assertEquals("/wx/qrcodeauth/test-id/poll", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":42}}"""))
            assertEquals("42", client.confirm(config, "test-id"))
            assertEquals("/wx/qrcodeauth/test-id/confirm", server.takeRequest().path)
        }
    }
    @Test fun rejectsInsecureRemoteAndPathInjection() {
        val client = WechatQrClient()
        assertThrows(IllegalArgumentException::class.java) { client.create(WechatCodeSettings("http://example.com", "a".repeat(43))) }
        assertThrows(IllegalArgumentException::class.java) { client.poll(WechatCodeSettings(), "../accounts") }
    }
    @Test fun rejectsExpiredAndInvalidEnvelope() {
        MockWebServer().use { server ->
            server.start()
            val config = WechatCodeSettings(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "a".repeat(43))
            server.enqueue(MockResponse().setResponseCode(404))
            assertThrows(java.io.IOException::class.java) { WechatQrClient().poll(config, "a") }
            server.enqueue(MockResponse().setBody("{}"))
            assertThrows(java.io.IOException::class.java) { WechatQrClient().poll(config, "a") }
        }
    }
    @Test fun retriesGatewayFailureUsingTheSameQr() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val config = WechatCodeSettings(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "a".repeat(43))
            server.enqueue(MockResponse().setResponseCode(502).setBody("upstream connection reset"))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"status":"authorized"}}"""))
            val reasons = mutableListOf<String>()
            val status = retryWechatQr(pause = {}, onRetry = { reasons += it }) {
                WechatQrClient().poll(config, "same-qr")
            }
            assertEquals("authorized", status)
            assertTrue(reasons.single().contains("HTTP 502"))
            repeat(2) { assertEquals("/wx/qrcodeauth/same-qr/poll", server.takeRequest().path) }
        }
    }
}
