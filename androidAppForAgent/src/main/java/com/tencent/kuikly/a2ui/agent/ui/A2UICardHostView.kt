package com.tencent.kuikly.a2ui.agent.ui

import android.content.Context
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import com.tencent.kuikly.a2ui.agent.ChatViewModel
import com.tencent.kuikly.a2ui.agent.data.RendererBinding
import com.tencent.kuikly.a2ui.agent.data.RpcCall
import com.tencent.kuikly.a2ui.agent.data.RpcDelivery
import org.json.JSONObject
import java.util.UUID
import com.tencent.kuikly.a2ui.agent.data.CardDocument
import com.tencent.kuikly.a2ui.agent.data.RendererCardAction
import com.tencent.kuikly.a2ui.render.android.A2UISurfaceView
import com.tencent.kuikly.a2ui.render.android.A2UISurfaceViewListener
import com.tencent.kuikly.a2ui.bridge.A2UIBridgeManager

class A2UICardHostView(context: Context) : FrameLayout(context) {
    private var document: CardDocument? = null
    private var surface: A2UISurfaceView? = null
    private var renderedIdentity: Triple<String, String, Int>? = null
    private var generation = 0
    var onContentResize: (() -> Unit)? = null
    var onAction: ((RendererCardAction) -> Unit)? = null
    var onOpenLink: ((String) -> Unit)? = null
    private var rpcModel: ChatViewModel? = null
    private var rpcSessionId = ""
    var rpcBinding: RendererBinding? = null
        private set
    var rpcReady: Boolean = false
        private set
    var onRpcCompletion: ((String) -> Unit)? = null
    var onRpcEnvelopeOutbound: ((String) -> Unit)? = null
    var onRpcInvocationStarted: ((String?) -> Unit)? = null
    var onRendererRpcHandled: ((String) -> Unit)? = null
    var deferRendererRpcResponses: Boolean = false
    private var rpcStatus: TextView? = null
    private var rpcButtons = emptyList<Button>()
    private val deferredRpcResponses = linkedMapOf<String, () -> Unit>()

    fun connectRpc(model: ChatViewModel?, sessionId: String) {
        if (rpcModel === model && rpcSessionId == sessionId) return
        releaseSurface()
        rpcModel = model
        rpcSessionId = sessionId
    }

    fun rpcRegistered(binding: RendererBinding) {
        if (rpcBinding != binding || !isAttachedToWindow) return
        rpcReady = true
        rpcButtons.forEach { it.isEnabled = true }
        rpcStatus?.text = "RPC 已就绪 · getGreeting(name)"
    }

    fun rpcUnavailable(message: String) { rpcStatus?.text = message }

    fun rpcDiagnostics(): JSONObject = JSONObject().put("rendererId", rpcBinding?.rendererId)
        .put("sessionId", rpcBinding?.sessionId).put("cardId", rpcBinding?.cardId)
        .put("surfaceId", rpcBinding?.surfaceId).put("baseCardRevision", rpcBinding?.baseCardRevision)
        .put("sdkInstanceId", surface?.instanceId).put("registered", rpcReady)
        .put("attached", isAttachedToWindow).put("statusText", rpcStatus?.text?.toString())
        .put("nativeChildCount", surface?.childCount ?: 0).put("renderedIdentity", renderedIdentity?.toString())

    fun recoverRpcRuntime() {
        releaseSurface()
        if (isAttachedToWindow) render()
    }

    fun rpcUpdated(call: RpcCall) {
        val binding = rpcBinding ?: return
        if (!call.matches(binding)) return
        val response = call.response?.let(::JSONObject)
        val body = response?.optJSONObject(if (call.direction == "renderer_to_agent") "agentFunctionResponse" else "rendererFunctionResponse")
        rpcStatus?.text = if (body?.has("value") == true) "${call.status}: ${body.get("value")}" else
            "${call.status}${body?.optJSONObject("error")?.optString("code")?.let { ": $it" }.orEmpty()}"
        if (call.terminal) deferredRpcResponses.remove(call.functionCallId)
    }

    fun deliverRpc(delivery: RpcDelivery): Boolean {
        if (rpcBinding != delivery.binding || !delivery.binding.matches(document) || !isAttachedToWindow) return false
        val view = surface ?: return false
        view.writeStream(delivery.envelope)
        return true
    }

