package com.tencent.kuikly.a2ui.agent.data

import org.json.JSONArray
import org.json.JSONObject

class ChatStateStore {
    var sessionId: String = ""
        private set
    var cursor: Long = 0
        private set
    var activeRunId: String? = null
        private set
    var canLoadHistory = false
        private set
    private val messageMap = linkedMapOf<String, ChatMessage>()
    private val cardMap = linkedMapOf<String, CardDocument>()
    private val recentCards = mutableListOf<String>()
    private val completedRuns = mutableSetOf<String>()

    val messages: List<ChatMessage> get() = messageMap.values.sortedBy { it.ordinal }
    val cards: Map<String, CardDocument> get() = cardMap.toMap()

    fun snapshot(json: JSONObject) {
        sessionId = json.getString("sessionId")
        cursor = json.getLong("lastEventId")
        activeRunId = json.nullableString("activeRunId")
        messageMap.clear()
        val allMessages = json.getJSONArray("messages").objects().map(ChatMessage::parse).sortedBy { it.ordinal }
        allMessages.takeLast(30).forEach { messageMap[it.id] = it }
        canLoadHistory = allMessages.size > 30
        cardMap.clear()
        json.getJSONArray("cards").objects().map(CardDocument::parse).forEach { cardMap[it.id] = it }
        recentCards.clear()
        val recent = json.getJSONArray("recentCardIds")
        (0 until recent.length()).forEach { recentCards.add(recent.getString(it)) }
        completedRuns.clear()
        allMessages.filter { it.role == "assistant" && it.status != "running" }.forEach { completedRuns.add(it.runId) }
    }

    fun confirmRun(json: JSONObject) {
        val id = json.getString("runId")
        if (json.getString("status") == "running" && id !in completedRuns) activeRunId = id
    }

    fun consume(event: JSONObject): Boolean {
        if (event.getString("sessionId") != sessionId) throw SyncRequired()
        val id = event.getLong("eventId")
        if (id <= cursor) return false
        if (id != cursor + 1) throw SyncRequired()
        val runId = event.getString("runId")
        val payload = event.getJSONObject("payload")
        when (event.getString("type")) {
            "message.created", "message.updated" -> {
                val message = ChatMessage.parse(payload)
                messageMap[message.id] = message
                message.parts.mapNotNull { it.cardId }.forEach { cardId ->
                    if (cardId !in cardMap) cardMap[cardId] = CardDocument(
                        cardId, event.getString("surfaceId"), 0, "pending", false, emptyList()
                    )
                }
            }
            "run.started" -> activeRunId = runId
            "a2ui.message", "card.replaced" -> applyEnvelope(event, payload)
            "run.completed", "run.failed", "run.cancelled" -> {
                completedRuns.add(runId)
                if (activeRunId == runId) activeRunId = null
                val cardId = event.getString("cardId")
                cardMap[cardId]?.takeIf { it.revision == 0 }?.let {
                    val status = if (event.getString("type") == "run.cancelled") "cancelled" else "failed"
                    cardMap[cardId] = it.copy(status = status)
                }
            }
            else -> throw SyncRequired()
        }
        cursor = id
        return true
    }

    private fun applyEnvelope(event: JSONObject, envelope: JSONObject) {
        if (envelope.optString("version") != "1.0") throw SyncRequired()
        val id = event.getString("cardId")
        val old = cardMap[id]
        val base = event.getInt("baseCardRevision")
        val revision = event.getInt("cardRevision")
        if ((old?.revision ?: 0) != base || revision != base + 1) throw SyncRequired()
        val surfaceId = event.getString("surfaceId")
        val replay = when {
            envelope.has("createSurface") -> {
                if (envelope.getJSONObject("createSurface").getString("surfaceId") != surfaceId) throw SyncRequired()
                listOf(envelope.toString())
            }
            envelope.has("updateComponents") -> {
                if (old == null || old.surfaceId != surfaceId || old.replay.isEmpty()) throw SyncRequired()
                val update = envelope.getJSONObject("updateComponents")
                if (update.getString("surfaceId") != surfaceId) throw SyncRequired()
                val canonical = JSONObject(old.replay.first())
                val surface = canonical.getJSONObject("createSurface")
                val components = linkedMapOf<String, JSONObject>()
                surface.getJSONArray("components").objects().forEach { components[it.getString("id")] = it }
                update.getJSONArray("components").objects().forEach { components[it.getString("id")] = it }
                surface.put("components", JSONArray(components.values.toList()))
                listOf(canonical.toString())
            }
            else -> throw SyncRequired()
        }
        cardMap[id] = CardDocument(id, surfaceId, revision, "ready", true, replay,
            if (envelope.has("updateComponents")) envelope.toString() else null)
        if (base == 0) {
            recentCards.remove(id)
            recentCards.add(0, id)
            while (recentCards.size > 10) recentCards.removeAt(recentCards.lastIndex)
        }
        cardMap.replaceAll { cardId, card -> card.copy(editable = cardId in recentCards && card.status == "ready") }
    }

    fun mergeHistory(page: JSONObject) {
        if (page.getString("sessionId") != sessionId) throw SyncRequired()
        page.getJSONArray("messages").objects().map(ChatMessage::parse).forEach { messageMap.putIfAbsent(it.id, it) }
        canLoadHistory = !page.isNull("nextBeforeOrdinal")
    }
}
