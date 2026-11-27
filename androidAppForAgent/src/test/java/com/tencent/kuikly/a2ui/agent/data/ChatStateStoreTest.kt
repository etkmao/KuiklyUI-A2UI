package com.tencent.kuikly.a2ui.agent.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatStateStoreTest {
    private lateinit var store: ChatStateStore
    private fun snapshot() = JSONObject().put("sessionId", "session").put("lastEventId", 0)
        .put("capabilities", JSONObject().put("id", "basic-v1").put("protocolVersion", "1.0"))
        .put("activeRunId", JSONObject.NULL).put("messages", JSONArray()).put("cards", JSONArray()).put("recentCardIds", JSONArray())
    private fun fixture(name: String): List<JSONObject> = javaClass.getResourceAsStream("/fixtures/$name.json")
        ?.bufferedReader()?.use { JSONObject(it.readText()).getJSONArray("messages").objects() } ?: error("Missing harness fixture: $name")
    private fun envelope(surfaceId: String = "surface", text: String = "hello") = fixture("basic-text").first().apply {
        getJSONObject("createSurface").put("surfaceId", surfaceId).getJSONArray("components").getJSONObject(0).put("text", text)
    }
    private fun event(id: Int, type: String, payload: JSONObject = JSONObject(), base: Int = 0, revision: Int = 0, surface: String = "surface") = JSONObject()
        .put("sessionId", "session").put("eventId", id).put("type", type).put("runId", "run")
        .put("cardId", "card").put("surfaceId", surface).put("baseCardRevision", base).put("cardRevision", revision).put("payload", payload).put("messageId", "message").put("messageIndex", 0).put("messageCount", 1)
    private fun message(id: String = "message", ordinal: Int = 1, status: String = "completed") = JSONObject()
        .put("messageId", id).put("sessionId", "session").put("ordinal", ordinal).put("role", "assistant").put("status", status).put("runId", "run")
        .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", "response"))).put("createdAt", "2026-01-01T00:00:00.000Z")
    private fun createCard() { store.consume(event(1, "a2ui.message", envelope(), revision = 1)) }
    private fun deletion(surface: String) = fixture("basic-text-stream").last().apply { getJSONObject("deleteSurface").put("surfaceId", surface) }
    private fun populateCards(count: Int) {
        (1..count).forEach { i ->
            val cardId = "card-$i"
            val placeholder = message("message-$i", i, "running").put("parts", JSONArray().put(JSONObject().put("type", "card").put("cardId", cardId)))
            store.consume(event(i * 2 - 1, "message.created", placeholder).put("cardId", cardId))
            store.consume(event(i * 2, "a2ui.message", envelope(), revision = 1).put("cardId", cardId))
        }
    }

    @Before fun setUp() {
        store = ChatStateStore()
        val pending = JSONObject().put("cardId", "card").put("sessionId", "session").put("surfaceId", "surface")
            .put("revision", 0).put("status", "pending").put("editable", false).put("replayMessages", JSONArray())
        store.snapshot(snapshot().put("cards", JSONArray().put(pending)))
    }

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
    @Test fun actionRunDataModelCommitIsAtomicAndRestoresCanonicalFormAndResult() {
        val form = fixture("action-form").single().apply { getJSONObject("createSurface").put("surfaceId", "surface") }
        store.consume(event(1, "a2ui.message", form, revision = 1))
        store.confirmRun(JSONObject().put("runId", "run").put("status", "running").put("operation", "update_card"))
        val before = store.cards.getValue("card")
        fun update(path: String, value: String) = JSONObject().put("version", "1.0").put("updateDataModel",
            JSONObject().put("surfaceId", "surface").put("path", path).put("value", value))
        store.consume(event(2, "a2ui.message", update("/form/value", "最新输入"), 1, 2).put("messageCount", 2))
        assertEquals(before, store.cards.getValue("card"))
        assertTrue(store.hasPendingCommit)
        store.consume(event(3, "a2ui.message", update("/result/message", "已提交：最新输入"), 1, 2)
            .put("messageCount", 2).put("messageIndex", 1))
        val committed = store.cards.getValue("card")
        assertEquals(2, committed.revision); assertEquals(2, committed.incrementalEnvelopes.size)
        assertEquals(before.surfaceId, committed.surfaceId)
        assertFalse(store.hasPendingCommit)
        val model = UiStateDocument.restore(committed.replay).getJSONObject("surfaces").getJSONObject("surface").getJSONObject("dataModel")
        assertEquals("最新输入", model.getJSONObject("form").getString("value"))
        assertEquals("已提交：最新输入", model.getJSONObject("result").getString("message"))
        val replay = JSONObject(committed.replay.single()).getJSONObject("createSurface")
        assertEquals(5, replay.getJSONArray("components").length())
        store.consume(event(4, "run.completed"))
        store.confirmRun(JSONObject().put("runId", "run").put("status", "running").put("operation", "update_card"))
        assertNull(store.activeRunId)
        assertFalse(store.consume(event(3, "a2ui.message")))
        assertEquals(committed, store.cards.getValue("card"))
        assertTrue(runCatching { store.consume(event(5, "a2ui.message", update("/form/value", "stale"), 1, 2)) }.isFailure)
        assertEquals(committed, store.cards.getValue("card"))
    }

    @Test fun replaceChangesSurfaceAndClearsOldReplay() {
        createCard()
        val before = store.cards.getValue("card")
        store.consume(event(2, "card.replaced", deletion("surface"), 1, 2, "new-surface").put("messageCount", 2))
        assertEquals(before, store.cards.getValue("card"))
        assertTrue(store.hasPendingCommit)
        store.consume(event(3, "a2ui.message", envelope("new-surface", "replacement"), 1, 2, "new-surface").put("messageIndex", 1).put("messageCount", 2))
        val card = store.cards.getValue("card")
        assertEquals("new-surface", card.surfaceId)
        assertEquals(2, card.revision)
        assertEquals(1, card.replay.size)
        assertFalse(UiStateDocument.restore(card.replay).getJSONObject("surfaces").has("surface"))
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
        populateCards(11)
        assertFalse(store.cards.getValue("card-1").editable)
        assertTrue(store.cards.getValue("card-11").editable)
    }
    @Test fun updatingOldCardDoesNotChangeCreationRecency() {
        populateCards(10)
        store.consume(event(21, "card.replaced", deletion("surface"), 1, 2, "replacement").put("cardId", "card-1").put("messageCount", 2))
        store.consume(event(22, "a2ui.message", envelope("replacement"), 1, 2, "replacement").put("cardId", "card-1").put("messageIndex", 1).put("messageCount", 2))
        val placeholder = message("new-message", 11, "running").put("parts", JSONArray().put(JSONObject().put("type", "card").put("cardId", "card-11")))
        store.consume(event(23, "message.created", placeholder).put("cardId", "card-11"))
        store.consume(event(24, "a2ui.message", envelope(), revision = 1).put("cardId", "card-11"))
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
    @Test fun lifecycleBatchIsAtomicAndRestorable() {
        val messages = fixture("basic-text-stream")
        val pending = JSONObject().put("cardId", "card").put("sessionId", "session")
            .put("surfaceId", messages.first().getJSONObject("createSurface").getString("surfaceId"))
            .put("revision", 0).put("status", "pending").put("editable", false).put("replayMessages", JSONArray())
        store.snapshot(snapshot().put("cards", JSONArray().put(pending)))
        messages.forEachIndexed { index, payload ->
            store.consume(event(index + 1, "a2ui.message", payload, revision = 1)
                .put("surfaceId", messages.first().getJSONObject("createSurface").getString("surfaceId"))
                .put("messageIndex", index).put("messageCount", messages.size))
            if (index < messages.lastIndex) assertEquals(0, store.cards.getValue("card").revision)
        }
        val card = store.cards.getValue("card")
        assertEquals(1, card.revision)
        assertFalse(store.hasPendingCommit)
        val document = UiStateDocument.restore(card.replay)
        assertEquals(1, document.getJSONObject("surfaces").length())
        val surface = document.getJSONObject("surfaces").getJSONObject(card.surfaceId)
        assertEquals(3, surface.getJSONObject("components").length())
        assertFalse(surface.getJSONObject("dataModel").has("obsolete"))
    }
    @Test fun interruptedBatchDoesNotExposePartialStateAndSnapshotClearsIt() {
        createCard()
        val update = fixture("basic-text-stream")[1].apply { getJSONObject("updateDataModel").put("surfaceId", "surface") }
        store.consume(event(2, "a2ui.message", update, 1, 2).put("messageCount", 2))
        assertTrue(store.hasPendingCommit)
        assertEquals(1, store.cards.getValue("card").revision)
        val card = store.cards.getValue("card")
        val serialized = JSONObject().put("cardId", card.id).put("sessionId", "session").put("surfaceId", card.surfaceId)
            .put("revision", card.revision).put("status", card.status).put("editable", card.editable).put("replayMessages", JSONArray(card.replay.map(::JSONObject)))
        store.snapshot(snapshot().put("cards", JSONArray().put(serialized)).put("lastEventId", 2))
        assertFalse(store.hasPendingCommit)
        assertEquals(card.replay, store.cards.getValue("card").replay)
    }
    @Test fun canonicalRecoveryHasLargerBudgetThanWire() {
        val large = envelope(text = "x".repeat(UiStateDocument.MAX_MESSAGE_BYTES))
        assertTrue(runCatching { UiStateDocument.apply(UiStateDocument.empty(), listOf(large)) }.isFailure)
        assertEquals(1, UiStateDocument.restore(listOf(large.toString())).getJSONObject("surfaces").length())
    }
    @Test fun arrayUpdatesPadNullAndBoundWritesWithoutCreatingDeletedPaths() {
        val create = envelope().apply { getJSONObject("createSurface").put("dataModel", JSONObject().put("items", JSONArray())) }
        val initial = UiStateDocument.apply(UiStateDocument.empty(), listOf(create))
        fun update(path: String, value: Any) = fixture("basic-text-stream")[1].apply {
            getJSONObject("updateDataModel").put("surfaceId", "surface").put("path", path).put("value", value)
        }
        val filled = UiStateDocument.apply(initial, listOf(update("/items/2/name", "filled")))
        val items = filled.getJSONObject("surfaces").getJSONObject("surface").getJSONObject("dataModel").getJSONArray("items")
        assertEquals(3, items.length())
        assertTrue(items.isNull(0))
        assertTrue(items.isNull(1))
        assertEquals("filled", items.getJSONObject(2).getString("name"))
        val deleted = UiStateDocument.apply(filled, listOf(update("/items/1", JSONObject.NULL), update("/missing/4/name", JSONObject.NULL)))
        assertFalse(deleted.getJSONObject("surfaces").getJSONObject("surface").getJSONObject("dataModel").has("missing"))
        assertEquals(2, deleted.getJSONObject("surfaces").getJSONObject("surface").getJSONObject("dataModel").getJSONArray("items").length())
        val bounded = UiStateDocument.apply(initial, listOf(update("/items/10000", "last")))
        assertEquals(10001, bounded.getJSONObject("surfaces").getJSONObject("surface").getJSONObject("dataModel").getJSONArray("items").length())
        assertTrue(runCatching { UiStateDocument.apply(initial, listOf(update("/items/10001", "invalid"))) }.isFailure)
        assertTrue(runCatching { UiStateDocument.apply(initial, listOf(update("/items/01", "invalid"))) }.isFailure)
    }
}
