package com.tencent.kuikly.a2ui.agent

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.kuikly.a2ui.agent.data.CardDocument
import com.tencent.kuikly.a2ui.agent.data.RendererCardAction
import com.tencent.kuikly.a2ui.agent.ui.A2UICardHostView
import org.json.JSONObject
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicReference
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCardSmokeTest {
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

    @Test fun nativeCallbackResolvesLatestInputButMissingSourceAndSurfaceFailClosed() {
        val card = formCard()
        val callback = AtomicReference<RendererCardAction?>()
        ActivityScenario.launch(AIChatActivity::class.java).use { scenario ->
            lateinit var host: A2UICardHostView
            scenario.onActivity { activity ->
                host = A2UICardHostView(activity).apply { onAction = callback::set; bind(card) }
                activity.setContentView(host)
            }
            awaitForm(scenario, host)
            scenario.onActivity {
                val input = descendants(host).filterIsInstance<EditText>().first()
                input.setText("较旧输入")
                input.setText("本地最后输入")
            }
            tapSubmit(scenario, host)
            awaitCallback(callback)
            val actual = callback.get()!!
            assertEquals(card.id, actual.cardId); assertEquals(card.revision, actual.baseCardRevision)
            assertEquals("dsl-surface", actual.surfaceId); assertTrue(actual.tag > 0); assertNull(actual.params)
            val raw = JSONObject(actual.actionJson)
            assertEquals("submit_form", raw.getJSONObject("event").getString("name"))
            assertEquals("本地最后输入", raw.getJSONObject("event").getJSONObject("context").getString("value"))
            assertFalse(raw.has("sourceComponentId")); assertFalse(raw.getJSONObject("event").has("sourceComponentId"))
            assertTrue("Current SDK must not produce a fabricated action request", runCatching { actual.requestBody(card) }.isFailure)
            scenario.onActivity { activity ->
                assertFalse(ViewModelProvider(activity)[ChatViewModel::class.java].submitAction(actual))
            }
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
            awaitCallback(callback)
            assertEquals("重新挂载后的输入", JSONObject(callback.get()!!.actionJson)
                .getJSONObject("event").getJSONObject("context").getString("value"))
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
                val label = descendants(host).filterIsInstance<TextView>().first { it.text.toString() == "提交" }
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
                    descendants(host).filterIsInstance<TextView>().any { it.text.toString() == "提交" && it.height > 0 }
            }
            if (!ready) Thread.sleep(100)
        }
        assertTrue("Form must contain native TextField and submit label", ready)
    }
    private fun tapSubmit(scenario: ActivityScenario<AIChatActivity>, host: A2UICardHostView) {
        var x = 0f; var y = 0f
        scenario.onActivity {
            val label = descendants(host).filterIsInstance<TextView>().first { it.text.toString() == "提交" }
            val location = IntArray(2).also(label::getLocationOnScreen)
            x = location[0] + label.width / 2f; y = location[1] + label.height / 2f
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(now, now + index * 40, action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
    }
    private fun awaitCallback(callback: AtomicReference<RendererCardAction?>) {
        val deadline = System.currentTimeMillis() + 10000
        while (callback.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertNotNull("A real native button touch must reach the host callback", callback.get())
    }

    private fun awaitRenderedText(scenario: ActivityScenario<AIChatActivity>) {
        var rendered = false
        val deadline = System.currentTimeMillis() + 10000
        while (!rendered && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val host = descendants(activity.window.decorView).filterIsInstance<A2UICardHostView>().firstOrNull()
                rendered = host != null && descendants(host).filterIsInstance<TextView>().any {
                    it.text.toString().contains("这是一张由 Kuikly A2UI 原生渲染的卡片") && it.height > 0
                }
            }
            if (!rendered) Thread.sleep(100)
        }
        assertTrue("A2UI Text must produce a measured native text view", rendered)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
