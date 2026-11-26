package com.tencent.kuikly.a2ui.agent.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tencent.kuikly.a2ui.agent.data.CardDocument
import com.tencent.kuikly.a2ui.agent.data.ChatUiState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ChatMessageAdapter(
    private val onEdit: (String) -> Unit,
    private val onResize: () -> Unit,
    private val onAction: () -> Unit,
    private val onLink: (String) -> Unit
) : RecyclerView.Adapter<ChatMessageAdapter.Holder>() {
    data class Row(val key: String, val user: Boolean, val text: String?, val card: CardDocument?, val status: String, val time: String)
    private var rows = emptyList<Row>()

    fun submit(state: ChatUiState) {
        val next = buildList {
            state.messages.forEach { message ->
                message.parts.forEachIndexed { index, part ->
                    add(Row("${message.id}:$index", message.role == "user", part.text,
                        part.cardId?.let { state.cards[it] }, message.status, message.createdAt))
                }
            }
            if (!state.uncertainRequest) state.pendingText?.let { add(Row("pending", true, it, null, "sending", "")) }
        }
        val old = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = old[oldItemPosition].key == next[newItemPosition].key
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = old[oldItemPosition] == next[newItemPosition]
        })
        rows = next
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setPadding(context.dp(16), context.dp(5), context.dp(16), context.dp(7))
    })
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])
    override fun onViewRecycled(holder: Holder) { holder.release(); super.onViewRecycled(holder) }

    inner class Holder(private val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        private var cardHost: A2UICardHostView? = null
        private var bound: Row? = null
        private val content = LinearLayout(root.context).apply { orientation = LinearLayout.VERTICAL }
        private val meta = TextView(root.context).apply { textSize = 11f; setTextColor(muted); setPadding(0, context.dp(5), 0, 0) }

        init {
            root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun bind(row: Row) {
            root.gravity = if (row.user) Gravity.END else Gravity.START
            val available = root.resources.displayMetrics.widthPixels - root.context.dp(32)
            val width = (available * if (row.card != null) .88f else .76f).toInt()
            if (row.card != null && bound?.card?.id == row.card.id && cardHost != null) {
                cardHost?.bind(row.card)
                cardHost?.layoutParams?.width = width
            } else {
                release()
                content.removeAllViews()
                if (row.card != null) {
                    val host = A2UICardHostView(root.context).apply {
                        onContentResize = onResize
                        onUnsupportedAction = onAction
                        onOpenLink = onLink
                        bind(row.card)
                    }
                    cardHost = host
                    content.addView(host, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
                } else {
                    content.addView(TextView(root.context).apply {
                        text = row.text.orEmpty()
                        textSize = 16f
                        setTextColor(ink)
                        maxWidth = width
                        setTextIsSelectable(true)
                        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
                        background = context.bubble(if (row.user) Color.rgb(181, 235, 172) else Color.WHITE,
                            if (row.user) null else Color.rgb(230, 235, 232))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
            bound = row
            val status = when {
                row.status == "sending" -> "发送中"
                row.status == "uncertain" -> "待确认"
                row.status == "failed" -> "生成失败"
                row.status == "cancelled" -> "已停止"
                row.status == "running" && !row.user -> "生成中"
                else -> ""
            }
            val timestamp = runCatching {
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(parser.parse(row.time)!!)
            }.getOrDefault("")
            val edit = row.card?.editable == true
            meta.text = listOf(timestamp, status, if (edit) "修改此卡片" else "").filter { it.isNotEmpty() }.joinToString("  ·  ")
            meta.visibility = if (meta.text.isEmpty()) View.GONE else View.VISIBLE
            meta.setTextColor(if (edit) green else muted)
            meta.setOnClickListener(if (edit) View.OnClickListener { onEdit(row.card!!.id) } else null)
            meta.contentDescription = if (edit) "修改此卡片" else null
            if (edit) meta.setPadding(0, root.context.dp(8), 0, root.context.dp(8))
        }

        fun release() { cardHost?.releaseSurface(); cardHost = null; bound = null }
    }
}
