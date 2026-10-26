package edu.uestc.nightsign.data.wechat.nativeprotocol

import com.google.gson.JsonParser
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit opt-in only. Never signs in to school or submits attendance. */
class NativeLiveTest {
    @Test fun obtainFreshCodeWithoutPythonOrGo() {
        assumeTrue(System.getenv("NIGHT_SIGN_NATIVE_LIVE") == "1")
        val path = System.getenv("NIGHT_SIGN_NATIVE_CREDENTIALS") ?: error("PRIVATE_CONFIG_REQUIRED")
        val credentials = JsonParser.parseString(File(path).readText()).asJsonObject
        val cancelled = AtomicBoolean(false)
        val auth = NativeYybAuth(cancelled, { println("NATIVE_STAGE $it") }, {}, {})
        auth.renew(credentials)
        val buffer = auth.freshBuffer(credentials)
        val code = NativeMmtls(cancelled).getCode(buffer) { println("NATIVE_STAGE $it") }
        assertTrue(Regex("[A-Za-z0-9_-]{24,128}").matches(code))
        println("NATIVE_LIVE_OK codeLength=${code.length} checkinSubmitted=false")
    }
}
