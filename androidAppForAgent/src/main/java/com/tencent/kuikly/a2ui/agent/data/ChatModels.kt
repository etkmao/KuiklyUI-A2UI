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
    val incrementalEnvelopes: List<String> = emptyList()
) {
    companion object {
        fun parse(json: JSONObject): CardDocument {
            val replay = json.getJSONArray("replayMessages").objects().map { it.toString() }
            val status = json.getString("status")
            val revision = json.getInt("revision")
            if ((status == "ready") != (revision > 0) || (status != "ready" && replay.isNotEmpty())) throw SyncRequired()
            UiStateDocument.restore(replay)
            return CardDocument(json.getString("cardId"), json.getString("surfaceId"), revision,
                status, json.getBoolean("editable"), replay)
        }
    }
}

data class RendererCardAction(
    val cardId: String, val baseCardRevision: Int, val surfaceId: String,
    val tag: Int, val actionJson: String, val params: String?
) {
    fun requestBody(card: CardDocument): JSONObject {
        require(card.id == cardId && card.revision == baseCardRevision && card.surfaceId == surfaceId)
        require(card.editable && card.status == "ready")
        require(actionJson.toByteArray(Charsets.UTF_8).size <= UiStateDocument.MAX_MESSAGE_BYTES)
        val message = JSONObject(actionJson)
        require(message.opt("version") == "1.0")
        val action = message.getJSONObject("action")
        require(action.opt("name") == "submit_form" && action.opt("surfaceId") == surfaceId)
        val source = action.opt("sourceComponentId") as? String
        require(!source.isNullOrBlank())
        val declared = UiStateDocument.restore(card.replay).getJSONObject("surfaces")
            .getJSONObject(surfaceId).getJSONObject("components").getJSONObject(source)
        require(declared.opt("component") == "Button")
        val declaredEvent = declared.getJSONObject("action").getJSONObject("event")
        require(declaredEvent.opt("name") == "submit_form")
        val declaredContext = declaredEvent.getJSONObject("context")
        require(declaredContext.length() == 1 && declaredContext.has("value"))
        val binding = declaredContext.getJSONObject("value")
        require(binding.length() == 1 && binding.opt("@path") == "/form/value")
        val timestamp = action.opt("timestamp") as? String ?: error("Missing renderer timestamp")
        require(timestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")))
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false
        }.parse(timestamp) ?: error("Invalid renderer timestamp")
        val context = action.getJSONObject("context")
        require(context.length() == 1 && context.has("value"))
        val value = context.opt("value") as? String ?: error("Missing renderer value")
        require(value.trim().isNotEmpty() && value.trim().length <= 1000)
        val outgoing = JSONObject().put("name", "submit_form").put("surfaceId", surfaceId)
            .put("sourceComponentId", source).put("timestamp", timestamp)
            .put("context", JSONObject().put("value", value))
        if (action.has("userMessage")) {
            val userMessage = action.get("userMessage") as? String ?: error("Invalid userMessage")
            require(userMessage.length <= 1000)
            outgoing.put("userMessage", userMessage)
        }
        return JSONObject().put("cardId", cardId).put("baseCardRevision", baseCardRevision)
            .put("message", JSONObject().put("version", "1.0").put("action", outgoing))
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
class AgentFailure(val code: String, val retryable: Boolean, val httpStatus: Int = 0) : Exception(code) {
    val submissionUncertain: Boolean get() = httpStatus == 0 || httpStatus == 408 || httpStatus >= 500
}
