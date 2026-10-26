package edu.uestc.nightsign.data.wechat

import org.junit.Assert.*
import org.junit.Test

class WechatCodeRouterTest {
    @Test fun localDoesNotContactServer() {
        assertEquals("local-code", fetchCodeForMode("local", { "local-code" }, { error("unexpected server call") }))
    }
    @Test fun serverDoesNotUseLocalCredentials() {
        assertEquals("server-code", fetchCodeForMode("server", { error("unexpected local call") }, { "server-code" }))
    }
    @Test fun failureDoesNotFallBackToOtherMode() {
        for (mode in listOf("local", "server")) {
            var calls = 0
            val outcome = runCatching { fetchCodeForMode(mode, { calls++; error("local failure") }, { calls++; error("server failure") }) }
            assertTrue(outcome.isFailure)
            assertEquals(1, calls)
        }
    }
    @Test fun invalidModeDoesNotFetchCode() {
        assertTrue(runCatching { fetchCodeForMode("unknown", { error("local") }, { error("server") }) }.isFailure)
    }
}
