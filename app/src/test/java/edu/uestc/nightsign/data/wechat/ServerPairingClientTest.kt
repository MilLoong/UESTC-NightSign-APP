package edu.uestc.nightsign.data.wechat

import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ServerPairingClientTest {
    private val code = "c".repeat(43)
    private fun payload(server: String = "https://private.example.org", version: Int = 1) = JsonObject().apply {
        addProperty("kind", "uestc-nightsign-pair"); addProperty("version", version)
        addProperty("server", server); addProperty("code", code)
    }.toString()

    @Test fun parsesHttpsOfferWithoutExposingCode() {
        val offer = ServerPairingOffer.parse(payload())
        assertEquals("https://private.example.org", offer.endpoint)
        assertFalse(offer.toString().contains(code))
    }
    @Test fun rejectsOtherQrAndCredentialBearingOrInsecureUrls() {
        for (raw in listOf("not json", payload(version = 2), payload("http://private.example.org"),
            payload("https://user:secret@private.example.org"), payload("https://private.example.org/a"),
            payload("https://private.example.org?code=secret"), payload("https://private.example.org#secret"))) {
            assertThrows(IllegalArgumentException::class.java) { ServerPairingOffer.parse(raw) }
        }
    }
    @Test fun exchangesOneTimeCodeWithoutSendingSchoolOrAdminCredentials() {
        val token = "d".repeat(43)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/v1/pair", chain.request().url.encodedPath)
            assertNull(chain.request().header("Cookie"))
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{"token":"$token","device_id":"device-1"}}""".toResponseBody()).build()
        }.build()
        val settings = ServerPairingClient(http).pair(ServerPairingOffer.parse(payload()))
        assertEquals(token, settings.accessToken)
        assertEquals("", settings.accountRef)
    }
    @Test fun expiredResponseDoesNotEchoSecrets() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Expired")
                .body("secret upstream data".toResponseBody()).build()
        }.build()
        val error = assertThrows(java.io.IOException::class.java) {
            ServerPairingClient(http).pair(ServerPairingOffer.parse(payload()))
        }
        assertFalse(error.message!!.contains("secret"))
    }
}
