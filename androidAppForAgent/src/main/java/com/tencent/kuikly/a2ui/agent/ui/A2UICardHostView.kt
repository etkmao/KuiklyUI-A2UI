package com.tencent.kuikly.a2ui.agent.ui

import android.content.Context
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.TextView
import com.tencent.kuikly.a2ui.agent.data.CardDocument
import com.tencent.kuikly.a2ui.render.android.A2UISurfaceView
import com.tencent.kuikly.a2ui.render.android.A2UISurfaceViewListener

class A2UICardHostView(context: Context) : FrameLayout(context) {
    private var document: CardDocument? = null
    private var surface: A2UISurfaceView? = null
    private var renderedIdentity: Pair<String, Int>? = null
    private var generation = 0
    var onContentResize: (() -> Unit)? = null
    var onUnsupportedAction: (() -> Unit)? = null
    var onOpenLink: ((String) -> Unit)? = null

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
        val identity = card.surfaceId to card.revision
        if (renderedIdentity == identity && surface != null) return
        val previous = renderedIdentity
        if (surface != null && previous?.first == card.surfaceId && previous.second + 1 == card.revision && card.incrementalEnvelopes.isNotEmpty()) {
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
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
                    post { if (generation == currentGeneration) onUnsupportedAction?.invoke() }
                }
                override fun onOpenUrl(url: String) {
                    post { if (generation == currentGeneration) onOpenLink?.invoke(url) }
                }
            })
            view.init("""{"protocolVersion":"1.0","strictV10":true}""")
            card.replay.forEach(view::writeStream)
        } catch (_: Exception) {
            releaseSurface()
            addView(TextView(context).apply { text = "卡片初始化失败"; setTextColor(muted) })
        }
    }

    fun releaseSurface() {
        generation++
        surface?.destroy()
        surface = null
        renderedIdentity = null
        removeAllViews()
    }
}
