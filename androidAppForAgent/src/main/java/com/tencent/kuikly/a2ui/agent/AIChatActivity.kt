package com.tencent.kuikly.a2ui.agent

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.kuikly.a2ui.agent.data.AgentConnection
import com.tencent.kuikly.a2ui.agent.data.ChatUiState
import com.tencent.kuikly.a2ui.agent.databinding.ActivityAiChatBinding
import com.tencent.kuikly.a2ui.agent.ui.ChatMessageAdapter
import com.tencent.kuikly.a2ui.agent.ui.bubble
import com.tencent.kuikly.a2ui.agent.ui.dp
import com.tencent.kuikly.a2ui.agent.ui.green
import kotlinx.coroutines.launch

class AIChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAiChatBinding
    private lateinit var model: ChatViewModel
    private lateinit var adapter: ChatMessageAdapter
    private lateinit var manager: LinearLayoutManager
    private var followBottom = true
    private var shownState = ChatUiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        followBottom = savedInstanceState?.getBoolean("followBottom", true) ?: true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        model = ViewModelProvider(this)[ChatViewModel::class.java]
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            if (followBottom) scrollBottom()
            insets
        }
        manager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = ChatMessageAdapter(
            onEdit = { model.selectCard(it); binding.input.requestFocus() },
            onResize = { if (followBottom) scrollBottom() },
            onAction = { Toast.makeText(this, "当前服务尚不支持卡片交互", Toast.LENGTH_SHORT).show() },
            onLink = ::openLink
        )
        binding.messages.layoutManager = manager
        binding.messages.adapter = adapter
        binding.messages.itemAnimator = null
        binding.messages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    followBottom = !recyclerView.canScrollVertically(1)
                    binding.jumpBottom.visibility = if (followBottom) View.GONE else View.VISIBLE
                }
            }
        })
        binding.input.background = bubble(Color.rgb(245, 247, 246))
        binding.input.doAfterTextChanged { updateSend() }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        binding.send.setOnClickListener {
            if (shownState.activeRunId != null) model.cancelRun() else send()
        }
        binding.settings.setOnClickListener { confirmLeavingTask { showSettings() } }
        binding.newSession.setOnClickListener { confirmLeavingTask {
            runCatching { model.connect(model.connection, newSession = true) }
                .onFailure { showSettings() }
            followBottom = true
        } }
        binding.offlineDemo.setOnClickListener { model.offlinePreview() }
        binding.loadHistory.setOnClickListener { followBottom = false; model.loadHistory() }
        binding.clearTarget.setOnClickListener { model.selectCard(null) }
        binding.jumpBottom.setOnClickListener { followBottom = true; binding.jumpBottom.visibility = View.GONE; scrollBottom() }
        binding.notice.setOnClickListener {
            if (shownState.uncertainRequest) model.retryPending()
            else if (shownState.offline || model.connection.baseUrl.isBlank()) showSettings()
            else model.resync()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("followBottom", followBottom)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() { super.onStart(); model.foreground(true) }
    override fun onStop() { model.foreground(false); super.onStop() }
    override fun onDestroy() { binding.messages.adapter = null; super.onDestroy() }

    private fun render(state: ChatUiState) {
        val oldFirst = manager.findFirstVisibleItemPosition()
        val oldOffset = manager.findViewByPosition(oldFirst)?.top ?: 0
        val oldCount = adapter.itemCount
        val wasLoading = shownState.loadingHistory
        shownState = state
        binding.connection.text = state.connectionLabel
        binding.notice.text = state.notice
        binding.notice.visibility = if (state.notice == null) View.GONE else View.VISIBLE
        binding.emptyState.visibility = if (state.messages.isEmpty() && state.pendingText == null) View.VISIBLE else View.GONE
        binding.loadHistory.visibility = if (state.canLoadHistory) View.VISIBLE else View.GONE
        binding.loadHistory.isEnabled = !state.loadingHistory && state.connected
        binding.loadHistory.text = if (state.loadingHistory) "加载中…" else "加载更早消息"
        binding.editTarget.visibility = if (state.selectedCardId != null) View.VISIBLE else View.GONE
        binding.editLabel.text = getString(R.string.edit_card_version, state.cards[state.selectedCardId]?.revision ?: 0)
        adapter.submit(state)
        if (wasLoading && adapter.itemCount > oldCount && oldFirst >= 0) {
            manager.scrollToPositionWithOffset(oldFirst + adapter.itemCount - oldCount, oldOffset)
        } else if (followBottom) scrollBottom()
        updateSend()
    }

    private fun updateSend() {
        val running = shownState.activeRunId != null
        binding.send.text = if (running) "停止" else "发送"
        binding.send.isEnabled = if (running) shownState.connected else shownState.connected && !shownState.posting &&
            !shownState.uncertainRequest && !binding.input.text.isNullOrBlank()
        binding.send.background = bubble(if (binding.send.isEnabled) green else Color.rgb(173, 200, 184))
    }

    private fun send() {
        if (model.send(binding.input.text.toString())) {
            binding.input.text.clear()
            followBottom = true
            binding.jumpBottom.visibility = View.GONE
            scrollBottom()
        }
    }

    private fun scrollBottom() {
        binding.messages.post {
            if (!followBottom || adapter.itemCount == 0 || isDestroyed) return@post
            val last = adapter.itemCount - 1
            manager.scrollToPosition(last)
            binding.messages.post {
                if (!followBottom || isDestroyed) return@post
                manager.findViewByPosition(last)?.let {
                    val overflow = it.bottom - (binding.messages.height - binding.messages.paddingBottom)
                    if (overflow > 0) binding.messages.scrollBy(0, overflow)
                }
            }
        }
    }

    private fun confirmLeavingTask(action: () -> Unit) {
        if (shownState.activeRunId == null && !shownState.posting && !shownState.uncertainRequest) action()
        else AlertDialog.Builder(this).setTitle("当前请求尚未结束")
            .setMessage("切换或新建会话不会取消服务端任务。若需取消，请先点击停止。是否继续？")
            .setNegativeButton("返回", null).setPositiveButton("继续") { _, _ -> action() }.show()
    }

    private fun showSettings() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(12)) }
        fun field(label: String, value: String, multiline: Boolean = false, secret: Boolean = false): EditText {
            form.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(green); setPadding(0, dp(10), 0, dp(4)) })
            return EditText(this).apply {
                setText(value)
                textSize = 14f
                inputType = when {
                    secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                if (multiline) { minLines = 5; maxLines = 10 } else setSingleLine(true)
                if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                isSaveEnabled = !secret
                form.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        val config = model.connection
        val base = field("服务地址", config.baseUrl)
        val routes = field("路由 JSON（参数：{sessionId}、{runId}、{cardId}）", config.routesJson.ifBlank { AgentConnection.emptyRoutes() }, multiline = true)
        val headerName = field("本次请求头名称（可选）", config.headerName)
        val headerValue = field("本次请求头值（不保存、不写入构建）", config.headerValue, secret = true)
        form.addView(TextView(this).apply {
            text = "地址和路由可由 local.properties 的 agent.baseUrl、agent.routes 预设。请求头仅本次进程有效。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(12), 0, 0)
        })
        val dialog = AlertDialog.Builder(this).setTitle("连接设置").setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null).setPositiveButton("连接", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = AgentConnection(base.text.toString().trim(), routes.text.toString().trim(),
                    headerName.text.toString().trim(), headerValue.text.toString())
                runCatching { model.connect(updated) }.onSuccess { dialog.dismiss() }
                    .onFailure { base.error = it.message ?: "配置无效" }
            }
        }
        dialog.show()
    }

    private fun openLink(value: String) {
        val uri = Uri.parse(value)
        if (uri.scheme !in listOf("https", "http")) return
        AlertDialog.Builder(this).setTitle("打开外部链接？").setMessage(uri.host.orEmpty())
            .setNegativeButton("取消", null).setPositiveButton("打开") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    .onFailure { Toast.makeText(this, "没有可打开链接的应用", Toast.LENGTH_SHORT).show() }
            }.show()
    }
}
