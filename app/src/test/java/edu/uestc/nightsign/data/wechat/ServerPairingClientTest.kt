package edu.uestc.nightsign.data.wechat

import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ServerPairingClientTest {
    @Test fun sendsInstallationKeyAndMigrationProofButNeverThePreviousBearer() {
        val oldToken = "t".repeat(43)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            val raw = buffer.readUtf8()
            val body = com.google.gson.JsonParser.parseString(raw).asJsonObject
            assertEquals("k".repeat(43), body.get("installation_key").asString)
            assertEquals("mBaGOTEQDoUa0xTzmmNlIwA_0d-meqLKva6Y208DL-U", body.get("previous_proof").asString)
            assertFalse(raw.contains(oldToken))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{"token":"${"d".repeat(43)}","device_id":"existing-device","ref":"42"}}""".toResponseBody()).build()
        }.build()
        val settings = ServerPairingClient(http).pair(ServerPairingOffer("https://new.example.org", "c".repeat(43), "s".repeat(43)),
            "k".repeat(43), edu.uestc.nightsign.data.network.WechatCodeSettings("https://old.example.org", oldToken))
        assertTrue(settings.enabled)
        assertEquals("42", settings.accountRef)
    }
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
    @Test fun readsServerAuthorizationWithoutRequestingAQr() {
        val token = "d".repeat(43)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("GET", chain.request().method)
            assertEquals("/v1/device", chain.request().url.encodedPath)
            assertEquals("Bearer $token", chain.request().header("Authorization"))
            assertNull(chain.request().header("Cookie"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{"authorized":true,"ref":"42","status":"confirmed"}}""".toResponseBody()).build()
        }.build()
        val status = ServerPairingClient(http).status(edu.uestc.nightsign.data.network.WechatCodeSettings("https://private.example.org", token))
        assertTrue(status.authorized)
        assertEquals("42", status.ref)
    }
    @Test fun acceptsPendingStatusWithNoAccount() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{"authorized":false,"ref":null,"status":"pending"}}""".toResponseBody()).build()
        }.build()
        val status = ServerPairingClient(http).status(edu.uestc.nightsign.data.network.WechatCodeSettings("https://private.example.org", "d".repeat(43)))
        assertFalse(status.authorized)
        assertNull(status.ref)
    }
    @Test fun rejectsAnAuthorizedStatusWithoutAnAccount() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{"authorized":true,"ref":null,"status":"confirmed"}}""".toResponseBody()).build()
        }.build()
        assertThrows(java.io.IOException::class.java) {
            ServerPairingClient(http).status(edu.uestc.nightsign.data.network.WechatCodeSettings("https://private.example.org", "d".repeat(43)))
        }
    }
}
