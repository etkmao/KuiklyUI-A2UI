package com.tencent.kuikly.a2ui.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tencent.kuikly.a2ui.agent.data.AgentApiClient
import com.tencent.kuikly.a2ui.agent.data.AgentConnection
import com.tencent.kuikly.a2ui.agent.data.AgentFailure
import com.tencent.kuikly.a2ui.agent.data.CardDocument
import com.tencent.kuikly.a2ui.agent.data.ChatMessage
import com.tencent.kuikly.a2ui.agent.data.ChatStateStore
import com.tencent.kuikly.a2ui.agent.data.ChatUiState
import com.tencent.kuikly.a2ui.agent.data.MessagePart
import com.tencent.kuikly.a2ui.agent.data.SyncRequired
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("agent_connection", 0)
    private val mutableState = MutableStateFlow(ChatUiState())
    val state = mutableState.asStateFlow()
    var connection = AgentConnection(
        preferences.getString("base", BuildConfig.AGENT_BASE_URL).orEmpty(),
        preferences.getString("routes", BuildConfig.AGENT_ROUTES).orEmpty()
    )
        private set
    private var store = ChatStateStore()
    private var api: AgentApiClient? = null
    private var streamJob: Job? = null
    private var submitJob: Job? = null
    private var historyJob: Job? = null
    private var cancelJob: Job? = null
    private var foreground = false
    private var needsSnapshot = true
    private var sessionId = preferences.getString("session", "").orEmpty()
    private data class Submission(val key: String, val body: JSONObject, val text: String)
    private var pending: Submission? = null

    fun foreground(active: Boolean) {
        foreground = active
        if (!active) {
            streamJob?.cancel()
            mutableState.update { it.copy(connected = false, connectionLabel = if (it.offline) "离线预览" else "连接已暂停") }
            return
        }
        if (state.value.offline) return
        if (api == null) {
            runCatching { connection.validate(BuildConfig.DEBUG) }.onSuccess { api = AgentApiClient(connection) }
                .onFailure { mutableState.update { it.copy(notice = "请先在设置中配置服务地址和路由；也可以离线预览卡片。") }; return }
        }
        subscribe()
    }

    fun connect(config: AgentConnection, newSession: Boolean = false) {
        config.validate(BuildConfig.DEBUG)
        val changed = config.baseUrl != connection.baseUrl || config.routesJson != connection.routesJson
        streamJob?.cancel(); submitJob?.cancel(); historyJob?.cancel(); cancelJob?.cancel()
        api?.close()
        connection = config
        api = AgentApiClient(config)
        preferences.edit().putString("base", config.baseUrl).putString("routes", config.routesJson).apply()
        if (changed || newSession || state.value.offline) {
            sessionId = ""
            preferences.edit().remove("session").apply()
            store = ChatStateStore()
            pending = null
            mutableState.value = ChatUiState()
        }
        needsSnapshot = true
        mutableState.update { it.copy(posting = false, pendingText = pending?.text, uncertainRequest = pending != null,
            notice = if (pending != null) "发送结果待确认，连接后点此安全重试。" else null, offline = false) }
        if (foreground) subscribe()
    }

    private fun subscribe() {
        if (streamJob?.isActive == true) return
        val transport = api ?: return
        streamJob = viewModelScope.launch {
            var retries = 0
            while (foreground && api === transport) {
                try {
                    mutableState.update { it.copy(connected = false, connectionLabel = if (retries == 0) "连接中…" else "正在重新连接…") }
                    if (sessionId.isEmpty()) {
                        val result = transport.createSession()
                        require(result.getJSONObject("capabilities").getString("protocolVersion") == "1.0" && result.getJSONObject("capabilities").getString("id") == "basic-v1")
                        sessionId = result.getString("sessionId")
                        preferences.edit().putString("session", sessionId).apply()
                        needsSnapshot = true
                    }
                    if (needsSnapshot || store.sessionId.isEmpty()) {
                        store.snapshot(transport.snapshot(sessionId))
                        needsSnapshot = false
                        publish()
                    }
                    transport.events(sessionId, store.cursor) {
                        viewModelScope.launch {
                            if (api === transport && foreground) mutableState.update {
                                it.copy(connected = true, connectionLabel = "已连接 · A2UI 1.0", notice = if (pending != null) it.notice else null)
                            }
                        }
                    }.collect { event ->
                        if (store.consume(event)) {
                            retries = 0
                            if (!state.value.posting) publish()
                            if (event.getString("type") == "run.failed") {
                                mutableState.update { it.copy(notice = failureText(event.getJSONObject("payload").optString("code"))) }
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    mutableState.update { it.copy(connected = false, connectionLabel = "连接已断开") }
                    val code = (e as? AgentFailure)?.code
                    if (store.hasPendingCommit || e is SyncRequired || code == "EVENT_CURSOR_EXPIRED" || code == "INVALID_CURSOR") {
                        needsSnapshot = true
                    } else if (e is AgentFailure && !e.retryable) {
                        mutableState.update { it.copy(notice = failureText(e.code)) }
                        break
                    } else if (e !is AgentFailure) {
                        mutableState.update { it.copy(notice = "服务响应无法解析，请检查服务和路由配置。") }
                        break
                    }
                    delay(minOf(1000L * (1L shl minOf(retries++, 4)), 15000L))
                }
            }
        }
    }

    fun send(text: String): Boolean {
        val prompt = text.trim()
        val current = state.value
        if (prompt.isEmpty() || prompt.length > 4000 || !current.connected || current.activeRunId != null || pending != null) return false
        val body = JSONObject().put("operation", if (current.selectedCardId == null) "create_card" else "update_card").put("prompt", prompt)
        current.selectedCardId?.let { id ->
            val card = store.cards[id]
            if (card == null || !card.editable) {
                mutableState.update { it.copy(selectedCardId = null, notice = "此卡片已不在可修改范围，请重新选择。") }
                return false
            }
            body.put("target", JSONObject().put("cardId", id)).put("baseCardRevision", card.revision).put("delivery", "patch")
        }
        pending = Submission(UUID.randomUUID().toString(), body, prompt)
        mutableState.update { it.copy(pendingText = prompt, selectedCardId = null, notice = null) }
        postPending()
        return true
    }

    fun retryPending() {
        if (state.value.connected && pending != null && submitJob?.isActive != true) postPending()
    }

    private fun postPending() {
        val submission = pending ?: return
        val transport = api ?: return
        mutableState.update { it.copy(posting = true) }
        submitJob = viewModelScope.launch {
            try {
                val result = transport.submit(sessionId, submission.body, submission.key)
                store.confirmRun(result)
                pending = null
                mutableState.update { it.copy(posting = false, pendingText = null, uncertainRequest = false, notice = null) }
                publish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = e as? AgentFailure
                val uncertain = failure == null || failure.httpStatus == 0 || failure.httpStatus >= 500
                if (!uncertain) pending = null
                mutableState.update { it.copy(posting = false, uncertainRequest = uncertain,
                    pendingText = if (uncertain) submission.text else null,
                    notice = if (uncertain) "发送结果待确认，点此使用原请求安全重试。" else failureText(failure?.code.orEmpty())) }
                if (failure?.code in listOf("RUN_IN_PROGRESS", "REVISION_CONFLICT", "CARD_NOT_EDITABLE")) resync()
            }
        }
    }

    fun cancelRun() {
        val run = state.value.activeRunId ?: return
        val transport = api ?: return
        if (cancelJob?.isActive == true) return
        cancelJob = viewModelScope.launch {
            try {
                transport.cancel(run)
                mutableState.update { it.copy(notice = "停止请求已提交") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(notice = "停止请求未确认，请重试或恢复连接。") } }
        }
    }

    fun resync() {
        streamJob?.cancel()
        needsSnapshot = true
        if (foreground) subscribe()
    }

    fun selectCard(id: String?) {
        if (id == null || store.cards[id]?.editable == true) mutableState.update { it.copy(selectedCardId = id) }
    }

    fun loadHistory() {
        if (!state.value.connected || !store.canLoadHistory || historyJob?.isActive == true) return
        val before = store.messages.firstOrNull()?.ordinal ?: return
        val transport = api ?: return
        mutableState.update { it.copy(loadingHistory = true) }
        historyJob = viewModelScope.launch {
            try { store.mergeHistory(transport.messages(sessionId, before)); publish() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(notice = "历史消息加载失败，请重试。") } }
            finally { mutableState.update { it.copy(loadingHistory = false) } }
        }
    }

    private fun publish() {
        mutableState.update { it.copy(messages = store.messages, cards = store.cards,
            activeRunId = store.activeRunId, canLoadHistory = store.canLoadHistory,
            selectedCardId = it.selectedCardId?.takeIf { id -> store.cards[id]?.editable == true }) }
    }

    fun offlinePreview() {
        if (state.value.activeRunId != null || state.value.posting || pending != null) return
        streamJob?.cancel()
        val surfaceId = UUID.randomUUID().toString()
        val cardId = UUID.randomUUID().toString()
        val envelope = JSONObject().put("version", "1.0").put("createSurface", JSONObject()
            .put("surfaceId", surfaceId)
            .put("catalogId", "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json")
            .put("components", JSONArray().put(JSONObject().put("id", "root").put("component", "Text")
                .put("text", "你好！这是一张由 Kuikly A2UI 原生渲染的卡片。\n\n连接服务后，回答会显示在这里。")
                .put("variant", "body"))))
        mutableState.value = ChatUiState(connectionLabel = "离线预览 · 非服务回复", offline = true,
            messages = listOf(ChatMessage("preview", 1, "assistant", "completed", "preview", listOf(MessagePart(cardId = cardId)), "")),
            cards = mapOf(cardId to CardDocument(cardId, surfaceId, 1, "ready", false, listOf(envelope.toString()))),
            notice = "当前为离线渲染预览，设置服务连接后可发送消息。")
    }

    private fun failureText(code: String): String = when (code) {
        "UNAUTHORIZED" -> "连接未授权，请在设置中填写本次请求头。"
        "NOT_FOUND" -> "会话或接口不存在，请检查路由；服务重启后可新建会话。"
        "RUN_IN_PROGRESS" -> "当前会话仍有任务运行，正在同步状态。"
        "REVISION_CONFLICT" -> "卡片版本已变化，正在同步最新版本。"
        "CARD_NOT_EDITABLE" -> "此卡片已不可修改，请选择最近的卡片。"
        "RUN_TIMEOUT" -> "生成超时，请稍后重新发送。"
        "INVALID_GENERATION", "GENERATION_FAILED" -> "卡片生成失败，请稍后重新发送。"
        "RATE_LIMIT", "STREAM_LIMIT" -> "服务繁忙，请稍后重新连接。"
        else -> "请求未完成，请检查连接或重新发送。"
    }

    override fun onCleared() {
        streamJob?.cancel(); submitJob?.cancel(); historyJob?.cancel(); cancelJob?.cancel()
        api?.close()
        super.onCleared()
    }
}
