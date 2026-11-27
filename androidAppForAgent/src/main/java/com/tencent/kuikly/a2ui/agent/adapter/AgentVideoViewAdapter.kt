package com.tencent.kuikly.a2ui.agent.adapter

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.tencent.kuikly.core.render.android.adapter.IKRVideoView
import com.tencent.kuikly.core.render.android.adapter.IKRVideoViewAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRVideoViewListener
import com.tencent.kuikly.core.render.android.expand.component.KRVideoPlayState
import com.tencent.kuikly.core.render.android.expand.component.KRVideoViewContentMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class AgentVideoViewAdapter : IKRVideoViewAdapter {
    override fun createVideoView(context: Context, src: String, listener: IKRVideoViewListener): IKRVideoView =
        AgentVideoView(context, src, listener)
}

private class AgentVideoView(
    context: Context,
    src: String,
    private val callback: IKRVideoViewListener
) : PlayerView(context), IKRVideoView, DefaultLifecycleObserver {
    private val exoPlayer = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var owner: LifecycleOwner? = null
    private var released = false
    private var foreground = false
    private var wantsPlayback = true
    private var firstFrameRendered = false
    private var poster: ImageView? = null
    private var posterJob: Job? = null
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (released) return
            val state = when (playbackState) {
                Player.STATE_BUFFERING -> KRVideoPlayState.KRVideoPlayStateCaching
                Player.STATE_ENDED -> KRVideoPlayState.KRVideoPlayStatePlayEnd
                Player.STATE_READY -> if (exoPlayer.playWhenReady) KRVideoPlayState.KRVideoPlayStatePlaying else KRVideoPlayState.KRVideoPlayStatePaused
                else -> return
            }
            callback.videoPlayStateDidChangedWithState(state, emptyMap())
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (released) return
            if (foreground) wantsPlayback = playWhenReady
            if (exoPlayer.playbackState == Player.STATE_READY) onPlaybackStateChanged(Player.STATE_READY)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!released) callback.videoPlayStateDidChangedWithState(KRVideoPlayState.KRVideoPlayStateFaild, emptyMap())
        }

        override fun onRenderedFirstFrame() {
            if (released) return
            firstFrameRendered = true
            poster?.visibility = View.GONE
            callback.videoFirstFrameDidDisplay()
        }
    }

    init {
        useController = true
        player = exoPlayer
        exoPlayer.addListener(playerListener)
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(src)))
        exoPlayer.prepare()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (released) return
        var current: Context = context
        while (current !is LifecycleOwner && current is ContextWrapper && current.baseContext !== current) current = current.baseContext
        owner = current as? LifecycleOwner
        foreground = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
        owner?.lifecycle?.addObserver(this)
        applyPlaybackIntent()
    }

    override fun onResume(owner: LifecycleOwner) {
        if (released) return
        foreground = true
        applyPlaybackIntent()
    }

    override fun onPause(owner: LifecycleOwner) {
        if (released) return
        wantsPlayback = exoPlayer.playWhenReady
        foreground = false
        exoPlayer.pause()
    }

    override fun onDestroy(owner: LifecycleOwner) = stop()

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun applyPlaybackIntent() {
        if (!released) exoPlayer.playWhenReady = foreground && wantsPlayback
    }

    override fun preplay() {
        if (!released) exoPlayer.prepare()
    }

    override fun play() {
        wantsPlayback = true
        applyPlaybackIntent()
    }

    override fun pause() {
        wantsPlayback = false
        applyPlaybackIntent()
    }

    override fun stop() {
        if (released) return
        released = true
        foreground = false
        owner?.lifecycle?.removeObserver(this)
        owner = null
        scope.cancel()
        exoPlayer.removeListener(playerListener)
        player = null
        exoPlayer.release()
        poster?.setImageDrawable(null)
        poster = null
    }

    override fun setVideoContentMode(videoViewContentMode: KRVideoViewContentMode) {
        if (released) return
        resizeMode = when (videoViewContentMode) {
            KRVideoViewContentMode.KRVideoViewContentModeScaleAspectFill -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            KRVideoViewContentMode.KRVideoViewContentModeScaleToFill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun setMuted(muted: Boolean) {
        if (!released) exoPlayer.volume = if (muted) 0f else 1f
    }

    override fun setRate(rate: Float) {
        if (!released && rate.isFinite() && rate > 0f) exoPlayer.playbackParameters = PlaybackParameters(rate)
    }

    override fun seekToTime(seekToTimeMs: Long) {
        if (!released) exoPlayer.seekTo(seekToTimeMs.coerceAtLeast(0))
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        if (propKey != "posterUrl") return false
        if (released || firstFrameRendered) return true
        posterJob?.cancel()
        val value = propValue.toString()
        if (value.isEmpty()) {
            poster?.visibility = View.GONE
            return true
        }
        val uri = Uri.parse(value)
        if (uri.scheme !in listOf("https", "http")) return true
        val image = poster ?: ImageView(context).also {
            it.scaleType = ImageView.ScaleType.CENTER_CROP
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            poster = it
        }
        image.visibility = View.VISIBLE
        posterJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(value).openConnection().apply { connectTimeout = 10000; readTimeout = 10000 }
                    try { connection.getInputStream().use(BitmapFactory::decodeStream) }
                    finally { (connection as? java.net.HttpURLConnection)?.disconnect() }
                }.getOrNull()
            }
            if (!released && !firstFrameRendered) image.setImageBitmap(bitmap)
        }
        return true
    }

    override fun call(method: String, params: String?) {}
}
