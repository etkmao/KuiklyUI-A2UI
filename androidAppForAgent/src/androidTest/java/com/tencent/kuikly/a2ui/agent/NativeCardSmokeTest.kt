package com.tencent.kuikly.a2ui.agent

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.kuikly.a2ui.agent.data.AgentApiClient
import com.tencent.kuikly.a2ui.agent.data.AgentConnection
import com.tencent.kuikly.a2ui.agent.data.CardDocument
import com.tencent.kuikly.a2ui.agent.data.ChatStateStore
import com.tencent.kuikly.a2ui.agent.data.ChatUiState
import com.tencent.kuikly.a2ui.agent.data.RendererCardAction
import com.tencent.kuikly.a2ui.agent.data.UiStateDocument
import com.tencent.kuikly.a2ui.agent.ui.A2UICardHostView
import com.tencent.kuikly.core.render.android.expand.component.KRRichTextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NativeCardSmokeTest {
    private val richTextGetter by lazy {
        KRRichTextView::class.java.getMethod("getText\$com_tencent_kuikly_open_core_render_android_release")
    }

    private fun nativeRichText(view: KRRichTextView): String = richTextGetter.invoke(view) as String

    @Before fun clearSession() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("agent_connection", 0).edit().clear().commit()
    }

    @Test fun previewUsesNativeRendererAndSurvivesRecreation() {
        ActivityScenario.launch(AIChatActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.offlineDemo).performClick() }
            awaitRenderedText(scenario)
            scenario.recreate()
            awaitRenderedText(scenario)
        }
    }

    @Test fun detachedCardReplaysAfterReattach() {
        ActivityScenario.launch(AIChatActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.offlineDemo).performClick() }
            awaitRenderedText(scenario)
            scenario.onActivity { activity ->
                val host = descendants(activity.window.decorView).filterIsInstance<A2UICardHostView>().first()
                val parent = host.parent as ViewGroup
                val index = parent.indexOfChild(host)
                val params = host.layoutParams
                parent.removeView(host)
                parent.addView(host, index, params)
            }
            awaitRenderedText(scenario)
        }
    }

    @Test fun nativeCallbackIncludesRealSourceSurfaceAndLatestInput() {
        val card = formCard()
        val callback = AtomicReference<RendererCardAction?>()
        ActivityScenario.launch(AIChatActivity::class.java).use { scenario ->
            lateinit var host: A2UICardHostView
            scenario.onActivity { activity ->
                host = A2UICardHostView(activity).apply {
                    onAction = { action ->
                        Log.i("A2UIActionDeviceTest", "pureNativeCallbackEnvelope=${action.actionJson}")
                        callback.set(action)
                    }
                    bind(card)
                }
                activity.setContentView(host)
            }
            awaitForm(scenario, host)
            scenario.onActivity {
                val input = descendants(host).filterIsInstance<EditText>().first()
                input.setText("较旧输入")
                input.setText("本地最后输入")
            }
            tapSubmit(scenario, host)
            awaitCallback(callback, scenario)
            val actual = callback.get()!!
            assertNativeAction(actual, card, "本地最后输入")
            callback.set(null)
            scenario.onActivity {
                val parent = host.parent as ViewGroup
                val params = host.layoutParams
                parent.removeView(host)
                parent.addView(host, params)
            }
            awaitForm(scenario, host)
            scenario.onActivity { descendants(host).filterIsInstance<EditText>().first().setText("重新挂载后的输入") }
            tapSubmit(scenario, host)
            awaitCallback(callback, scenario)
            assertNativeAction(callback.get()!!, card, "重新挂载后的输入")
        }
    }

    @Test fun releaseDropsQueuedNativeCallback() {
        val callback = AtomicReference<RendererCardAction?>()
        ActivityScenario.launch(AIChatActivity::class.java).use { scenario ->
            lateinit var host: A2UICardHostView
            scenario.onActivity { activity ->
                host = A2UICardHostView(activity).apply { onAction = callback::set; bind(formCard()) }
                activity.setContentView(host)
            }
            awaitForm(scenario, host)
            scenario.onActivity {
                val label = descendants(host).filterIsInstance<KRRichTextView>().first { nativeRichText(it) == "提交" }
                val labelLocation = IntArray(2).also(label::getLocationOnScreen)
                val hostLocation = IntArray(2).also(host::getLocationOnScreen)
                val x = (labelLocation[0] - hostLocation[0] + label.width / 2).toFloat()
                val y = (labelLocation[1] - hostLocation[1] + label.height / 2).toFloat()
                val now = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                    val event = MotionEvent.obtain(now, now + index * 40, action, x, y, 0)
                    try { host.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                host.releaseSurface()
            }
            Thread.sleep(300)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertNull("Released generation must not forward a queued native callback", callback.get())
        }
    }

    @Test fun realNativeActionRoundTripAgainstConfiguredHttpSseService(): Unit = runBlocking {
        val baseUrl = InstrumentationRegistry.getArguments().getString("actionServiceBaseUrl").orEmpty()
        assumeTrue("Only this integration test requires -e actionServiceBaseUrl http://127.0.0.1:3102 and a local fixture-only service", baseUrl.isNotBlank())
        val uri = java.net.URI(baseUrl)
        require(uri.scheme == "http" && uri.host in listOf("127.0.0.1", "localhost", "[::1]", "::1")) {
            "Action integration must use a local fixture-only HTTP service, never a remote model endpoint"
        }
        val routes = JSONObject().put("sessions", "/v1/sessions")
            .put("runs", "/v1/sessions/{sessionId}/runs")
            .put("events", "/v1/sessions/{sessionId}/events")
            .put("snapshot", "/v1/sessions/{sessionId}/snapshot")
            .put("card", "/v1/sessions/{sessionId}/cards/{cardId}")
            .put("messages", "/v1/sessions/{sessionId}/messages")
            .put("cancel", "/v1/runs/{runId}/cancel")
            .put("actions", "/v1/sessions/{sessionId}/actions").toString()
        val connection = AgentConnection(baseUrl, routes).also { it.validate(true) }
        val api = AgentApiClient(connection)
        try {
            ActivityScenario.launch(AIChatActivity::class.java).use { scenario ->
                lateinit var model: ChatViewModel
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[ChatViewModel::class.java]
                    model.connect(connection, newSession = true)
                }
                awaitState(model, "normal createSession and SSE connection") { it.connected }
                scenario.onActivity { assertTrue("First form must use normal create_card submission", model.send("第一张表单")) }
                val first = awaitState(model, "first service-generated form") {
                    it.cards.size == 1 && it.cards.values.all { card -> card.status == "ready" } && !it.posting && it.activeRunId == null
                }.cards.values.single()
                scenario.onActivity { assertTrue("Second form must use normal create_card submission", model.send("第二张表单")) }
                val initial = awaitState(model, "two service-generated forms") {
                    it.cards.size == 2 && it.cards.values.all { card -> card.status == "ready" } && !it.posting && it.activeRunId == null
                }
                val second = initial.cards.values.single { it.id != first.id }
                assertNotEquals(first.surfaceId, second.surfaceId)
                assertEquals("", cardModel(first).getJSONObject("form").getString("value"))
                assertEquals("", cardModel(second).getJSONObject("form").getString("value"))
                awaitForm(scenario, awaitCardHost(scenario, model, second.id))
                val sessionId = InstrumentationRegistry.getInstrumentation().targetContext
                    .getSharedPreferences("agent_connection", 0).getString("session", "").orEmpty()
                assertTrue("VM must have created a real session", sessionId.isNotBlank())
                val baseline = api.snapshot(sessionId)
                val events = Collections.synchronizedList(mutableListOf<JSONObject>())
                val opened = CompletableDeferred<Unit>()
                val streamFailure = AtomicReference<Throwable?>()
                val observer = launch(Dispatchers.IO) {
                    try {
                        api.events(sessionId, baseline.getLong("lastEventId")) { opened.complete(Unit) }.collect { event ->
                            events.add(JSONObject(event.toString()))
                            Log.i("A2UIActionDeviceTest", "SSE $event")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Throwable) { streamFailure.set(e); opened.completeExceptionally(e) }
                }
                try {
                    withTimeout(10000) { opened.await() }
                    var previous = first
                    listOf("本地最后输入", "第二次点击的最新输入").forEachIndexed { index, latest ->
                        val host = awaitCardHost(scenario, model, first.id)
                        awaitForm(scenario, host)
                        val capture = observeNextRealAction(scenario, host, model)
                        scenario.onActivity { descendants(host).filterIsInstance<EditText>().first().setText("第${index + 1}次较旧输入") }
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        scenario.onActivity { descendants(host).filterIsInstance<EditText>().first().setText(latest) }
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        tapSubmit(scenario, host)
                        val actual = awaitCapturedAction(capture)
                        assertNativeAction(actual.callback, previous, latest)
                        assertJsonEquivalent(actual.callback.requestBody(previous), actual.body, "realAction.requestBody")
                        val updatedState = awaitState(model, "real Action SSE revision ${previous.revision + 1}") {
                            it.cards[first.id]?.revision == previous.revision + 1 && !it.posting && it.activeRunId == null
                        }
                        val updated = updatedState.cards.getValue(first.id)
                        assertEquals(first.surfaceId, updated.surfaceId)
                        assertEquals(second, updatedState.cards.getValue(second.id))
                        assertEquals(latest, cardModel(updated).getJSONObject("form").getString("value"))
                        assertEquals("已接收：$latest", cardModel(updated).getJSONObject("result").getString("message"))
                        val actionEvents = awaitActionEvents(events, updated, streamFailure)
                        val runId = assertActionSseBatch(actionEvents, previous, updated)
                        val beforeRetry = api.snapshot(sessionId)
                        val response = api.submitAction(sessionId, actual.body, actual.requestId)
                        assertEquals("update_card", response.getString("operation"))
                        assertEquals("completed", response.getString("status"))
                        assertEquals(first.id, response.getString("cardId"))
                        assertEquals(runId, response.getString("runId"))
                        val repeated = api.submitAction(sessionId, actual.body, actual.requestId)
                        assertJsonEquivalent(response, repeated, "sameKeyReplay.response")
                        assertJsonEquivalent(beforeRetry, api.snapshot(sessionId), "sameKeyReplay.snapshotMustNotChange")
                        val evidence = JSONObject().put("requestId", actual.requestId).put("httpStatus", 202)
                            .put("responseSource", "idempotent-replay").put("response", response)
                            .put("callbackEnvelope", JSONObject(actual.callback.actionJson)).put("requestBody", actual.body)
                            .put("baseCardRevision", previous.revision).put("committedCardRevision", updated.revision)
                            .put("result", cardModel(updated)).put("secondCardRevision", second.revision)
                        Log.i("A2UIActionDeviceTest", "actionResult=$evidence")
                        awaitNativeResult(scenario, model, first.id, "已接收：$latest")
                        previous = updated
                    }
                    assertEquals(first.revision + 2, previous.revision)
                    val snapshot = api.snapshot(sessionId)
                    val restored = ChatStateStore().also { it.snapshot(snapshot) }
                    assertEquals(model.state.value.messages, restored.messages)
                    assertCardSnapshotsMatch(model.state.value.cards, restored.cards)
                    scenario.onActivity { model.resync() }
                    awaitState(model, "VM snapshot restoration") { it.connected && !it.posting && it.activeRunId == null }
                    assertCardSnapshotsMatch(restored.cards, model.state.value.cards)
                    scenario.recreate()
                    awaitState(model, "Activity recreation and SSE reconnection") { it.connected }
                    awaitNativeResult(scenario, model, first.id, "已接收：第二次点击的最新输入")
                    assertReplayEquivalent(second.replay, model.state.value.cards.getValue(second.id).replay, "secondCard.replayMessages")
                    Log.i("A2UIActionDeviceTest", "snapshot restored sessionId=$sessionId cards=${restored.cards.mapValues { it.value.revision }} lastEventId=${restored.cursor}")
                } finally { observer.cancelAndJoin() }
            }
        } finally { api.close() }
    }

    private fun assertNativeAction(actual: RendererCardAction, card: CardDocument, value: String) {
        assertEquals(card.id, actual.cardId)
        assertEquals(card.revision, actual.baseCardRevision)
        assertEquals(card.surfaceId, actual.surfaceId)
        assertTrue(actual.tag > 0)
        assertNull(actual.params)
        val message = JSONObject(actual.actionJson)
        assertEquals("1.0", message.getString("version"))
        assertFalse("Legacy event-only callback is not a v1 Action", message.has("event"))
        val action = message.getJSONObject("action")
        assertEquals("submit_form", action.getString("name"))
        assertEquals(card.surfaceId, action.getString("surfaceId"))
        assertEquals("submit", action.getString("sourceComponentId"))
        assertTrue(action.getString("timestamp").matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")))
        assertEquals(value, action.getJSONObject("context").getString("value"))
        assertEquals(1, action.getJSONObject("context").length())
        val body = actual.requestBody(card)
        assertEquals(card.id, body.getString("cardId"))
        assertEquals(card.revision, body.getInt("baseCardRevision"))
        assertEquals(value, body.getJSONObject("message").getJSONObject("action").getJSONObject("context").getString("value"))
        Log.i("A2UIActionDeviceTest", "native callback card=${card.id} revision=${card.revision} tag=${actual.tag} message=$message")
    }

    private data class CapturedAction(val callback: RendererCardAction, val requestId: String, val body: JSONObject)

    private fun observeNextRealAction(
        scenario: ActivityScenario<AIChatActivity>, host: A2UICardHostView, model: ChatViewModel
    ): AtomicReference<Result<CapturedAction>?> {
        val captured = AtomicReference<Result<CapturedAction>?>()
        scenario.onActivity {
            val delegate = host.onAction ?: error("The real chat adapter must wire its host Action to the VM")
            host.onAction = { action ->
                Log.i("A2UIActionDeviceTest", "callbackEnvelope=${action.actionJson}")
                host.onAction = delegate
                captured.set(runCatching {
                    delegate(action)
                    val pendingField = ChatViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }
                    val pending = pendingField.get(model) ?: error("Real native Action was rejected: ${model.state.value.notice}")
                    val key = pending.javaClass.getDeclaredField("key").apply { isAccessible = true }.get(pending) as String
                    val body = pending.javaClass.getDeclaredField("body").apply { isAccessible = true }.get(pending) as JSONObject
                    val isAction = pending.javaClass.getDeclaredField("action").apply { isAccessible = true }.getBoolean(pending)
                    assertTrue("The original host delegate must submit to the Action API", isAction)
                    assertTrue(key.isNotBlank())
                    Log.i("A2UIActionDeviceTest", "requestId(Idempotency-Key)=$key body=$body")
                    CapturedAction(action, key, JSONObject(body.toString()))
                })
            }
        }
        return captured
    }

    private fun awaitCapturedAction(captured: AtomicReference<Result<CapturedAction>?>): CapturedAction {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (captured.get() == null && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
        return captured.get()?.getOrThrow() ?: error("A real native touch must invoke the existing host/VM Action delegate")
    }

    private fun awaitState(model: ChatViewModel, description: String, predicate: (ChatUiState) -> Boolean): ChatUiState {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            val state = model.state.value
            if (predicate(state)) return state
            Thread.sleep(50)
        }
        error("Timed out awaiting $description: ${model.state.value}")
    }

    private fun awaitCardHost(scenario: ActivityScenario<AIChatActivity>, model: ChatViewModel, cardId: String): A2UICardHostView {
        val deadline = SystemClock.uptimeMillis() + 10000
        var found: A2UICardHostView? = null
        while (found == null && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val position = model.state.value.messages.flatMap { it.parts }.indexOfLast { it.cardId == cardId }
                assertTrue("The target must originate from a real assistant card message", position >= 0)
                val recycler = activity.findViewById<RecyclerView>(R.id.messages)
                (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
                found = recycler.findViewHolderForAdapterPosition(position)?.itemView?.let { row ->
                    descendants(row).filterIsInstance<A2UICardHostView>().firstOrNull { it.isAttachedToWindow && it.height > 0 }
                }
            }
            if (found == null) Thread.sleep(100)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        if (found == null) dumpNativeUi(scenario, "awaitCardHost timed out for $cardId")
        return found ?: error("The real chat RecyclerView must render card $cardId")
    }

    private fun awaitNativeResult(scenario: ActivityScenario<AIChatActivity>, model: ChatViewModel, cardId: String, expected: String) {
        val host = awaitCardHost(scenario, model, cardId)
        val deadline = SystemClock.uptimeMillis() + 10000
        var visible = false
        while (!visible && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity {
                val committedValue = cardModel(model.state.value.cards.getValue(cardId)).getJSONObject("form").getString("value")
                visible = descendants(host).filterIsInstance<KRRichTextView>().any { nativeRichText(it) == expected && it.height > 0 } &&
                    descendants(host).filterIsInstance<EditText>().any { it.text.toString() == committedValue && it.height > 0 }
            }
            if (!visible) Thread.sleep(100)
        }
        if (!visible) dumpNativeUi(scenario, "awaitNativeResult timed out for $cardId")
        assertTrue("Committed SSE result and form value must render as native Text/TextField: $expected", visible)
    }

    private fun awaitActionEvents(
        events: MutableList<JSONObject>, card: CardDocument, failure: AtomicReference<Throwable?>
    ): List<JSONObject> {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            failure.get()?.let { throw AssertionError("Real observer SSE failed", it) }
            val copy = synchronized(events) { events.toList() }
            val batch = copy.filter { it.getString("cardId") == card.id && it.getInt("cardRevision") == card.revision }
            if (batch.any { it.getString("type") == "run.completed" }) return batch
            Thread.sleep(50)
        }
        error("No real SSE run.completed for card=${card.id} revision=${card.revision}")
    }

    private fun assertActionSseBatch(events: List<JSONObject>, before: CardDocument, after: CardDocument): String {
        val batch = events.filter { it.getString("type") == "a2ui.message" }
        assertEquals("Action must commit form/value and result/message as one two-message revision", 2, batch.size)
        val runId = batch.first().getString("runId")
        batch.forEachIndexed { index, event ->
            assertEquals(runId, event.getString("runId"))
            assertEquals(before.id, event.getString("cardId"))
            assertEquals(before.surfaceId, event.getString("surfaceId"))
            assertEquals(before.revision, event.getInt("baseCardRevision"))
            assertEquals(after.revision, event.getInt("cardRevision"))
            assertEquals(index, event.getInt("messageIndex"))
            assertEquals(2, event.getInt("messageCount"))
            assertEquals("1.0", event.getJSONObject("payload").getString("version"))
        }
        assertEquals(batch[0].getLong("eventId") + 1, batch[1].getLong("eventId"))
        assertTrue(events.any { it.getString("type") == "run.completed" && it.getString("runId") == runId })
        val replayed = UiStateDocument.apply(UiStateDocument.restore(before.replay), batch.map { it.getJSONObject("payload") })
        assertReplayEquivalent(after.replay, UiStateDocument.replay(replayed), "actionSse.replayMessages")
        return runId
    }

    private fun cardModel(card: CardDocument): JSONObject = UiStateDocument.restore(card.replay)
        .getJSONObject("surfaces").getJSONObject(card.surfaceId).getJSONObject("dataModel")

    private fun assertJsonEquivalent(expected: Any?, actual: Any?, path: String) {
        when {
            expected is JSONObject -> {
                assertTrue("$path must remain a JSON object", actual is JSONObject)
                val objectValue = actual as JSONObject
                val keys = expected.keys().asSequence().toSet()
                assertEquals("$path must preserve every object field", keys, objectValue.keys().asSequence().toSet())
                keys.forEach { key -> assertJsonEquivalent(expected.get(key), objectValue.get(key), "$path.$key") }
            }
            expected is org.json.JSONArray -> {
                assertTrue("$path must remain a JSON array", actual is org.json.JSONArray)
                val arrayValue = actual as org.json.JSONArray
                assertEquals("$path must preserve array length", expected.length(), arrayValue.length())
                for (index in 0 until expected.length()) assertJsonEquivalent(expected.get(index), arrayValue.get(index), "$path[$index]")
            }
            expected is Number && actual is Number -> assertEquals("$path must preserve the JSON number", 0,
                java.math.BigDecimal(expected.toString()).compareTo(java.math.BigDecimal(actual.toString())))
            expected == null || expected === JSONObject.NULL -> assertTrue("$path must remain JSON null", actual == null || actual === JSONObject.NULL)
            else -> assertEquals("$path must preserve the JSON value", expected, actual)
        }
    }

    private fun assertReplayEquivalent(expected: List<String>, actual: List<String>, path: String) {
        assertEquals("$path must preserve envelope count and order", expected.size, actual.size)
        expected.forEachIndexed { index, envelope ->
            assertJsonEquivalent(JSONObject(envelope), JSONObject(actual[index]), "$path[$index]")
        }
    }

    private fun assertCardSnapshotsMatch(expected: Map<String, CardDocument>, actual: Map<String, CardDocument>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (id, card) ->
            val restored = actual.getValue(id)
            assertEquals(card.id, restored.id)
            assertEquals(card.surfaceId, restored.surfaceId)
            assertEquals(card.revision, restored.revision)
            assertEquals(card.status, restored.status)
            assertEquals(card.editable, restored.editable)
            assertReplayEquivalent(card.replay, restored.replay, "cards.$id.replayMessages")
        }
    }

    private fun formCard(): CardDocument {
        val messages = InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/action-form.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONArray("messages") }
        return CardDocument("native-form", messages.getJSONObject(0).getJSONObject("createSurface").getString("surfaceId"),
            3, "ready", true, (0 until messages.length()).map { messages.getJSONObject(it).toString() })
    }
    private fun awaitForm(scenario: ActivityScenario<AIChatActivity>, host: A2UICardHostView) {
        val deadline = System.currentTimeMillis() + 10000
        var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            scenario.onActivity {
                ready = descendants(host).filterIsInstance<EditText>().any { it.height > 0 } &&
                    descendants(host).filterIsInstance<KRRichTextView>().any { nativeRichText(it) == "提交" && it.height > 0 }
            }
            if (!ready) Thread.sleep(100)
        }
        if (!ready) dumpNativeUi(scenario, "awaitForm timed out")
        assertTrue("Form must contain native TextField and submit label", ready)
    }
    private fun awaitSubmitBounds(scenario: ActivityScenario<AIChatActivity>, host: A2UICardHostView): android.graphics.Rect {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10000
        var previous: android.graphics.Rect? = null
        var stableFrames = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val nextFrame = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { host.postOnAnimation { nextFrame.countDown() } }
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0 || !nextFrame.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)) break
            instrumentation.waitForIdleSync()
            var current: android.graphics.Rect? = null
            scenario.onActivity {
                val label = descendants(host).filterIsInstance<KRRichTextView>().firstOrNull {
                    nativeRichText(it) == "提交" && it.width > 0 && it.height > 0
                }
                val visible = android.graphics.Rect()
                if (label != null && host.isAttachedToWindow && host.hasWindowFocus() && !host.isLayoutRequested &&
                    label.isShown && !label.isLayoutRequested && label.getGlobalVisibleRect(visible) &&
                    visible.width() == label.width && visible.height() == label.height) {
                    val location = IntArray(2).also(label::getLocationOnScreen)
                    current = android.graphics.Rect(location[0], location[1], location[0] + label.width, location[1] + label.height)
                }
            }
            stableFrames = if (current != null && current == previous) stableFrames + 1 else 0
            if (current != null && stableFrames >= 2) return current!!
            previous = current
        }
        dumpNativeUi(scenario, "submit native label did not reach stable visible layout before the single touch")
        error("Submit must preserve real text, measured size and stable screen bounds before touch")
    }

    private fun tapSubmit(scenario: ActivityScenario<AIChatActivity>, host: A2UICardHostView) {
        val bounds = awaitSubmitBounds(scenario, host)
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        Log.i("A2UIActionDeviceTest", "singleNativeTouch x=$x y=$y labelBounds=$bounds")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(now, now + index * 40, action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
    }
    private fun awaitCallback(callback: AtomicReference<RendererCardAction?>, scenario: ActivityScenario<AIChatActivity>) {
        val deadline = System.currentTimeMillis() + 10000
        while (callback.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (callback.get() == null) dumpNativeUi(scenario, "single native touch produced no callback")
        assertNotNull("A real native button touch must reach the host callback", callback.get())
    }

    private fun awaitRenderedText(scenario: ActivityScenario<AIChatActivity>) {
        var rendered = false
        val deadline = System.currentTimeMillis() + 10000
        while (!rendered && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val host = descendants(activity.window.decorView).filterIsInstance<A2UICardHostView>().firstOrNull()
                rendered = host != null && descendants(host).filterIsInstance<KRRichTextView>().any {
                    nativeRichText(it).contains("这是一张由 Kuikly A2UI 原生渲染的卡片") && it.height > 0
                }
            }
            if (!rendered) Thread.sleep(100)
        }
        if (!rendered) dumpNativeUi(scenario, "awaitRenderedText timed out")
        assertTrue("A2UI Text must produce a measured native text view", rendered)
    }

    private fun dumpNativeUi(scenario: ActivityScenario<AIChatActivity>, reason: String) {
        scenario.onActivity { activity ->
            val keyguard = activity.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            val power = activity.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            Log.i("A2UIActionDeviceTest", "uiDiagnostic reason=$reason lifecycle=${activity.lifecycle.currentState} windowFocus=${activity.window.decorView.hasWindowFocus()} keyguardLocked=${keyguard.isKeyguardLocked} deviceLocked=${keyguard.isDeviceLocked} interactive=${power.isInteractive}")
            fun field(instance: Any, name: String): Any? = runCatching {
                instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
            fun dump(view: View, depth: Int) {
                val id = if (view.id == View.NO_ID) "NO_ID" else runCatching {
                    view.resources.getResourceName(view.id)
                }.getOrElse { view.id.toString() }
                val node = JSONObject().put("depth", depth).put("class", view.javaClass.name).put("id", id)
                    .put("width", view.width).put("height", view.height)
                    .put("measuredWidth", view.measuredWidth).put("measuredHeight", view.measuredHeight)
                    .put("left", view.left).put("top", view.top).put("right", view.right).put("bottom", view.bottom)
                    .put("visibility", view.visibility).put("attached", view.isAttachedToWindow)
                    .put("shown", view.isShown).put("windowVisibility", view.windowVisibility)
                    .put("windowFocus", view.hasWindowFocus()).put("alpha", view.alpha.toDouble())
                if (view is TextView) node.put("text", view.text.toString())
                if (view is KRRichTextView) node.put("text", nativeRichText(view))
                if (view is ViewGroup) node.put("childCount", view.childCount)
                if (view is A2UICardHostView) {
                    val card = field(view, "document") as? CardDocument
                    node.put("cardId", card?.id).put("surfaceId", card?.surfaceId).put("revision", card?.revision)
                        .put("replayCount", card?.replay?.size).put("generation", field(view, "generation"))
                        .put("renderedIdentity", field(view, "renderedIdentity")?.toString())
                    val surface = field(view, "surface")
                    node.put("rendererClass", surface?.javaClass?.name)
                    if (surface != null) node.put("rendererInitialized", field(surface, "isInitialized"))
                        .put("coreEntryPresent", field(surface, "coreEntry") != null)
                        .put("renderLayerPresent", field(surface, "renderLayerHandler") != null)
                }
                Log.i("A2UIActionDeviceTest", "uiNode=$node")
                if (view is ViewGroup) for (index in 0 until view.childCount) dump(view.getChildAt(index), depth + 1)
            }
            dump(activity.window.decorView, 0)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}

@RunWith(AndroidJUnit4::class)
class RpcSmokeTest {
    @Before fun clearRpcSession() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("agent_connection", 0).edit().clear().commit()
    }

    private fun connection(): AgentConnection {
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("rpcServiceBaseUrl") ?: args.getString("actionServiceBaseUrl") ?: "http://127.0.0.1:3102"
        val routes = JSONObject(AgentConnection.emptyRoutes()).apply {
            put("sessions", "/v1/sessions"); put("runs", "/v1/sessions/{sessionId}/runs")
            put("events", "/v1/sessions/{sessionId}/events"); put("snapshot", "/v1/sessions/{sessionId}/snapshot")
            put("card", "/v1/sessions/{sessionId}/cards/{cardId}"); put("messages", "/v1/sessions/{sessionId}/messages")
            put("cancel", "/v1/runs/{runId}/cancel")
        }
        return AgentConnection(base, routes.toString())
    }

    private fun awaitState(scenario: ActivityScenario<AIChatActivity>, predicate: (ChatUiState) -> Boolean): ChatUiState {
        val deadline = SystemClock.elapsedRealtime() + 25000
        var state = ChatUiState()
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { state = ViewModelProvider(it)[ChatViewModel::class.java].state.value }
            if (predicate(state)) return state
            Thread.sleep(100)
        }
        fail("RPC smoke timed out: connected=${state.connected}, notice=${state.notice}, rpc=${state.rpcNotice}, calls=${state.rpcCalls}")
        return state
    }

    private fun awaitHost(scenario: ActivityScenario<AIChatActivity>, host: A2UICardHostView) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { ready = host.rpcReady && host.isAttachedToWindow && host.width > 0 &&
                descendants(host).any { view -> view is KRRichTextView } }
            if (ready) {
                scenario.onActivity { Log.i("A2UIRpcDeviceTest", JSONObject().put("stage", "host.ready")
                    .put("host", host.rpcDiagnostics()).toString()) }
                return
            }
            Thread.sleep(100)
        }
        var diagnostics = JSONObject()
        scenario.onActivity { diagnostics = host.rpcDiagnostics() }
        fail("Real A2UISurfaceView RPC host not registered/rendered: $diagnostics")
    }

    private fun awaitSdkCompletion(scenario: ActivityScenario<AIChatActivity>, host: A2UICardHostView,
        completion: AtomicReference<String?>, outbound: AtomicReference<String?>): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 12000
        while (completion.get() == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        var diagnostics = JSONObject()
        scenario.onActivity { diagnostics = host.rpcDiagnostics() }
        val response = completion.get()
        Log.i("A2UIRpcDeviceTest", JSONObject().put("stage", "sdk.completion.observed").put("host", diagnostics)
            .put("completion", response?.let(::JSONObject)).put("outbound", outbound.get()?.let(::JSONObject)).toString())
        assertNotNull("SDK completion absent; host=$diagnostics outbound=${outbound.get()}", response)
        val parsed = JSONObject(response!!)
        assertFalse("SDK failed locally: response=$parsed host=$diagnostics outbound=${outbound.get()}", parsed.has("error"))
        return parsed
    }

    private fun logRpcCall(call: com.tencent.kuikly.a2ui.agent.data.RpcCall) {
        Log.i("A2UIRpcDeviceTest", JSONObject().put("stage", "rpc.call.record")
            .put("functionCallId", call.functionCallId).put("rendererId", call.rendererId).put("sessionId", call.sessionId)
            .put("cardId", call.cardId).put("surfaceId", call.surfaceId).put("baseCardRevision", call.baseCardRevision)
            .put("direction", call.direction).put("status", call.status).put("request", JSONObject(call.request))
            .put("response", call.response?.let(::JSONObject)).toString())
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    @Test fun realHostBidirectionalRpcLeavesTwoCardsUnchangedAndSupportsRecoveryCancelTimeout(): Unit = runBlocking {
        val config = connection()
        val api = AgentApiClient(config)
        try {
            ActivityScenario.launch(AIChatActivity::class.java).use { scenario ->
                scenario.onActivity { ViewModelProvider(it)[ChatViewModel::class.java].connect(config, true) }
                awaitState(scenario) { it.connected && it.sessionId.isNotEmpty() }
                scenario.onActivity { assertTrue(ViewModelProvider(it)[ChatViewModel::class.java].send("Create a simple greeting card")) }
                awaitState(scenario) { it.cards.values.count { card -> card.status == "ready" } == 1 && it.activeRunId == null && !it.posting }
                scenario.onActivity { assertTrue(ViewModelProvider(it)[ChatViewModel::class.java].send("Create a second simple greeting card")) }
                val initial = awaitState(scenario) { it.cards.values.count { card -> card.status == "ready" } == 2 && it.activeRunId == null && !it.posting }
                val before = api.snapshot(initial.sessionId)
                val firstCard = initial.cards.values.first(); val secondCard = initial.cards.values.last()
                lateinit var first: A2UICardHostView
                lateinit var second: A2UICardHostView
                fun mountHosts() {
                    scenario.onActivity { activity ->
                        val model = ViewModelProvider(activity)[ChatViewModel::class.java]
                        val root = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
                        first = A2UICardHostView(activity).apply { connectRpc(model, initial.sessionId); bind(firstCard) }
                        second = A2UICardHostView(activity).apply { connectRpc(model, initial.sessionId); bind(secondCard) }
                        root.addView(first); root.addView(second)
                        activity.setContentView(root)
                    }
                    awaitHost(scenario, first); awaitHost(scenario, second)
                }
                mountHosts()
                val completion = AtomicReference<String?>()
                val outbound = AtomicReference<String?>()
                val handled = AtomicReference<String?>()
                val otherHandled = AtomicReference<String?>()
                scenario.onActivity {
                    first.onRpcCompletion = { response ->
                        completion.set(response)
                        Log.i("A2UIRpcDeviceTest", JSONObject().put("stage", "sdk.completion").put("host", first.rpcDiagnostics())
                            .put("message", JSONObject(response)).toString())
                    }
                    first.onRpcEnvelopeOutbound = { envelope ->
                        outbound.set(envelope)
                        Log.i("A2UIRpcDeviceTest", JSONObject().put("stage", "sdk.envelope.outbound").put("host", first.rpcDiagnostics())
                            .put("message", JSONObject(envelope)).toString())
                    }
                    first.onRpcInvocationStarted = { requestId -> Log.i("A2UIRpcDeviceTest", JSONObject().put("stage", "sdk.invoke.return")
                        .put("functionCallId", requestId).put("host", first.rpcDiagnostics()).toString()) }
                    first.onRendererRpcHandled = { request ->
                        handled.set(request)
                        Log.i("A2UIRpcDeviceTest", JSONObject().put("stage", "sdk.renderer.handler").put("host", first.rpcDiagnostics())
                            .put("message", JSONObject(request)).toString())
                    }
                    second.onRendererRpcHandled = otherHandled::set
                    descendants(first).filterIsInstance<EditText>().first { input -> input.contentDescription == "RPC name" }.setText("Alice")
                    descendants(first).first { view -> view.contentDescription == "RPC Renderer to Agent" }.performClick()
                }
                assertEquals("Hello, Alice!", awaitSdkCompletion(scenario, first, completion, outbound).getString("result"))
                val outgoing = awaitState(scenario) { state -> state.rpcCalls.values.any { call -> call.cardId == firstCard.id && call.direction == "renderer_to_agent" && call.status == "completed" } }
                outgoing.rpcCalls.values.forEach(::logRpcCall)
                val outboundCall = outgoing.rpcCalls.values.last { it.cardId == firstCard.id && it.direction == "renderer_to_agent" }
                val wireRequest = JSONObject(outboundCall.request).getJSONObject("callAgentFunction")
                assertEquals("getGreeting", wireRequest.getJSONObject("callFunction").getString("@call"))
                assertEquals(com.tencent.kuikly.a2ui.agent.data.RpcCall.CATALOG_ID, wireRequest.getJSONObject("callFunction").getString("catalogId"))
                scenario.onActivity { descendants(first).first { view -> view.contentDescription == "RPC Agent to Renderer" }.performClick() }
                val incoming = awaitState(scenario) { state -> state.rpcCalls.values.any { call -> call.cardId == firstCard.id && call.direction == "agent_to_renderer" && call.status == "completed" } }
                val incomingCall = incoming.rpcCalls.values.last { it.cardId == firstCard.id && it.direction == "agent_to_renderer" }
                logRpcCall(incomingCall)
                assertEquals("Hello, Alice!", JSONObject(incomingCall.response!!).getJSONObject("rendererFunctionResponse").getString("value"))
                assertEquals(incomingCall.functionCallId, JSONObject(handled.get()!!).getString("requestId"))
                assertEquals(com.tencent.kuikly.a2ui.agent.data.RpcCall.CATALOG_ID, JSONObject(handled.get()!!).getString("catalogId"))
                assertNull("RPC must not reach another bound card", otherHandled.get())
                scenario.onActivity { assertTrue(second.requestRendererGreeting("Bob")) }
                val both = awaitState(scenario) { state -> state.rpcCalls.values.any { call -> call.cardId == secondCard.id && call.status == "completed" } }
                assertEquals("Hello, Bob!", JSONObject(both.rpcCalls.values.last { it.cardId == secondCard.id }.response!!)
                    .getJSONObject("rendererFunctionResponse").getString("value"))
                val originalBinding = first.rpcBinding!!
                scenario.onActivity {
                    val parent = first.parent as ViewGroup; val params = first.layoutParams; val index = parent.indexOfChild(first)
                    parent.removeView(first); parent.addView(first, index, params)
                }
                awaitHost(scenario, first)
                assertNotEquals(originalBinding.rendererId, first.rpcBinding!!.rendererId)
                handled.set(null)
                scenario.onActivity { first.onRendererRpcHandled = handled::set; first.deferRendererRpcResponses = true; assertTrue(first.requestRendererGreeting("Cancel")) }
                val pending = awaitState(scenario) { state -> state.rpcCalls.values.any { call -> call.rendererId == first.rpcBinding!!.rendererId && !call.terminal } }
                val cancelId = pending.rpcCalls.values.last { !it.terminal }.functionCallId
                scenario.onActivity { ViewModelProvider(it)[ChatViewModel::class.java].cancelRpc(first.rpcBinding!!) }
                awaitState(scenario) { it.rpcCalls[cancelId]?.status == "cancelled" }
                scenario.onActivity { first.flushDeferredRpcResponses() }
                assertEquals("cancelled", api.rpcCall(initial.sessionId, cancelId, first.rpcBinding!!.rendererId).status)
                scenario.onActivity { assertTrue(first.requestRendererGreeting("Timeout", 300)) }
                val timed = awaitState(scenario) { state -> state.rpcCalls.values.any { call -> call.rendererId == first.rpcBinding!!.rendererId && call.status == "timed_out" } }
                assertTrue(timed.rpcCalls.values.any { it.status == "timed_out" })
                scenario.onActivity { first.flushDeferredRpcResponses(); assertTrue(first.requestRendererGreeting("DeadInstance")) }
                val deadBinding = first.rpcBinding!!
                val deadPending = awaitState(scenario) { state -> state.rpcCalls.values.any { call -> call.rendererId == deadBinding.rendererId && !call.terminal } }
                val deadId = deadPending.rpcCalls.values.last { it.rendererId == deadBinding.rendererId && !it.terminal }.functionCallId
                scenario.onActivity { first.releaseSurface(); first.flushDeferredRpcResponses() }
                awaitState(scenario) { it.rpcCalls[deadId]?.status == "cancelled" }
                assertEquals("cancelled", api.rpcCall(initial.sessionId, deadId, deadBinding.rendererId).status)
                scenario.recreate()
                awaitState(scenario) { it.connected }
                mountHosts()
                completion.set(null)
                scenario.onActivity { first.onRpcCompletion = completion::set; assertTrue(first.invokeAgentGreeting("Recovered")) }
                awaitState(scenario) { state -> state.rpcCalls.values.any { call -> call.rendererId == first.rpcBinding!!.rendererId && call.status == "completed" } }
                val after = api.snapshot(initial.sessionId)
                assertEquals(before.getJSONArray("cards").toString(), after.getJSONArray("cards").toString())
                assertEquals(before.getJSONArray("messages").toString(), after.getJSONArray("messages").toString())
                assertEquals(initial.cards.mapValues { it.value.revision }, timed.cards.mapValues { it.value.revision })
                assertEquals(initial.cards.mapValues { it.value.replay }, timed.cards.mapValues { it.value.replay })
                Log.i("A2UIRpcDeviceTest", "realSdkRpcSmoke session=${initial.sessionId} twoCards=${initial.cards.keys} directions=both cancel=cancelled timeout=timed_out cardsAndMessagesUnchanged=true")
            }
        } finally { api.close() }
    }
}
