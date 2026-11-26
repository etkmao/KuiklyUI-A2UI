package com.tencent.kuikly.a2ui.agent.data

import org.json.JSONArray
import org.json.JSONObject

data class MessagePart(val text: String? = null, val cardId: String? = null)
data class ChatMessage(
    val id: String, val ordinal: Int, val role: String, val status: String,
    val runId: String, val parts: List<MessagePart>, val createdAt: String
) {
    companion object {
        fun parse(json: JSONObject): ChatMessage = ChatMessage(
            json.getString("messageId"), json.getInt("ordinal"), json.getString("role"),
            json.getString("status"), json.getString("runId"), json.getJSONArray("parts").objects().map {
                when (it.getString("type")) {
                    "text" -> MessagePart(text = it.getString("text"))
                    "card" -> MessagePart(cardId = it.getString("cardId"))
                    else -> error("不支持的消息内容")
                }
            }, json.getString("createdAt")
        )
    }
}

data class CardDocument(
    val id: String, val surfaceId: String, val revision: Int,
    val status: String, val editable: Boolean, val replay: List<String>,
    val incrementalEnvelope: String? = null
) {
    companion object {
        fun parse(json: JSONObject): CardDocument = CardDocument(
            json.getString("cardId"), json.getString("surfaceId"), json.getInt("revision"),
            json.getString("status"), json.getBoolean("editable"),
            json.getJSONArray("replayMessages").objects().map { it.toString() }
        )
    }
}

data class ChatUiState(
    val connectionLabel: String = "未连接", val connected: Boolean = false,
    val messages: List<ChatMessage> = emptyList(), val cards: Map<String, CardDocument> = emptyMap(),
    val activeRunId: String? = null, val posting: Boolean = false,
    val pendingText: String? = null, val notice: String? = null,
    val selectedCardId: String? = null, val canLoadHistory: Boolean = false,
    val loadingHistory: Boolean = false, val offline: Boolean = false,
    val uncertainRequest: Boolean = false
)

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
class SyncRequired : IllegalStateException("会话状态需要重新同步")
class AgentFailure(val code: String, val retryable: Boolean, val httpStatus: Int = 0) : Exception(code)
