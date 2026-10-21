package edu.uestc.nightsign.data.repository

import edu.uestc.nightsign.data.network.ApiConstants
import edu.uestc.nightsign.data.network.NightSignApi
import edu.uestc.nightsign.domain.SignSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.time.ZonedDateTime

class CheckinFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var flow: CheckinFlow
    private val settings = SignSettings("30.1", "103.1", "测试地址", 1320, true)
    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        flow = CheckinFlow(NightSignApi(OkHttpClient(), server.url("/").toString().trimEnd('/')),
            now = { ZonedDateTime.parse("2026-10-21T22:00:00+08:00") }, pause = {})
    }
    @After fun tearDown() { server.shutdown() }
    private fun respond(body: String) = server.enqueue(MockResponse().setBody(body))
    private fun status(value: String, date: String = "2026-10-21", abnormal: Boolean = false) =
        respond("""{"code":0,"data":{"businessDate":"$date","recordStatusCode":"$value","abnormalFlag":$abnormal}}""")
    private fun schedule(end: Int = 1410) = respond("""{"code":0,"data":{"effective":true,"studentCheckinStartOffsetMinute":1260,"studentCheckinEndOffsetMinute":$end}}""")

    @Test fun alreadyCheckedDoesNotPost() = runBlocking {
        status("CHECKED_IN")
        assertTrue(flow.run(settings, false)!!.checkedIn)
        assertEquals(1, server.requestCount)
    }
    @Test fun abnormalExistingResultIsNotSuccess() = runBlocking {
        status("CHECKED_IN", abnormal = true)
        assertTrue(runCatching { flow.run(settings, false) }.isFailure)
        assertEquals(1, server.requestCount)
    }
    @Test fun staleBusinessDateDoesNotPost() = runBlocking {
        status("PENDING", "2026-10-20")
        assertTrue(runCatching { flow.run(settings, false) }.isFailure)
        assertEquals(1, server.requestCount)
    }
    @Test fun outsideWindowDoesNotPost() = runBlocking {
        status("PENDING"); schedule(1319)
        assertTrue(runCatching { flow.run(settings, false) }.isFailure)
        assertEquals(2, server.requestCount)
    }
    @Test fun backgroundBeforeTargetDoesNotPost() = runBlocking {
        status("PENDING"); schedule()
        assertNull(flow.run(settings.copy(targetMinute = 1380), true))
        assertEquals(2, server.requestCount)
    }
    @Test fun confirmsSubmissionWithTodayQuery() = runBlocking {
        status("PENDING"); schedule(); respond("""{"code":0,"data":true}"""); status("CHECKED_IN")
        assertTrue(flow.run(settings, false)!!.checkedIn)
        val requests = (0 until server.requestCount).map { server.takeRequest() }
        assertEquals(listOf("GET", "GET", "POST", "GET"), requests.map { it.method })
        assertEquals(ApiConstants.CHECKIN, requests[2].path)
    }
    @Test fun pendingConfirmationNeverRepeatsPost() = runBlocking {
        status("PENDING"); schedule(); respond("""{"code":0,"data":true}""")
        repeat(3) { status("PENDING") }
        assertTrue(runCatching { flow.run(settings, false) }.isFailure)
        val requests = (0 until server.requestCount).map { server.takeRequest() }
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals(6, requests.size)
    }
    @Test fun unknownStatusDoesNotPost() = runBlocking {
        status("EXEMPTED"); schedule()
        assertTrue(runCatching { flow.run(settings, false) }.isFailure)
        assertEquals(2, server.requestCount)
    }
}
