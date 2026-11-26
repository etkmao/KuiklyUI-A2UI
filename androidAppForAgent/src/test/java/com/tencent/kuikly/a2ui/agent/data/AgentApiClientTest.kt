package com.tencent.kuikly.a2ui.agent.data

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AgentApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AgentApiClient
    private fun routes(): String = JSONObject().apply {
        AgentConnection.ROUTE_KEYS.forEach { put(it, "/test/$it/{sessionId}") }
        put("sessions", "/test/sessions"); put("cancel", "/test/cancel/{runId}")
    }.toString()
    @Before fun setUp() {
        server = MockWebServer(); server.start()
        api = AgentApiClient(AgentConnection(server.url("/").toString(), routes(), "X-Demo-Header", "test-only"))
    }
    @After fun tearDown() { api.close(); server.shutdown() }

    @Test fun sessionRequestDeclaresNativeV10Capabilities() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{\"sessionId\":\"session\"}"))
        assertEquals("session", api.createSession().getString("sessionId"))
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        val client = JSONObject(request.body.readUtf8()).getJSONObject("client")
        assertEquals("android", client.getString("platform"))
        assertEquals("basic-text-v1", client.getString("profileId"))
        assertEquals("test-only", request.getHeader("X-Demo-Header"))
    }
    @Test fun submissionRetriesReuseSameIdempotencyKey() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(202).setBody("{\"runId\":\"run\"}")) }
        val body = JSONObject().put("operation", "create_card").put("prompt", "hello")
        repeat(2) { api.submit("session", body, "same-key") }
        val first = server.takeRequest(3, TimeUnit.SECONDS)!!
        val second = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("same-key", first.getHeader("Idempotency-Key"))
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
    }
    @Test fun cancellationPostsToConfiguredRunPath() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"status\":\"cancelled\"}"))
        assertEquals("cancelled", api.cancel("run").getString("status"))
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/test/cancel/run", request.path)
    }
    @Test fun eventSourceUsesCursorAndIgnoresHeartbeat() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(": heartbeat\n\nid: 8\nevent: run.started\ndata: {\"eventId\":8,\"type\":\"run.started\"}\n\n"))
        val events = withTimeout(5000) { api.events("session", 7) {}.take(1).toList() }
        assertEquals(8, events.single().getInt("eventId"))
        assertEquals("7", server.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Last-Event-ID"))
    }
    @Test fun expiredCursorExposesRecoverableCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":{\"code\":\"EVENT_CURSOR_EXPIRED\",\"retryable\":false}}"))
        try {
            withTimeout(5000) { api.events("session", 0) {}.toList() }
            fail("Expected cursor failure")
        } catch (e: AgentFailure) { assertEquals("EVENT_CURSOR_EXPIRED", e.code); assertEquals(409, e.httpStatus) }
    }
    @Test fun invalidEventIdentityRequiresResync() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("id: 8\nevent: run.started\ndata: {\"eventId\":9,\"type\":\"run.started\"}\n\n"))
        try { withTimeout(5000) { api.events("session", 0) {}.toList() }; fail("Expected sync failure") }
        catch (_: SyncRequired) { }
    }
    @Test fun validationRejectsCredentialsInUrl() {
        assertTrue(runCatching { AgentConnection("https://user:secret@example.com", routes()).validate(false) }.isFailure)
    }
    @Test fun releaseConnectionRejectsHttp() {
        assertTrue(runCatching { AgentConnection("http://example.com", routes()).validate(false) }.isFailure)
    }
    @Test fun validationRejectsHeaderInjection() {
        assertTrue(runCatching { AgentConnection("https://example.com", routes(), "X-Test", "value\r\nOther: x").validate(false) }.isFailure)
    }
}
