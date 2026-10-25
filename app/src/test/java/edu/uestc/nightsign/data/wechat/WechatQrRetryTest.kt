package edu.uestc.nightsign.data.wechat

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WechatQrRetryTest {
    @Test fun transportFailureDoesNotDiscardSession() = runBlocking {
        var attempts = 0
        val pauses = mutableListOf<Long>()
        val result = retryWechatQr(pause = { pauses += it }) {
            if (++attempts < 3) throw IOException("connection reset")
            "authorized"
        }
        assertEquals("authorized", result)
        assertEquals(listOf(2_000L, 4_000L), pauses)
    }
    @Test fun retriesTransientHttpAndConfirmNotReady() = runBlocking {
        for (code in listOf(409, 502, 503)) {
            var attempts = 0
            assertEquals("ok", retryWechatQr(pause = {}) {
                if (++attempts == 1) throw WechatQrRequestException(true, "HTTP $code")
                "ok"
            })
            assertEquals(2, attempts)
        }
    }
    @Test fun doesNotRetryExpiredOrUnauthorized() {
        for (code in listOf(401, 403, 404)) {
            var attempts = 0
            assertThrows(WechatQrRequestException::class.java) {
                runBlocking { retryWechatQr<Unit>(pause = {}) {
                    attempts++
                    throw WechatQrRequestException(false, "HTTP $code")
                } }
            }
            assertEquals(1, attempts)
        }
    }
    @Test fun respectsCancellation() {
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            runBlocking { retryWechatQr<Unit>(pause = {}) {
                attempts++
                throw CancellationException()
            } }
        }
        assertEquals(1, attempts)
    }
    @Test fun boundsAttemptsAndBackoff() {
        val pauses = mutableListOf<Long>()
        assertThrows(IOException::class.java) {
            runBlocking { retryWechatQr<Unit>(maxAttempts = 8, pause = { pauses += it }) {
                throw IOException()
            } }
        }
        assertEquals(7, pauses.size)
        assertEquals(10_000L, pauses.last())
    }
}
