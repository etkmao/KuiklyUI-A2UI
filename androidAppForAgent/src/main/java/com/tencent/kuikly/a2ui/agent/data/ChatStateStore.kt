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
    private data class PendingCommit(val cardId: String, val runId: String, val messageId: String,
        val surfaceId: String, val base: Int, val revision: Int, val count: Int,
        val replaced: Boolean, val messages: List<JSONObject>)
    private var pendingCommit: PendingCommit? = null
    val hasPendingCommit: Boolean get() = pendingCommit != null

    val messages: List<ChatMessage> get() = messageMap.values.sortedBy { it.ordinal }
    val cards: Map<String, CardDocument> get() = cardMap.toMap()
    private val rpcMap = linkedMapOf<String, RpcCall>()
    private val rpcDeliveries = mutableListOf<RpcDelivery>()
    val rpcCalls: Map<String, RpcCall> get() = rpcMap.toMap()

    fun confirmRpc(call: RpcCall) {
        if (call.sessionId != sessionId) throw SyncRequired()
        val old = rpcMap[call.functionCallId]
        if (old != null && (old.rendererId != call.rendererId || old.cardId != call.cardId ||
                old.surfaceId != call.surfaceId || old.baseCardRevision != call.baseCardRevision ||
                old.direction != call.direction || old.request != call.request)) throw SyncRequired()
        if (old?.terminal == true) return
        rpcMap[call.functionCallId] = call
        while (rpcMap.size > 128) {
            val expired = rpcMap.entries.firstOrNull { it.value.terminal } ?: break
            rpcMap.remove(expired.key)
        }
    }

    fun drainRpcDeliveries(): List<RpcDelivery> = rpcDeliveries.toList().also { rpcDeliveries.clear() }

    private fun consumeRpc(event: JSONObject, id: Long, payload: JSONObject) {
        val binding = RendererBinding(event.getString("rendererId"), sessionId, event.getString("cardId"),
            event.getString("surfaceId"), event.getInt("baseCardRevision"))
        val callId = event.getString("functionCallId")
        if (event.getString("type") == "rpc.updated") {
            val call = RpcCall.parse(payload)
            if (call.functionCallId != callId || !call.matches(binding)) throw SyncRequired()
            confirmRpc(call)
        } else {
            val type = when {
                payload.has("callRendererFunction") -> "callRendererFunction"
                payload.has("agentFunctionResponse") -> "agentFunctionResponse"
                else -> throw SyncRequired()
            }
            if (payload.opt("version") != "1.0" || payload.getJSONObject(type).getString("functionCallId") != callId) throw SyncRequired()
            if (type == "callRendererFunction") {
                val request = payload.getJSONObject(type)
                val call = request.getJSONObject("callFunction")
                if (request.opt("surfaceId") != binding.surfaceId || call.opt("catalogId") != RpcCall.CATALOG_ID ||
                    call.opt("@call") != "getGreeting") throw SyncRequired()
            }
            rpcDeliveries.add(RpcDelivery(id, binding, callId, payload.toString()))
        }
        cursor = id
    }

    fun snapshot(json: JSONObject) {
        val nextSession = json.getString("sessionId")
        val nextCursor = json.getLong("lastEventId")
        val nextRun = json.nullableString("activeRunId")
        val capability = json.getJSONObject("capabilities")
        if (capability.getString("id") != UiStateDocument.PROFILE_ID || capability.getString("protocolVersion") != "1.0") throw SyncRequired()
        val messageJson = json.getJSONArray("messages").objects()
        val cardJson = json.getJSONArray("cards").objects()
        if (messageJson.any { it.getString("sessionId") != nextSession } || cardJson.any { it.getString("sessionId") != nextSession }) throw SyncRequired()
        val allMessages = messageJson.map(ChatMessage::parse).sortedBy { it.ordinal }
        val allCards = cardJson.map(CardDocument::parse)
        val recent = json.getJSONArray("recentCardIds")
        val nextRecent = (0 until recent.length()).map { recent.getString(it) }
        if (sessionId != nextSession) rpcMap.clear()
        rpcDeliveries.clear()
        sessionId = nextSession
        cursor = nextCursor
        activeRunId = nextRun
        pendingCommit = null
        messageMap.clear()
        allMessages.takeLast(30).forEach { messageMap[it.id] = it }
        canLoadHistory = allMessages.size > 30
        cardMap.clear()
        allCards.forEach { cardMap[it.id] = it }
        recentCards.clear()
        recentCards.addAll(nextRecent)
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
        val payload = event.getJSONObject("payload")
        if (event.getString("type") in setOf("rpc.message", "rpc.updated")) {
            consumeRpc(event, id, payload)
            return true
        }
        val runId = event.getString("runId")
        if (pendingCommit != null && event.getString("type") != "a2ui.message") throw SyncRequired()
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
        val type = UiStateDocument.validateMessage(envelope)
        val id = event.getString("cardId")
        val old = cardMap[id] ?: throw SyncRequired()
        val base = event.getInt("baseCardRevision")
        val revision = event.getInt("cardRevision")
        if (old.revision != base || revision != base + 1) throw SyncRequired()
        val surfaceId = event.getString("surfaceId")
        val messageIndex = event.getInt("messageIndex")
        val messageCount = event.getInt("messageCount")
        if (messageCount !in 1..UiStateDocument.MAX_MESSAGES || messageIndex !in 0 until messageCount) throw SyncRequired()
        var batch = pendingCommit
        if (batch == null) {
            if (messageIndex != 0) throw SyncRequired()
            val replaced = event.getString("type") == "card.replaced"
            if (replaced && (base == 0 || surfaceId == old.surfaceId || type != "deleteSurface" || envelope.getJSONObject(type).getString("surfaceId") != old.surfaceId || messageCount < 2)) throw SyncRequired()
            if (!replaced && surfaceId != old.surfaceId) throw SyncRequired()
            if (base == 0 && (type != "createSurface" || envelope.getJSONObject(type).getString("surfaceId") != surfaceId)) throw SyncRequired()
            batch = PendingCommit(id, event.getString("runId"), event.getString("messageId"), surfaceId, base, revision, messageCount, replaced, emptyList())
        }
        if (batch.cardId != id || batch.runId != event.getString("runId") || batch.messageId != event.getString("messageId") || batch.surfaceId != surfaceId || batch.base != base || batch.revision != revision || batch.count != messageCount || batch.messages.size != messageIndex) throw SyncRequired()
        val completed = batch.copy(messages = batch.messages + JSONObject(envelope.toString()))
        if (completed.messages.size < completed.count) {
            pendingCommit = completed
            return
        }
        val document = UiStateDocument.apply(UiStateDocument.restore(old.replay), completed.messages)
        if (completed.replaced && (document.getJSONObject("surfaces").length() != 1 || !document.getJSONObject("surfaces").has(surfaceId))) throw SyncRequired()
        val replay = UiStateDocument.replay(document)
        cardMap[id] = CardDocument(id, surfaceId, revision, "ready", true, replay,
            if (completed.replaced || base == 0) emptyList() else completed.messages.map { it.toString() })
        pendingCommit = null
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
