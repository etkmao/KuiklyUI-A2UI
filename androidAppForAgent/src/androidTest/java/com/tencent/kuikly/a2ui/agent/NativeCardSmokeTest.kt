package com.tencent.kuikly.a2ui.agent

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.kuikly.a2ui.agent.ui.A2UICardHostView
import org.junit.Assert.assertTrue
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