    fun invokeAgentGreeting(name: String, timeoutMs: Long = 10000): Boolean {
        val binding = rpcBinding ?: return false
        val view = surface ?: return false
        if (!rpcReady || !isAttachedToWindow || !binding.matches(document) || name.isBlank() || name.trim().length > 200) return false
        val args = JSONObject().put("name", name.trim()).toString()
        view.runOnContextThread {
            val requestId = A2UIBridgeManager.invokeAgentFunctionJson(instanceId = view.instanceId, surfaceId = binding.surfaceId,
                name = "getGreeting", argsJson = args, timeoutMs = timeoutMs, catalogId = RpcCall.CATALOG_ID,
                onCompleteJson = { response -> post {
                    if (rpcBinding == binding && surface === view && isAttachedToWindow) {
                        val result = runCatching { JSONObject(response) }.getOrNull()
                        rpcStatus?.text = if (result?.has("result") == true) result.get("result").toString()
                            else "RPC ${result?.optJSONObject("error")?.optString("code") ?: "INVALID_RESPONSE"}"
                        onRpcCompletion?.invoke(response)
                    }
                } })
            post {
                if (rpcBinding == binding && surface === view && isAttachedToWindow) {
                    if (requestId == null) rpcUnavailable("RPC SDK 实例尚未就绪，未发出调用")
                    onRpcInvocationStarted?.invoke(requestId)
                }
            }
        }
        return true
    }

    private fun installRpc(view: A2UISurfaceView, binding: RendererBinding) {
        view.rpcEnvelopeOutboundHandler = { envelope -> post {
            if (rpcBinding == binding && surface === view && isAttachedToWindow && binding.matches(document)) {
                onRpcEnvelopeOutbound?.invoke(envelope)
                rpcModel?.forwardRpc(binding, envelope)
            }
        } }
        view.runOnContextThread {
            A2UIBridgeManager.setRendererFunctionJsonHandler(view.instanceId) { requestJson ->
                val request = JSONObject(requestJson)
                val id = request.getString("requestId")
                val args = request.getJSONObject("args")
                val name = args.opt("name") as? String
                val valid = request.opt("surfaceId") == binding.surfaceId && request.opt("catalogId") == RpcCall.CATALOG_ID &&
                    request.opt("name") == "getGreeting" && args.length() == 1 && name != null &&
                    name.trim().isNotEmpty() && name.trim().length <= 200
                val respond = {
                    view.runOnContextThread {
                        if (valid) A2UIBridgeManager.respondToAgentJson(view.instanceId, binding.surfaceId, id,
                            JSONObject.quote("Hello, ${name!!.trim()}!"))
                        else A2UIBridgeManager.failAgentRequestJson(view.instanceId, binding.surfaceId, id,
                            "INVALID_ARGS", "Only getGreeting(name) with a literal name is allowed", null)
                    }
                }
                post {
                    if (rpcBinding == binding && surface === view && isAttachedToWindow && binding.matches(document)) {
                        onRendererRpcHandled?.invoke(requestJson)
                        if (deferRendererRpcResponses) deferredRpcResponses[id] = respond else respond()
                    }
                }
            }
        }
    }

    fun requestRendererGreeting(name: String, timeoutMs: Int = 10000): Boolean {
        val binding = rpcBinding ?: return false
        return rpcReady && rpcModel?.requestRendererGreeting(binding, name, timeoutMs) == true
    }

    fun flushDeferredRpcResponses() {
        deferredRpcResponses.values.toList().forEach { it() }
        deferredRpcResponses.clear()
    }

