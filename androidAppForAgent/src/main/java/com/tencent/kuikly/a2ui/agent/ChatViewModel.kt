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
import com.tencent.kuikly.a2ui.agent.data.RendererBinding
import com.tencent.kuikly.a2ui.agent.data.RpcCall
import com.tencent.kuikly.a2ui.agent.data.RpcDelivery
import com.tencent.kuikly.a2ui.agent.ui.A2UICardHostView
import java.lang.ref.WeakReference
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
    private data class Submission(val key: String, val body: JSONObject, val text: String?, val action: Boolean = false)
    private var pending: Submission? = null
    private data class BoundRenderer(val binding: RendererBinding, val host: WeakReference<A2UICardHostView>,
        val registration: Job)
    private data class RpcSubmission(val binding: RendererBinding, val body: String, val key: String,
        val toRenderer: Boolean, val response: Boolean = false)
    private val renderers = linkedMapOf<String, BoundRenderer>()
    private val rpcPending = linkedMapOf<String, RpcSubmission>()
    private val rpcJobs = linkedMapOf<String, Job>()
    private val deliveredRpc = linkedSetOf<Pair<String, String>>()
    private val rpcIncoming = linkedMapOf<Pair<String, String>, RpcDelivery>()
    private val rpcDeliveryJobs = linkedMapOf<Pair<String, String>, Job>()

    fun attachRpcHost(host: A2UICardHostView, binding: RendererBinding) {
        val transport = api ?: return
        if (binding.sessionId != sessionId || !binding.matches(store.cards[binding.cardId]) || state.value.offline) return
        val old = renderers.remove(binding.rendererId)
        old?.registration?.cancel()
        val registration = viewModelScope.launch {
            try {
                transport.registerRenderer(binding.sessionId, binding)
                if (api === transport && host.rpcBinding == binding) host.rpcRegistered(binding)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (host.rpcBinding == binding) host.rpcUnavailable("RPC 实例注册未确认，点击恢复连接")
            }
        }
        renderers[binding.rendererId] = BoundRenderer(binding, WeakReference(host), registration)
    }

    fun releaseRpcHost(binding: RendererBinding) {
        val bound = renderers[binding.rendererId] ?: return
        if (bound.binding != binding) return
        renderers.remove(binding.rendererId)
        rpcPending.values.filter { it.binding == binding }.map { it.key }.forEach { key ->
            rpcPending.remove(key); rpcJobs.remove(key)?.cancel()
        }
        rpcIncoming.keys.filter { it.first == binding.rendererId }.toList().forEach { key ->
            rpcIncoming.remove(key); rpcDeliveryJobs.remove(key)?.cancel()
        }
        mutableState.update { it.copy(uncertainRpc = rpcPending.isNotEmpty() || rpcIncoming.isNotEmpty()) }
        val transport = api ?: return
        viewModelScope.launch {
            bound.registration.join()
            runCatching { transport.releaseRenderer(binding.sessionId, binding.rendererId) }
        }
    }

    fun forwardRpc(binding: RendererBinding, envelope: String) {
        if (!validRpcBinding(binding)) return
        val message = runCatching { JSONObject(envelope) }.getOrNull() ?: return
        if (message.opt("version") != "1.0") return
        val response = message.has("rendererFunctionResponse")
        val payload = message.optJSONObject(if (response) "rendererFunctionResponse" else "callAgentFunction") ?: return
        val callId = payload.optString("functionCallId")
        if (callId.isBlank()) return
        if (!response) {
            val function = payload.optJSONObject("callFunction") ?: return
            if (payload.opt("surfaceId") != binding.surfaceId || function.opt("catalogId") != RpcCall.CATALOG_ID ||
                function.opt("@call") != "getGreeting") return
        }
        val body = if (response) JSONObject().put("rendererId", binding.rendererId).put("message", message)
            else binding.requestBody().put("message", message)
        val key = "${binding.rendererId}:$callId:${if (response) "response" else "invoke"}"
        if (rpcPending.containsKey(key) || store.rpcCalls[callId]?.terminal == true) return
        rpcPending[key] = RpcSubmission(binding, body.toString(), key, false, response)
        postRpc(key)
    }

    fun requestRendererGreeting(binding: RendererBinding, name: String, timeoutMs: Int = 10000): Boolean {
        if (!validRpcBinding(binding) || !state.value.connected || name.isBlank() || name.trim().length > 200 || timeoutMs !in 1..30000) return false
        val key = UUID.randomUUID().toString()
        val call = JSONObject().put("catalogId", RpcCall.CATALOG_ID)
            .put("@call", "getGreeting").put("args", JSONObject().put("name", name.trim()))
        val body = binding.requestBody().put("callFunction", call).put("timeoutMs", timeoutMs)
        rpcPending[key] = RpcSubmission(binding, body.toString(), key, true)
        postRpc(key)
        return true
    }

    private fun validRpcBinding(binding: RendererBinding): Boolean = binding.sessionId == sessionId &&
        binding.matches(store.cards[binding.cardId]) && renderers[binding.rendererId]?.binding == binding &&
        renderers[binding.rendererId]?.host?.get()?.rpcBinding == binding

    private fun postRpc(key: String) {
        val submission = rpcPending[key] ?: return
        val transport = api ?: return
        if (rpcJobs[key]?.isActive == true || !validRpcBinding(submission.binding)) return
        rpcJobs[key] = viewModelScope.launch {
            try {
                renderers[submission.binding.rendererId]?.registration?.join()
                if (!validRpcBinding(submission.binding)) return@launch
                val body = JSONObject(submission.body)
                val call = if (submission.response) transport.respondRpc(submission.binding.sessionId, body)
                    else transport.submitRpc(submission.binding.sessionId, body, submission.key, submission.toRenderer)
                if (api !== transport || sessionId != call.sessionId) return@launch
                store.confirmRpc(call)
                rpcPending.remove(key)
                mutableState.update { it.copy(rpcNotice = "RPC ${call.status}", uncertainRpc = rpcPending.isNotEmpty()) }
                notifyRpc(call)
                publishRpc()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val uncertain = (e as? AgentFailure)?.submissionUncertain != false
                if (!uncertain) rpcPending.remove(key)
                mutableState.update { it.copy(rpcNotice = if (uncertain) "RPC 待确认；使用原请求安全重试"
                    else "RPC 未完成：${(e as? AgentFailure)?.code ?: "INVALID_RESPONSE"}", uncertainRpc = rpcPending.isNotEmpty()) }
                renderers[submission.binding.rendererId]?.host?.get()?.rpcUnavailable(state.value.rpcNotice.orEmpty())
            } finally { rpcJobs.remove(key) }
        }
    }

    fun retryRpc(binding: RendererBinding? = null) {
        rpcPending.values.filter { binding == null || it.binding == binding }.map { it.key }.forEach(::postRpc)
        rpcIncoming.values.filter { binding == null || it.binding == binding }.toList().forEach(::deliverRpc)
    }

    fun cancelRpc(binding: RendererBinding) {
        val call = store.rpcCalls.values.lastOrNull { it.matches(binding) && !it.terminal } ?: return
        val transport = api ?: return
        viewModelScope.launch {
            try { val result = transport.cancelRpc(binding.sessionId, call.functionCallId, binding.rendererId)
                store.confirmRpc(result); notifyRpc(result); publishRpc() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(rpcNotice = "RPC 取消未确认，可再次取消或查询") } }
        }
    }

    fun queryRpc(binding: RendererBinding) {
        val transport = api ?: return
        rpcIncoming.values.filter { it.binding == binding }.toList().forEach(::deliverRpc)
        viewModelScope.launch {
            store.rpcCalls.values.filter { it.matches(binding) }.forEach { call ->
                try {
                    val result = transport.rpcCall(binding.sessionId, call.functionCallId, binding.rendererId)
                    store.confirmRpc(result); notifyRpc(result)
                    if (result.direction == "renderer_to_agent" && result.response != null) deliverRpc(
                        RpcDelivery(0, binding, result.functionCallId, result.response))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { mutableState.update { it.copy(rpcNotice = "RPC 查询未确认") } }
            }
            publishRpc()
        }
    }

    private fun deliverRpc(delivery: RpcDelivery) {
        val bound = renderers[delivery.binding.rendererId] ?: return
        if (bound.binding != delivery.binding || !validRpcBinding(delivery.binding)) return
        val key = delivery.binding.rendererId to delivery.functionCallId
        if (key in deliveredRpc || rpcDeliveryJobs[key]?.isActive == true) return
        if (!JSONObject(delivery.envelope).has("callRendererFunction")) {
            writeRpcDelivery(delivery)
            return
        }
        val known = store.rpcCalls[delivery.functionCallId]
        if (known?.terminal == true) { notifyRpc(known); return }
        val transport = api ?: return
        rpcIncoming[key] = delivery
        rpcDeliveryJobs[key] = viewModelScope.launch {
            try {
                bound.registration.join()
                val call = transport.rpcCall(delivery.binding.sessionId, delivery.functionCallId, delivery.binding.rendererId)
                if (api !== transport || !validRpcBinding(delivery.binding) || !call.matches(delivery.binding) ||
                    call.direction != "agent_to_renderer" || call.request != delivery.envelope) return@launch
                store.confirmRpc(call)
                val confirmed = store.rpcCalls[call.functionCallId] ?: return@launch
                notifyRpc(confirmed); publishRpc()
                rpcIncoming.remove(key)
                if (confirmed.canExecute(delivery)) writeRpcDelivery(delivery)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutableState.update { it.copy(rpcNotice = "下行 RPC 状态待确认；未执行，可查询或安全重试", uncertainRpc = true) }
            } finally { rpcDeliveryJobs.remove(key) }
        }
    }

    private fun writeRpcDelivery(delivery: RpcDelivery) {
        val bound = renderers[delivery.binding.rendererId] ?: return
        if (bound.binding != delivery.binding || !validRpcBinding(delivery.binding)) return
        val key = delivery.binding.rendererId to delivery.functionCallId
        if (key in deliveredRpc) return
        if (bound.host.get()?.deliverRpc(delivery) == true) {
            deliveredRpc.add(key)
            while (deliveredRpc.size > 256) deliveredRpc.remove(deliveredRpc.first())
        }
    }

    private fun notifyRpc(call: RpcCall) {
        val bound = renderers[call.rendererId] ?: return
        if (call.matches(bound.binding)) bound.host.get()?.rpcUpdated(call)
    }

    private fun publishRpc() {
        mutableState.update { it.copy(rpcCalls = store.rpcCalls, sessionId = store.sessionId) }
    }

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
        rpcJobs.values.toList().forEach { it.cancel() }; rpcJobs.clear()
        rpcDeliveryJobs.values.toList().forEach { it.cancel() }; rpcDeliveryJobs.clear()
        if (changed || newSession || state.value.offline) {
            renderers.values.mapNotNull { it.host.get() }.forEach { it.releaseSurface() }
            renderers.clear(); deliveredRpc.clear(); rpcPending.clear(); rpcIncoming.clear()
        }
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
                            if (api === transport && foreground) {
                                mutableState.update { it.copy(connected = true, connectionLabel = "已连接 · A2UI 1.0", notice = if (pending != null) it.notice else null) }
                                renderers.values.toList().forEach { queryRpc(it.binding) }
                            }
                        }
                    }.collect { event ->
                        if (store.consume(event)) {
                            retries = 0
                            store.drainRpcDeliveries().forEach(::deliverRpc)
                            if (event.getString("type") == "rpc.updated") {
                                val call = store.rpcCalls[event.getString("functionCallId")]
                                if (call != null) notifyRpc(call)
                                publishRpc()
                            } else if (event.getString("type") == "rpc.message") publishRpc()
                            else if (!state.value.posting) publish()
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

    fun submitAction(action: com.tencent.kuikly.a2ui.agent.data.RendererCardAction): Boolean {
        val card = store.cards[action.cardId] ?: state.value.cards[action.cardId]
        val body = runCatching { action.requestBody(card ?: error("Missing card")) }.getOrElse {
            mutableState.update { state -> state.copy(notice = "卡片交互缺少真实来源、输入无效或版本已变化，未发送请求。") }
            return false
        }
        val current = state.value
        if (!current.connected || current.offline || current.activeRunId != null || pending != null || current.posting || api == null) {
            mutableState.update { it.copy(notice = "请等待服务连接或当前任务完成后再提交卡片。") }
            return false
        }
        pending = Submission(UUID.randomUUID().toString(), body, null, action = true)
        mutableState.update { it.copy(pendingText = null, notice = null) }
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
                val result = if (submission.action) transport.submitAction(sessionId, submission.body, submission.key)
                    else transport.submit(sessionId, submission.body, submission.key)
                if (submission.action && result.opt("operation") != "update_card") throw AgentFailure("INVALID_RESPONSE", false)
                store.confirmRun(result)
                pending = null
                mutableState.update { it.copy(posting = false, pendingText = null, uncertainRequest = false, notice = null) }
                publish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = e as? AgentFailure
                val uncertain = failure == null || failure.submissionUncertain
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
            sessionId = store.sessionId, rpcCalls = store.rpcCalls,
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
        rpcJobs.values.toList().forEach { it.cancel() }
        rpcDeliveryJobs.values.toList().forEach { it.cancel() }
        renderers.values.mapNotNull { it.host.get() }.forEach { it.releaseSurface() }
        rpcPending.clear(); rpcJobs.clear(); renderers.clear(); deliveredRpc.clear()
        rpcIncoming.clear(); rpcDeliveryJobs.clear()
        api?.close()
        super.onCleared()
    }
}
