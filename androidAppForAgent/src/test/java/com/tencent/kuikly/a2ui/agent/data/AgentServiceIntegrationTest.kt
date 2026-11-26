package com.tencent.kuikly.a2ui.agent.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class AgentServiceIntegrationTest {
    @Test fun createPatchReplaceAndSnapshotAgainstConfiguredService() = runBlocking {
        val base = System.getProperty("agent.test.baseUrl").orEmpty()
        val routes = System.getProperty("agent.test.routes").orEmpty()
        assumeTrue("Set AGENT_TEST_BASE_URL and local agent.routes to run service integration", base.isNotEmpty() && routes.isNotEmpty())
        val connection = AgentConnection(base, routes).also { it.validate(true) }
        val api = AgentApiClient(connection)
        val store = ChatStateStore()
        try {
            val session = api.createSession().getString("sessionId")
            store.snapshot(api.snapshot(session))
            val opened = CompletableDeferred<Unit>()
            val collector = launch {
                api.events(session, store.cursor) { opened.complete(Unit) }.collect { store.consume(it) }
            }
            try {
                withTimeout(10000) { opened.await() }
                suspend fun submit(body: JSONObject) {
                    val response = api.submit(session, body, UUID.randomUUID().toString())
                    store.confirmRun(response)
                    withTimeout(10000) {
                        while (store.messages.none { it.runId == response.getString("runId") && it.role == "assistant" && it.status == "completed" }) delay(20)
                    }
                }
                submit(JSONObject().put("operation", "create_card").put("prompt", "hello"))
                val initial = store.cards.values.single()
                assertEquals(1, initial.revision)
                submit(JSONObject().put("operation", "update_card").put("prompt", "hello again")
                    .put("target", JSONObject().put("cardId", initial.id)).put("baseCardRevision", 1).put("delivery", "patch"))
                assertEquals(initial.surfaceId, store.cards.getValue(initial.id).surfaceId)
                assertEquals(2, store.cards.getValue(initial.id).revision)
                submit(JSONObject().put("operation", "update_card").put("prompt", "welcome")
                    .put("target", JSONObject().put("cardId", initial.id)).put("baseCardRevision", 2).put("delivery", "replace"))
                assertNotEquals(initial.surfaceId, store.cards.getValue(initial.id).surfaceId)
                assertEquals(3, store.cards.getValue(initial.id).revision)
                assertEquals(6, store.messages.size)
                collector.cancelAndJoin()
                val reconnectKey = UUID.randomUUID().toString()
                val request = JSONObject().put("operation", "create_card").put("prompt", "hello after reconnect")
                val accepted = api.submit(session, request, reconnectKey)
                val repeated = api.submit(session, request, reconnectKey)
                assertEquals(accepted.getString("runId"), repeated.getString("runId"))
                store.confirmRun(accepted)
                val resumed = launch { api.events(session, store.cursor) {}.collect { store.consume(it) } }
                try {
                    withTimeout(10000) { while (store.messages.size < 8 || store.activeRunId != null) delay(20) }
                } finally { resumed.cancelAndJoin() }
                assertEquals(8, store.messages.size)
                assertEquals(2, store.cards.size)
                val restored = ChatStateStore().apply { snapshot(api.snapshot(session)) }
                assertEquals(store.cards.getValue(initial.id).replay, restored.cards.getValue(initial.id).replay)
                assertEquals(store.messages, restored.messages)
            } finally { collector.cancelAndJoin() }
        } finally { api.close() }
    }
}