    private fun rpcControls(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val input = EditText(context).apply {
            hint = "RPC name"; setText("World"); isSingleLine = true
            contentDescription = "RPC name"
        }
        addView(input, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val outgoing = Button(context).apply {
            text = "Renderer → Agent"; contentDescription = "RPC Renderer to Agent"
            isEnabled = false; setOnClickListener { invokeAgentGreeting(input.text.toString()) }
        }
        val incoming = Button(context).apply {
            text = "Agent → Renderer"; contentDescription = "RPC Agent to Renderer"
            isEnabled = false; setOnClickListener { requestRendererGreeting(input.text.toString()) }
        }
        rpcButtons = listOf(outgoing, incoming)
        addView(outgoing); addView(incoming)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("取消" to { rpcBinding?.let { rpcModel?.cancelRpc(it) } },
                "安全重试" to { rpcBinding?.let { rpcModel?.retryRpc(it) } },
                "查询" to { rpcBinding?.let { rpcModel?.queryRpc(it) } }).forEach { (label, action) ->
                addView(Button(context).apply { text = label; textSize = 11f; setOnClickListener { action() } },
                    LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        })
        rpcStatus = TextView(context).apply {
            text = "RPC 实例注册中…"; textSize = 12f; setTextColor(muted)
            contentDescription = "RPC 状态与实例恢复"
            setOnClickListener { if (!rpcReady) { rpcModel?.resync(); recoverRpcRuntime() } }
        }
        addView(rpcStatus)
    }

    init {
        minimumHeight = context.dp(44)
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        background = context.bubble(Color.WHITE, Color.rgb(230, 235, 232))
        addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) onContentResize?.invoke()
        }
    }

    fun bind(card: CardDocument) {
        document = card
        if (isAttachedToWindow) render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        render()
    }

    override fun onDetachedFromWindow() {
        releaseSurface()
        super.onDetachedFromWindow()
    }

    private fun render() {
        val card = document ?: return
        val identity = Triple(card.id, card.surfaceId, card.revision)
        if (renderedIdentity == identity && surface != null) return
        val previous = renderedIdentity
        if (rpcModel == null && surface != null && previous?.first == card.id && previous.second == card.surfaceId && previous.third + 1 == card.revision && card.incrementalEnvelopes.isNotEmpty()) {
            card.incrementalEnvelopes.forEach { surface?.writeStream(it) }
            renderedIdentity = identity
            return
        }
        releaseSurface()
        if (card.replay.isEmpty()) {
            addView(TextView(context).apply {
                text = when (card.status) {
                    "failed" -> "卡片生成失败"
                    "cancelled" -> "已停止生成"
                    "ready" -> "Surface 已删除；已提交状态可恢复"
                    else -> "正在生成卡片…"
                }
                setTextColor(muted)
                textSize = 14f
            })
            return
        }
        val currentGeneration = generation
        val view = A2UISurfaceView(context)
        surface = view
        renderedIdentity = identity
        val rpcEnabled = rpcModel != null && rpcSessionId.isNotEmpty() && card.status == "ready"
        if (rpcEnabled) rpcBinding = RendererBinding(UUID.randomUUID().toString(), rpcSessionId,
            card.id, card.surfaceId, card.revision)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(view, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            if (rpcEnabled) addView(rpcControls())
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        try {
            view.setListener(object : A2UISurfaceViewListener {
                override fun onError(message: String) {
                    post {
                        if (generation == currentGeneration && isAttachedToWindow) {
                            releaseSurface()
                            addView(TextView(context).apply {
                                text = "卡片暂时无法显示，请重新连接恢复"
                                textSize = 14f
                                setTextColor(muted)
                            })
                        }
                    }
                }
                override fun onAction(surfaceId: String, tag: Int, action: String, params: String?) {
                    val clickedCard = document ?: return
                    val clickedIdentity = Triple(clickedCard.id, clickedCard.surfaceId, clickedCard.revision)
                    val event = RendererCardAction(clickedCard.id, clickedCard.revision, surfaceId, tag, action, params)
                    post {
                        if (generation == currentGeneration && isAttachedToWindow && renderedIdentity == clickedIdentity &&
                            document?.id == event.cardId && document?.revision == event.baseCardRevision) {
                            this@A2UICardHostView.onAction?.invoke(event)
                        }
                    }
                }
                override fun onOpenUrl(url: String) {
                    post { if (generation == currentGeneration) onOpenLink?.invoke(url) }
                }
            })
            if (rpcEnabled) A2UISurfaceView.registerCatalogFunction("getGreeting", RpcCall.CATALOG_ID,
                """{"type":"object","description":"Returns a greeting string for the supplied name without side effects.","returnType":"string","allowedCallers":"rendererOrAgent","properties":{"@call":{"const":"getGreeting"},"args":{"type":"object","properties":{"name":{"${'$'}ref":"common_types.json#/${'$'}defs/DynamicString"}},"required":["name"],"unevaluatedProperties":false}},"required":["@call","args"]}""", null)
            view.init("""{"protocolVersion":"1.0","strictV10":true}""")
            card.replay.forEach(view::writeStream)
            rpcBinding?.let { binding ->
                installRpc(view, binding)
                rpcModel?.attachRpcHost(this, binding)
            }
        } catch (_: Exception) {
            releaseSurface()
            addView(TextView(context).apply { text = "卡片初始化失败"; setTextColor(muted) })
        }
    }

    fun releaseSurface() {
        generation++
        rpcBinding?.let { rpcModel?.releaseRpcHost(it) }
        rpcBinding = null
        rpcReady = false
        rpcStatus = null
        rpcButtons = emptyList()
        deferredRpcResponses.clear()
        surface?.rpcEnvelopeOutboundHandler = null
        surface?.destroy()
        surface = null
        renderedIdentity = null
        removeAllViews()
    }
}
