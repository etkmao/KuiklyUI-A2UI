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
        assertEquals("basic-v1", client.getString("profileId"))
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
    private fun formCard(): CardDocument {
        val messages = javaClass.getResourceAsStream("/fixtures/action-form.json")!!.bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("messages").objects()
        }
        return CardDocument("clicked-card", messages.first().getJSONObject("createSurface").getString("surfaceId"),
            3, "ready", true, messages.map { it.toString() })
    }
    private fun syntheticRendererMessage(card: CardDocument, value: Any = " 本地最新输入 "): JSONObject = JSONObject()
        .put("version", "1.0").put("action", JSONObject().put("name", "submit_form").put("surfaceId", card.surfaceId)
            .put("sourceComponentId", "submit").put("timestamp", "2026-11-27T01:02:03.000Z")
            .put("context", JSONObject().put("value", value)))
    private fun rendererAction(card: CardDocument, message: JSONObject): RendererCardAction =
        RendererCardAction(card.id, card.revision, card.surfaceId, 99, message.toString(), null)

    @Test fun syntheticCompleteRendererEnvelopePreservesLiveValueNotReplayValue() {
        val card = formCard()
        val message = syntheticRendererMessage(card).apply { getJSONObject("action").put("userMessage", "提交表单") }
        val body = rendererAction(card, message).requestBody(card)
        assertEquals("clicked-card", body.getString("cardId"))
        assertEquals(3, body.getInt("baseCardRevision"))
        val outgoing = body.getJSONObject("message").getJSONObject("action")
        assertEquals(" 本地最新输入 ", outgoing.getJSONObject("context").getString("value"))
        assertEquals("submit", outgoing.getString("sourceComponentId"))
        assertEquals("提交表单", outgoing.getString("userMessage"))
        assertFalse(body.has("prompt")); assertFalse(body.has("operation")); assertFalse(outgoing.has("tag"))
    }
    @Test fun currentSdkRawCallbackCannotInventSurfaceOrSource() {
        val card = formCard()
        val raw = JSONObject().put("event", JSONObject().put("name", "submit_form")
            .put("context", JSONObject().put("value", "真实本地值")))
        val callback = RendererCardAction(card.id, card.revision, "dsl-surface", 99, raw.toString(), null)
        assertTrue(runCatching { callback.requestBody(card) }.isFailure)
        assertTrue(runCatching { callback.copy(surfaceId = card.surfaceId).requestBody(card) }.isFailure)
    }
    @Test fun actionRejectsMissingFieldsUnresolvedBindingsAndInvalidValues() {
        val card = formCard()
        listOf("sourceComponentId", "surfaceId", "context", "timestamp", "name").forEach { field ->
            val message = syntheticRendererMessage(card).apply { getJSONObject("action").remove(field) }
            assertTrue("Missing $field must fail closed", runCatching { rendererAction(card, message).requestBody(card) }.isFailure)
        }
        listOf<Any>("", " \n ", "x".repeat(1001), 42, JSONObject.NULL, JSONObject().put("@path", "/form/value")).forEach { value ->
            assertTrue(runCatching { rendererAction(card, syntheticRendererMessage(card, value)).requestBody(card) }.isFailure)
        }
        listOf("valueInput", "undeclared").forEach { source ->
            val message = syntheticRendererMessage(card).apply { getJSONObject("action").put("sourceComponentId", source) }
            assertTrue(runCatching { rendererAction(card, message).requestBody(card) }.isFailure)
        }
        val message = syntheticRendererMessage(card)
        assertTrue(runCatching { rendererAction(card, message).requestBody(card.copy(revision = 4)) }.isFailure)
        assertTrue(runCatching { rendererAction(card, message).requestBody(card.copy(id = "selected-not-clicked")) }.isFailure)
        assertTrue(runCatching { rendererAction(card, message).requestBody(card.copy(editable = false)) }.isFailure)
        assertTrue(runCatching { rendererAction(card, message).requestBody(card.copy(surfaceId = "replacement")) }.isFailure)
    }
    @Test fun actionRejectsWrongDeclaredBindingAndInvalidTimestamp() {
        val card = formCard()
        val altered = JSONObject(card.replay.single()).apply {
            getJSONObject("createSurface").getJSONArray("components").objects().first { it.getString("id") == "submit" }
                .getJSONObject("action").getJSONObject("event").getJSONObject("context").getJSONObject("value").put("@path", "/other")
        }
        val message = syntheticRendererMessage(card)
        assertTrue(runCatching { rendererAction(card, message).requestBody(card.copy(replay = listOf(altered.toString()))) }.isFailure)
        message.getJSONObject("action").put("timestamp", "2026-99-27T01:02:03.000Z")
        assertTrue(runCatching { rendererAction(card, message).requestBody(card) }.isFailure)
    }
    @Test fun actionRejectsExtraDeclaredAndResolvedContextFields() {
        val card = formCard()
        val message = syntheticRendererMessage(card)
        val extraResolved = JSONObject(message.toString()).apply {
            getJSONObject("action").getJSONObject("context").put("other", "unexpected")
        }
        assertTrue(runCatching { rendererAction(card, extraResolved).requestBody(card) }.isFailure)
        val extraDeclared = JSONObject(card.replay.single()).apply {
            getJSONObject("createSurface").getJSONArray("components").objects().first { it.getString("id") == "submit" }
                .getJSONObject("action").getJSONObject("event").getJSONObject("context").put("other", "unexpected")
        }
        assertTrue(runCatching { rendererAction(card, message).requestBody(card.copy(replay = listOf(extraDeclared.toString()))) }.isFailure)
    }

    @Test fun actionPostsToConfiguredPathAndRetriesReuseExactBodyAndKey() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(202)
            .setBody("{\"runId\":\"run\",\"status\":\"running\",\"operation\":\"update_card\"}")) }
        val card = formCard()
        val body = rendererAction(card, syntheticRendererMessage(card)).requestBody(card)
        repeat(2) { assertEquals("update_card", api.submitAction("session", body, "action-key").getString("operation")) }
        val first = server.takeRequest(3, TimeUnit.SECONDS)!!
        val second = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("POST", first.method); assertEquals("/test/actions/session", first.path)
        assertEquals("action-key", first.getHeader("Idempotency-Key"))
        assertEquals("test-only", first.getHeader("X-Demo-Header"))
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
    }
    @Test fun legacyRoutesUseStandardActionsPath() = runBlocking {
        val legacy = JSONObject(routes()).apply { remove("actions") }.toString()
        val connection = AgentConnection(server.url("/").toString(), legacy)
        connection.validate(true)
        val legacyApi = AgentApiClient(connection)
        try {
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"operation\":\"update_card\"}"))
            legacyApi.submitAction("session", JSONObject(), "key")
            assertEquals("/v1/sessions/session/actions", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
        } finally { legacyApi.close() }
    }
    @Test fun actionRevisionConflictRemainsRecoverableWithoutModelRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":{\"code\":\"REVISION_CONFLICT\",\"retryable\":false}}"))
        try { api.submitAction("session", JSONObject(), "key"); fail("Expected conflict") }
        catch (e: AgentFailure) { assertEquals("REVISION_CONFLICT", e.code); assertEquals(409, e.httpStatus) }
        assertEquals("/test/actions/session", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
        assertEquals(1, server.requestCount)
    }

    @Test fun submissionUncertaintyIncludes408ButNotDefinitiveClientRejections() {
        listOf(0, 408, 500, 502, 503).forEach { status ->
            assertTrue("Status $status must retain the pending submission", AgentFailure("TEST", false, status).submissionUncertain)
        }
        listOf(400, 401, 403, 404, 409, 422, 429).forEach { status ->
            assertFalse("Status $status is a definitive rejection", AgentFailure("TEST", true, status).submissionUncertain)
        }
    }
    @Test fun action408KeepsUncertainResultAndRetryUsesOriginalKeyAndBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(408).setBody("{\"error\":{\"code\":\"REQUEST_TIMEOUT\",\"retryable\":false}}"))
        server.enqueue(MockResponse().setResponseCode(202)
            .setBody("{\"runId\":\"run\",\"status\":\"running\",\"operation\":\"update_card\"}"))
        val card = formCard()
        val body = rendererAction(card, syntheticRendererMessage(card)).requestBody(card)
        try { api.submitAction("session", body, "original-action-key"); fail("Expected uncertain timeout") }
        catch (e: AgentFailure) { assertEquals(408, e.httpStatus); assertTrue(e.submissionUncertain) }
        assertEquals("update_card", api.submitAction("session", body, "original-action-key").getString("operation"))
        val first = server.takeRequest(3, TimeUnit.SECONDS)!!
        val second = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/test/actions/session", first.path); assertEquals(first.path, second.path)
        assertEquals("original-action-key", first.getHeader("Idempotency-Key"))
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
    }

    @Test fun actionRequires202UpdateCardResponseAndNonemptyKey() = runBlocking {
        assertTrue(runCatching { api.submitAction("session", JSONObject(), "") }.isFailure)
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"operation\":\"update_card\"}"))
        try { api.submitAction("session", JSONObject(), "key"); fail("Expected invalid status") }
        catch (e: AgentFailure) { assertEquals("INVALID_RESPONSE", e.code); assertEquals(200, e.httpStatus) }
        server.enqueue(MockResponse().setResponseCode(202).setBody("{\"operation\":\"create_card\"}"))
        try { api.submitAction("session", JSONObject(), "key"); fail("Expected invalid operation") }
        catch (e: AgentFailure) { assertEquals("INVALID_RESPONSE", e.code); assertEquals(202, e.httpStatus) }
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

    private fun syntheticRpc(direction: String = "renderer_to_agent", status: String = "pending"): JSONObject {
        val requestKey = if (direction == "renderer_to_agent") "callAgentFunction" else "callRendererFunction"
        val responseKey = if (direction == "renderer_to_agent") "agentFunctionResponse" else "rendererFunctionResponse"
        return JSONObject().put("sessionId", "session").put("rendererId", "renderer").put("cardId", "card")
            .put("surfaceId", "surface").put("baseCardRevision", 1).put("functionCallId", "rpc-id")
            .put("direction", direction).put("status", status).put("createdAt", "2026-11-27T01:00:00.000Z")
            .put("deadline", "2026-11-27T01:00:10.000Z").put("request", JSONObject().put("version", "1.0")
                .put(requestKey, JSONObject().put("surfaceId", "surface").put("functionCallId", "rpc-id")
                    .put("callFunction", JSONObject().put("@call", "getGreeting").put("catalogId", RpcCall.CATALOG_ID)
                        .put("args", JSONObject().put("name", "World")))))
            .apply { if (status != "pending") put("response", JSONObject().put("version", "1.0").put(responseKey,
                JSONObject().put("functionCallId", "rpc-id").put("value", "Hello, World!"))) }
    }

    @Test fun rpcPostsCompleteEnvelopeAnd408RetryPreservesExactOriginalBodyAndKey() = runBlocking {
        val call = syntheticRpc()
        val body = RendererBinding("renderer", "session", "card", "surface", 1).requestBody()
            .put("message", call.getJSONObject("request"))
        server.enqueue(MockResponse().setResponseCode(408).setBody("{\"error\":{\"code\":\"TIMEOUT\",\"retryable\":false}}"))
        server.enqueue(MockResponse().setResponseCode(202).setBody(call.toString()))
        val failure = runCatching { api.submitRpc("session", body, "original-rpc-key") }.exceptionOrNull() as AgentFailure
        assertTrue(failure.submissionUncertain); assertTrue(failure.retryable)
        assertEquals("rpc-id", api.submitRpc("session", body, "original-rpc-key").functionCallId)
        val first = server.takeRequest(3, TimeUnit.SECONDS)!!; val retry = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/test/rpc/session", first.path); assertEquals("POST", first.method)
        assertEquals(first.body.readUtf8(), retry.body.readUtf8())
        assertEquals("original-rpc-key", retry.getHeader("Idempotency-Key"))
        assertEquals("test-only", retry.getHeader("X-Demo-Header"))
    }

    @Test fun rpcRendererResponseQueryCancelAndRegistrationUseIndependentRoutes() = runBlocking {
        val binding = RendererBinding("renderer", "session", "card", "surface", 1)
        server.enqueue(MockResponse().setResponseCode(201).setBody(binding.requestBody().put("surfaceId", "surface").put("active", true).toString()))
        api.registerRenderer("session", binding)
        assertEquals("/test/renderers/session", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
        val call = syntheticRpc("agent_to_renderer")
        server.enqueue(MockResponse().setResponseCode(202).setBody(call.toString()))
        api.submitRpc("session", binding.requestBody().put("callFunction", call.getJSONObject("request")
            .getJSONObject("callRendererFunction").getJSONObject("callFunction")), "renderer-key", true)
        assertEquals("/test/rpcRenderer/session", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
        val done = syntheticRpc("agent_to_renderer", "completed")
        server.enqueue(MockResponse().setResponseCode(200).setBody(done.toString()))
        assertEquals("completed", api.respondRpc("session", JSONObject().put("rendererId", "renderer")
            .put("message", done.getJSONObject("response"))).status)
        assertEquals("/test/rpcResponses/session", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
        server.enqueue(MockResponse().setResponseCode(200).setBody(done.toString()))
        api.rpcCall("session", "rpc-id", "renderer")
        assertEquals("/test/rpcCall/session?rendererId=renderer", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
        server.enqueue(MockResponse().setResponseCode(200).setBody(syntheticRpc("agent_to_renderer", "cancelled").toString()))
        assertEquals("cancelled", api.cancelRpc("session", "rpc-id", "renderer").status)
        val cancel = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/test/rpcCancel/session", cancel.path)
        assertEquals("renderer", JSONObject(cancel.body.readUtf8()).getString("rendererId"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"rendererId\":\"renderer\",\"active\":false}"))
        api.releaseRenderer("session", "renderer")
        assertEquals("DELETE", server.takeRequest(3, TimeUnit.SECONDS)!!.method)
    }

    @Test fun legacyConnectionGetsPublicRpcDefaultsAndSessionAdvertisesBothCatalogs() = runBlocking {
        val legacy = JSONObject(routes()).apply { AgentConnection.DEFAULT_ROUTES.keys.filter { it != "actions" }.forEach { remove(it) } }
        val connection = AgentConnection(server.url("/").toString(), legacy.toString())
        connection.validate(true)
        assertEquals(server.url("/v1/sessions/session/rpc/rpc-id").toString(), connection.url("rpcCall",
            "sessionId" to "session", "functionCallId" to "rpc-id"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("{\"sessionId\":\"session\"}"))
        api.createSession()
        val supported = JSONObject(server.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONObject("client")
            .getJSONObject("a2uiRendererCapabilities").getJSONObject("1.0").getJSONArray("supportedCatalogIds")
        assertEquals(2, supported.length()); assertEquals(RpcCall.CATALOG_ID, supported.getString(1))
    }
}
