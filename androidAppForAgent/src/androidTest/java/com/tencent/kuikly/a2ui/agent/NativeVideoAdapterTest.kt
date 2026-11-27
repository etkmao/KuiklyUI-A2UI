package com.tencent.kuikly.a2ui.agent

import android.view.ContextThemeWrapper
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ui.PlayerView
import com.tencent.kuikly.core.render.android.adapter.IKRVideoView
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import com.tencent.kuikly.core.render.android.expand.component.KRVideoView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeVideoAdapterTest {
    @Test fun applicationRegistersVideoAdapter() {
        assertNotNull(KuiklyRenderAdapterManager.krVideoViewAdapter)
    }

    @Test fun nonEmptySourceAndFrameCreateRealPlayerAndReleaseSafely() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_AgentChat)
            val video = KRVideoView(context)
            try {
                video.setProp("src", File(context.cacheDir, "missing-video.mp4").toURI().toString())
                video.layoutParams = ViewGroup.MarginLayoutParams(320, 180)
                assertEquals(1, video.childCount)
                val playerView = video.getChildAt(0)
                assertTrue("Kuikly adapter must return an Android View", playerView is PlayerView)
                val player = playerView as PlayerView
                assertNotNull(player.player)
                assertEquals(320, player.layoutParams.width)
                assertEquals(180, player.layoutParams.height)
                video.onDestroy()
                (player as IKRVideoView).stop()
                (player as IKRVideoView).play()
                (player as IKRVideoView).setMuted(true)
                assertNull("Repeated stop and later controls must not touch a released player", player.player)
            } finally { video.onDestroy() }
        }
    }
}
