package edu.uestc.nightsign.data.network

import org.junit.Assert.*
import org.junit.Test

class RootWechatCodeClientTest {
    private val nonce = "a".repeat(32)
    private val code = "b".repeat(32)

    private fun response(appId: String = ApiConstants.WECHAT_APP_ID, requestNonce: String = nonce) =
        """{"ok":true,"appId":"$appId","nonce":"$requestNonce","code":"$code"}"""

    @Test fun defaultsToRootButPreservesExistingBrokerConfiguration() {
        assertTrue(WechatCodeSettings().rootMode)
        assertFalse(WechatCodeSettings(accessToken = "a".repeat(43)).rootMode)
        assertTrue(WechatCodeSettings(mode = "root", accessToken = "a".repeat(43)).rootMode)
        assertFalse(WechatCodeSettings(mode = "broker").enabled)
    }

    @Test fun invokesOnlyFixedAdapterWithBoundAppAndNonce() {
        var command = ""
        val client = RootWechatCodeClient(RootCommandRunner {
            command = it
            RootCommandResult(0, response())
        }) { nonce }
        assertEquals(code, client.fetchCode())
        assertTrue(command.contains(RootWechatCodeClient.ADAPTER))
        assertTrue(command.contains("--app-id ${ApiConstants.WECHAT_APP_ID} --nonce $nonce"))
        assertFalse(command.contains(code))
        assertTrue(command.contains("id -u"))
    }

    @Test fun rejectsWrongIdentityAndStaleResponseWithoutLeakingCode() {
        for (body in listOf(response(appId = "wrong"), response(requestNonce = "c".repeat(32)))) {
            val client = RootWechatCodeClient(RootCommandRunner { RootCommandResult(0, body) }) { nonce }
            val error = runCatching { client.fetchCode() }.exceptionOrNull()!!
            assertFalse(error.message!!.contains(code))
        }
    }

    @Test fun missingAdapterAndDeniedRootHaveDistinctMessages() {
        for ((exit, text) in listOf(74 to "适配组件", 73 to "Root 授权")) {
            val client = RootWechatCodeClient(RootCommandRunner { RootCommandResult(exit, "secret") }) { nonce }
            val error = runCatching { client.fetchCode() }.exceptionOrNull()!!
            assertTrue(error.message!!.contains(text))
            assertFalse(error.message!!.contains("secret"))
        }
    }

    @Test fun adapterFailuresDoNotExposeUnknownResponseText() {
        for (body in listOf("not-json", "s".repeat(4097), """{"ok":false,"error":"secret-session"}""")) {
            val client = RootWechatCodeClient(RootCommandRunner { RootCommandResult(0, body) }) { nonce }
            val error = runCatching { client.fetchCode() }.exceptionOrNull()!!
            assertFalse(error.message!!.contains("secret-session"))
        }
    }

    @Test fun rejectsInvalidNonceBeforeLaunchingSu() {
        var invoked = false
        val client = RootWechatCodeClient(RootCommandRunner {
            invoked = true
            RootCommandResult(0, response())
        }) { "bad;command" }
        assertTrue(runCatching { client.fetchCode() }.isFailure)
        assertFalse(invoked)
    }

    @Test fun commandResultDoesNotRevealOutputInLogs() {
        assertFalse(RootCommandResult(0, code).toString().contains(code))
    }
}
