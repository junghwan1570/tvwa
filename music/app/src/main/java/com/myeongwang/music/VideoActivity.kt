package com.myeongwang.music

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** 영상 재생: 기기 안 영상 · 인터넷 주소(파일·m3u8) · 화면 속 작은 화면 · 내려받기 */
@OptIn(UnstableApi::class)
class VideoActivity : ComponentActivity() {
    companion object {
        fun open(ctx: Context, uri: Uri, title: String?) {
            ctx.startActivity(Intent(ctx, VideoActivity::class.java).setData(uri).putExtra("제목", title))
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var bar: LinearLayout
    private var uri: Uri? = null
    private var aspect = Rational(16, 9)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        player = ExoPlayer.Builder(this).setHandleAudioBecomingNoisy(true).build()
        view = PlayerView(this).apply {
            this.player = this@VideoActivity.player
            keepScreenOn = true
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        bar = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(btn("작은 화면", 14f) { enterPip() })
            addView(btn("내려받기", 14f) { download() })
            addView(btn("닫기", 14f) { finish() })
        }
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> bar.visibility = v })
        val root = FrameLayout(this)
        root.addView(view, -1, -1)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val r = videoSize.width.toFloat() / videoSize.height
                    aspect = when { r > 2.39f -> Rational(239, 100); r < 0.42f -> Rational(42, 100); else -> Rational(videoSize.width, videoSize.height) }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                toast("재생하지 못했어요 (오류 번호 ${error.errorCode})")
            }
        })
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        start(intent)
    }

    private fun start(i: Intent?) {
        val u = i?.data ?: run { finish(); return }
        uri = u
        // 음악이 나오고 있으면 멈춤
        Remote.controller?.pause()
        PlaybackService.instance?.player?.pause()
        val title = i.getStringExtra("제목") ?: Uri.decode(u.lastPathSegment ?: "")
        player.setMediaItem(MediaItem.Builder().setUri(u).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build())
        player.prepare()
        player.play()
    }

    private fun download() {
        val u = uri ?: return
        if (u.scheme == "http" || u.scheme == "https") Downloader.start(this, u.toString(), false)
        else toast("이미 기기에 있는 영상이에요")
    }

    private fun enterPip() {
        try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(aspect).build()) } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        view.useController = !isInPictureInPictureMode
        bar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
