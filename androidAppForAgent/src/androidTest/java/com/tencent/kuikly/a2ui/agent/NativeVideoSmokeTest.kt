package com.tencent.kuikly.a2ui.agent

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ui.PlayerView
import com.tencent.kuikly.a2ui.agent.data.CardDocument
import com.tencent.kuikly.a2ui.agent.ui.A2UICardHostView
import com.tencent.kuikly.core.render.android.adapter.IKRVideoView
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeVideoSmokeTest {
    private val missingVideo get() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "missing-video-${UUID.randomUUID()}.mp4").toURI().toString()

    @Test fun applicationRegistersRealVideoAdapter() {
        assertNotNull(KuiklyRenderAdapterManager.krVideoViewAdapter)
    }

    @Test fun videoCardRendersAndReplaysAfterDetachAndReplacement() {
        ActivityScenario.launch(AIChatActivity::class.java).use { scenario ->
            lateinit var host: A2UICardHostView
            lateinit var container: FrameLayout
            scenario.onActivity { activity ->
                container = FrameLayout(activity)
                host = A2UICardHostView(activity)
                host.bind(videoCard())
                container.addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                activity.setContentView(container)
            }
            val first = awaitPlayer(scenario, host)
            scenario.onActivity {
                container.removeView(host)
                assertNull("Detached player must release its backing ExoPlayer", first.player)
                (first as IKRVideoView).stop()
                container.addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val second = awaitPlayer(scenario, host)
            assertNotSame(first, second)
            scenario.onActivity { host.bind(videoCard()) }
            val third = awaitPlayer(scenario, host)
            assertNotSame(second, third)
            scenario.onActivity {
                assertNull("Replacing a card must release the old player", second.player)
                host.releaseSurface()
                (third as IKRVideoView).stop()
                (third as IKRVideoView).stop()
                assertNull(third.player)
            }
        }
    }

    @Test fun videoPausesInBackgroundAndPreservesUserPause() {
        ActivityScenario.launch(AIChatActivity::class.java).use { scenario ->
            lateinit var host: A2UICardHostView
            scenario.onActivity { activity ->
                host = A2UICardHostView(activity).apply { bind(videoCard()) }
                activity.setContentView(host)
            }
            val view = awaitPlayer(scenario, host)
            scenario.onActivity { (view as IKRVideoView).play(); assertTrue(view.player!!.playWhenReady) }
            scenario.moveToState(Lifecycle.State.CREATED)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { assertFalse(view.player!!.playWhenReady) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertTrue(view.player!!.playWhenReady); (view as IKRVideoView).pause() }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertFalse("User pause must survive background/resume", view.player!!.playWhenReady) }
            scenario.moveToState(Lifecycle.State.DESTROYED)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { assertNull(view.player) }
        }
    }

    private fun videoCard(): CardDocument {
        val surface = UUID.randomUUID().toString()
        val envelope = JSONObject().put("version", "1.0").put("createSurface", JSONObject()
            .put("surfaceId", surface)
            .put("catalogId", "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json")
            .put("components", JSONArray().put(JSONObject().put("id", "root").put("component", "Video").put("url", missingVideo))))
        return CardDocument("video-card", surface, 1, "ready", false, listOf(envelope.toString()))
    }

    private fun awaitPlayer(scenario: ActivityScenario<AIChatActivity>, host: A2UICardHostView): PlayerView {
        var result: PlayerView? = null
        val deadline = System.currentTimeMillis() + 10000
        while (result == null && System.currentTimeMillis() < deadline) {
            scenario.onActivity {
                result = descendants(host).filterIsInstance<PlayerView>().firstOrNull { it.player != null && it.width > 0 && it.height > 0 }
            }
            if (result == null) Thread.sleep(100)
        }
        assertNotNull("A2UI Video must produce a measured native PlayerView even when its media source fails", result)
        return result!!
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
