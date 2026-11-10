package com.myeongwang.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import java.util.concurrent.Executors

/** 지금 재생 화면: 앨범 그림 · 싱크 가사 · 조작 단추 */
class NowPlayingActivity : ComponentActivity() {
    private var ctl: MediaController? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var art: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var seek: SeekBar
    private lateinit var pos: TextView
    private lateinit var dur: TextView
    private lateinit var playBtn: TextView
    private lateinit var shuffleBtn: TextView
    private lateinit var repeatBtn: TextView
    private lateinit var sleepInfo: TextView
    private lateinit var lyricScroll: ScrollView
    private lateinit var lyricBox: LinearLayout
    private var lyricViews: List<TextView> = emptyList()
    private var shownLyrics: Lyrics? = null
    private var shownLine = -2
    private var dragging = false
    private var artFor: String? = null
    private var showArt = true

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }
    private val busListener: () -> Unit = { main.post { refreshLyrics() } }
    private val ticker = object : Runnable {
        override fun run() {
            ctl?.let { c ->
                if (!dragging) {
                    val d = c.duration.coerceAtLeast(0)
                    seek.max = d.toInt().coerceAtLeast(1)
                    seek.progress = c.currentPosition.toInt()
                    pos.text = fmt(c.currentPosition); dur.text = fmt(d)
                }
            }
            val s = PlaybackService.instance?.sleepAt ?: 0
            sleepInfo.text = if (s > 0) "수면 타이머 " + fmt(s - System.currentTimeMillis()) else ""
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Col.BG)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(btn("◀", 20f) { finish() })
            addView(View(this@NowPlayingActivity), LinearLayout.LayoutParams(0, 1, 1f))
            sleepInfo = label("", 12f, Col.ACCENT)
            addView(sleepInfo)
            addView(btn("그림/가사", 13f) { showArt = !showArt; applyMode() })
        }
        root.addView(topBar)

        art = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Col.CARD) }
        lyricBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(120), 0, dp(160)) }
        lyricScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(lyricBox) }
        val stage = android.widget.FrameLayout(this)
        stage.addView(art, -1, -1)
        stage.addView(lyricScroll, -1, -1)
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(12) })

        title = label("", 20f, bold = true).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MARQUEE; isSelected = true }
        artist = label("", 14f, Col.SUB).apply { isSingleLine = true }
        root.addView(title); root.addView(artist)

        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { if (user) pos.text = fmt(p.toLong()) }
                override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar?) { dragging = false; ctl?.seekTo(seek.progress.toLong()) }
            })
        }
        root.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        pos = label("0:00", 12f, Col.SUB); dur = label("0:00", 12f, Col.SUB)
        root.addView(LinearLayout(this).apply {
            addView(pos); addView(View(this@NowPlayingActivity), LinearLayout.LayoutParams(0, 1, 1f)); addView(dur)
        })

        shuffleBtn = btn("섞기", 14f) { ctl?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        repeatBtn = btn("반복", 14f) {
            ctl?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        playBtn = btn("▶", 34f) { ctl?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(shuffleBtn)
            addView(btn("⏪", 22f) { ctl?.seekBack() })
            addView(btn("⏮", 26f) { ctl?.seekToPrevious() })
            addView(playBtn)
            addView(btn("⏭", 26f) { ctl?.seekToNext() })
            addView(btn("⏩", 22f) { ctl?.seekForward() })
            addView(repeatBtn)
        })
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(btn("수면", 14f) { Dialogs.sleep(this@NowPlayingActivity) })
            addView(btn("이퀄라이저", 14f) { Dialogs.equalizer(this@NowPlayingActivity) })
            addView(btn("속도", 14f) { ctl?.let { Dialogs.speed(this@NowPlayingActivity, it) } })
            addView(btn("상단바", 14f) {
                Prefs.statusNotify = !Prefs.statusNotify
                PlaybackService.instance?.refreshStatus()
                toast("상단바 가사 알림 " + if (Prefs.statusNotify) "켜짐" else "꺼짐")
            })
        })
        setContentView(root)
        applyMode()
    }

    private fun applyMode() {
        val hasLyrics = shownLyrics != null
        val lyricsMode = !showArt && hasLyrics
        art.visibility = if (lyricsMode) View.INVISIBLE else View.VISIBLE
        lyricScroll.visibility = if (lyricsMode) View.VISIBLE else View.GONE
        art.alpha = 1f
    }

    override fun onStart() {
        super.onStart()
        NowBus.add(busListener)
        Remote.get(this) { c -> ctl = c; c.removeListener(listener); c.addListener(listener); refresh() }
        main.post(ticker)
    }

    override fun onStop() {
        NowBus.remove(busListener)
        ctl?.removeListener(listener)
        main.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    private fun refresh() {
        val c = ctl ?: return
        val md = c.mediaMetadata
        title.text = md.title ?: "재생 중인 노래 없음"
        artist.text = listOfNotNull(md.artist, md.albumTitle).joinToString(" · ")
        playBtn.text = if (c.isPlaying) "⏸" else "▶"
        shuffleBtn.setTextColor(if (c.shuffleModeEnabled) Col.ACCENT else Col.SUB)
        repeatBtn.text = when (c.repeatMode) { Player.REPEAT_MODE_ALL -> "전체 반복"; Player.REPEAT_MODE_ONE -> "한 곡 반복"; else -> "반복 끔" }
        repeatBtn.setTextColor(if (c.repeatMode == Player.REPEAT_MODE_OFF) Col.SUB else Col.ACCENT)
        val id = c.currentMediaItem?.mediaId
        if (id != artFor) { artFor = id; loadArt(id) }
        refreshLyrics()
    }

    private fun loadArt(mediaId: String?) {
        art.setImageResource(R.drawable.ic_note)
        val song = Library.song(mediaId) ?: return
        io.execute {
            val bm: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(song.uri, Size(800, 800), null)
                else contentResolver.openInputStream(song.artUri)?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
            main.post { if (artFor == mediaId && bm != null) art.setImageBitmap(bm) }
        }
    }

    private fun refreshLyrics() {
        val svc = PlaybackService.instance
        val l = svc?.lyrics
        if (l !== shownLyrics) {
            shownLyrics = l; shownLine = -2
            lyricBox.removeAllViews()
            lyricViews = l?.lines?.map { line ->
                label(line.text.ifEmpty { "♪" }, 17f, Col.SUB).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(7), 0, dp(7))
                    if (l.synced) setOnClickListener { ctl?.seekTo(line.t) }
                }.also { lyricBox.addView(it) }
            } ?: emptyList()
            if (l != null && !l.synced) lyricViews.forEach { it.setTextColor(Col.TEXT) }
            if (l != null) showArt = false
            applyMode()
        }
        val idx = svc?.lyricLine ?: -1
        if (l != null && l.synced && idx != shownLine) {
            lyricViews.getOrNull(shownLine)?.apply { setTextColor(Col.SUB); textSize = 17f }
            shownLine = idx
            lyricViews.getOrNull(idx)?.let { v ->
                v.setTextColor(Col.ACCENT); v.textSize = 20f
                v.post { lyricScroll.smoothScrollTo(0, (v.top - lyricScroll.height / 2 + v.height / 2).coerceAtLeast(0)) }
            }
        }
    }
}
