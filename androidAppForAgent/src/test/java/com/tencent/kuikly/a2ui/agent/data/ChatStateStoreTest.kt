package com.tencent.kuikly.a2ui.agent.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatStateStoreTest {
    private lateinit var store: ChatStateStore
    private fun snapshot() = JSONObject().put("sessionId", "session").put("lastEventId", 0)
        .put("activeRunId", JSONObject.NULL).put("messages", JSONArray()).put("cards", JSONArray()).put("recentCardIds", JSONArray())
    private fun envelope(surfaceId: String = "surface", text: String = "hello") = JSONObject()
        .put("version", "1.0").put("createSurface", JSONObject().put("surfaceId", surfaceId)
            .put("catalogId", "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json")
            .put("components", JSONArray().put(JSONObject().put("id", "root").put("component", "Text").put("text", text).put("variant", "body"))))
    private fun event(id: Int, type: String, payload: JSONObject = JSONObject(), base: Int = 0, revision: Int = 0, surface: String = "surface") = JSONObject()
        .put("sessionId", "session").put("eventId", id).put("type", type).put("runId", "run")
        .put("cardId", "card").put("surfaceId", surface).put("baseCardRevision", base).put("cardRevision", revision).put("payload", payload)
    private fun message(id: String = "message", ordinal: Int = 1, status: String = "completed") = JSONObject()
        .put("messageId", id).put("ordinal", ordinal).put("role", "assistant").put("status", status).put("runId", "run")
        .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", "response"))).put("createdAt", "2026-01-01T00:00:00.000Z")
    private fun createCard() { store.consume(event(1, "a2ui.message", envelope(), revision = 1)) }

    @Before fun setUp() { store = ChatStateStore(); store.snapshot(snapshot()) }

    @Test fun duplicateEventIsIgnored() {
        assertTrue(store.consume(event(1, "run.started")))
        assertFalse(store.consume(event(1, "run.started")))
        assertEquals(1L, store.cursor)
    }
    @Test(expected = SyncRequired::class) fun gapRequiresSnapshot() { store.consume(event(2, "run.started")) }
    @Test(expected = SyncRequired::class) fun wrongSessionRequiresSnapshot() {
        store.consume(event(1, "run.started").put("sessionId", "other"))
    }
    @Test fun messageUpdatesDoNotDuplicate() {
        store.consume(event(1, "message.created", message(status = "running")))
        store.consume(event(2, "message.updated", message()))
        assertEquals(1, store.messages.size)
        assertEquals("completed", store.messages.single().status)
    }
    @Test fun patchBuildsCompactReplayAndIncrementsRevision() {
        createCard()
        val patch = JSONObject().put("version", "1.0").put("updateComponents", JSONObject().put("surfaceId", "surface")
            .put("components", envelope(text = "updated").getJSONObject("createSurface").getJSONArray("components")))
        store.consume(event(2, "a2ui.message", patch, 1, 2))
        val card = store.cards.getValue("card")
        assertEquals(2, card.revision)
        assertEquals(1, card.replay.size)
        assertEquals("updated", JSONObject(card.replay.single()).getJSONObject("createSurface").getJSONArray("components").getJSONObject(0).getString("text"))
    }
    @Test fun replaceChangesSurfaceAndClearsOldReplay() {
        createCard()
        store.consume(event(2, "card.replaced", envelope("new-surface", "replacement"), 1, 2, "new-surface"))
        assertEquals("new-surface", store.cards.getValue("card").surfaceId)
        assertEquals(1, store.cards.getValue("card").replay.size)
    }
    @Test(expected = SyncRequired::class) fun wrongRevisionRequiresSnapshot() {
        createCard(); store.consume(event(2, "a2ui.message", envelope(), 0, 2))
    }
    @Test(expected = SyncRequired::class) fun missingProtocolVersionRequiresSnapshot() {
        store.consume(event(1, "a2ui.message", envelope().apply { remove("version") }, revision = 1))
    }
    @Test fun failurePreservesCommittedCard() {
        createCard(); store.consume(event(2, "run.failed"))
        assertEquals("ready", store.cards.getValue("card").status)
        assertEquals(1, store.cards.getValue("card").revision)
    }
    @Test fun lateRunResponseDoesNotResurrectCompletedRun() {
        store.consume(event(1, "run.started"))
        store.consume(event(2, "run.completed"))
        store.confirmRun(JSONObject().put("runId", "run").put("status", "running"))
        assertNull(store.activeRunId)
    }
    @Test fun staleHistoryDoesNotOverwriteLiveMessage() {
        store.consume(event(1, "message.updated", message()))
        store.mergeHistory(JSONObject().put("sessionId", "session").put("messages", JSONArray().put(message(status = "running")))
            .put("nextBeforeOrdinal", JSONObject.NULL))
        assertEquals("completed", store.messages.single().status)
    }
    @Test fun snapshotShowsLastThirtyAndAllowsHistory() {
        val all = JSONArray(); (1..40).forEach { all.put(message("message-$it", it)) }
        store.snapshot(snapshot().put("messages", all).put("lastEventId", 100))
        assertEquals(30, store.messages.size)
        assertEquals(11, store.messages.first().ordinal)
        assertTrue(store.canLoadHistory)
        assertEquals(100L, store.cursor)
    }
    @Test fun onlyTenRecentCardsAreEditable() {
        (1..11).forEach { i -> store.consume(event(i, "a2ui.message", envelope(), revision = 1).put("cardId", "card-$i")) }
        assertFalse(store.cards.getValue("card-1").editable)
        assertTrue(store.cards.getValue("card-11").editable)
    }
    @Test fun updatingOldCardDoesNotChangeCreationRecency() {
        (1..10).forEach { i -> store.consume(event(i, "a2ui.message", envelope(), revision = 1).put("cardId", "card-$i")) }
        store.consume(event(11, "card.replaced", envelope("replacement"), 1, 2, "replacement").put("cardId", "card-1"))
        store.consume(event(12, "a2ui.message", envelope(), revision = 1).put("cardId", "card-11"))
        assertFalse(store.cards.getValue("card-1").editable)
        assertTrue(store.cards.getValue("card-2").editable)
    }
    @Test fun cancelledRunClearsActiveStateAndPreservesOldCard() {
        createCard()
        store.consume(event(2, "run.started"))
        store.consume(event(3, "run.cancelled"))
        assertNull(store.activeRunId)
        assertEquals("ready", store.cards.getValue("card").status)
    }
}
