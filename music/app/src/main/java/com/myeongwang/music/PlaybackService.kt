package com.myeongwang.music

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

/** 백그라운드 재생 · 잠금화면/알림 조작 · 이어폰 버튼 · 상단바 가사 · 수면 타이머 · 이퀄라이저 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    companion object {
        var instance: PlaybackService? = null; private set
        private const val LYRIC_CH = "상단바가사"
        private const val LYRIC_ID = 7707
    }

    lateinit var player: ExoPlayer; private set
    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var overlay: StatusOverlay
    private var ready = false
    private var lyricToken = 0

    var lyrics: Lyrics? = null; private set
    var lyricLine = -1; private set
    /** 수면 타이머가 끝나는 시각 (0 = 꺼짐) */
    var sleepAt = 0L; private set
    private var eq: Equalizer? = null
    private var lastStatus: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (sleepAt in 1..System.currentTimeMillis()) { sleepAt = 0; player.pause(); NowBus.emit() }
            val l = lyrics?.indexAt(player.currentPosition) ?: -1
            if (l != lyricLine) { lyricLine = l; NowBus.emit() }
            updateStatus()
            if (player.isPlaying) main.postDelayed(this, 200)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(applicationContext)
        overlay = StatusOverlay(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true) // 이어폰을 빼면 멈춤
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setPlaybackSpeed(Prefs.speed)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, NowPlayingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // 화면에서 보낸 항목에는 파일 주소가 빠져 오므로 다시 채워 넣음
                override fun onAddMediaItems(
                    mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems.map { fix(it) }.toMutableList())
            })
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).setChannelName(R.string.ch_play).build()
                .also { it.setSmallIcon(R.drawable.ic_note) }
        )
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(LYRIC_CH, getString(R.string.ch_lyric), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false); setSound(null, null); enableVibration(false)
                }
            )
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { loadLyrics(); save() }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                main.removeCallbacks(tick)
                if (isPlaying) main.post(tick) else { updateStatus(); save() }
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { save() }
            override fun onAudioSessionIdChanged(audioSessionId: Int) { eq?.release(); eq = null; applyEq() }
        })
        applyEq()
        // 마지막에 듣던 대기열과 위치 되살리기
        io.execute {
            Library.ensure(this)
            Queues.ensure(this)
            main.post {
                if (player.mediaItemCount == 0) Queues.loadInto(this, player, Queues.active)
                ready = true
                loadLyrics()
            }
        }
    }

    private fun fix(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        val uri = item.requestMetadata.mediaUri
            ?: run { Library.ensure(this); Library.itemFor(item.mediaId)?.localConfiguration?.uri }
            ?: return item
        return item.buildUpon().setUri(uri).build()
    }

    private fun save() {
        if (!ready) return
        Queues.capture(this, player)
        Queues.save(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        save()
        main.removeCallbacksAndMessages(null)
        overlay.hide()
        NotificationManagerCompat.from(this).cancel(LYRIC_ID)
        eq?.release(); eq = null
        session?.release(); session = null
        player.release()
        io.shutdown()
        instance = null
        super.onDestroy()
    }

    // ───── 가사 ─────
    fun reloadLyrics() = loadLyrics()

    private fun loadLyrics() {
        val item = player.currentMediaItem
        val token = ++lyricToken
        lyrics = null; lyricLine = -1; NowBus.emit(); updateStatus()
        if (item == null) return
        val uri = item.localConfiguration?.uri
        val md = item.mediaMetadata
        io.execute {
            val l = LyricsLoader.load(this, item.mediaId, uri, md.title?.toString(), md.artist?.toString())
            main.post {
                if (token != lyricToken) return@post
                lyrics = l; lyricLine = l?.indexAt(player.currentPosition) ?: -1
                NowBus.emit(); updateStatus()
            }
        }
    }

    /** 상단바에 보일 글: 지금 가사 줄, 없으면 "♪ 제목 - 가수" */
    fun statusText(): String {
        val l = lyrics
        if (l != null && lyricLine >= 0) {
            val t = l.lines[lyricLine].text
            if (t.isNotBlank()) return "♪ $t"
        }
        val md = player.mediaMetadata
        val title = md.title?.toString() ?: return "사신뮤직"
        val artist = md.artist?.toString()
        return if (artist.isNullOrBlank()) "♪ $title" else "♪ $title - $artist"
    }

    fun refreshStatus() { lastStatus = null; updateStatus() }

    private fun updateStatus() {
        val playing = player.isPlaying
        val text = statusText()
        if (playing && Prefs.statusOverlay) overlay.show(text) else overlay.hide()
        val nm = NotificationManagerCompat.from(this)
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!playing || !Prefs.statusNotify || !allowed) {
            if (lastStatus != null) { nm.cancel(LYRIC_ID); lastStatus = null }
            return
        }
        if (text == lastStatus) return
        lastStatus = text
        val md = player.mediaMetadata
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, NowPlayingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, LYRIC_CH)
            .setSmallIcon(R.drawable.ic_lyric)
            .setContentTitle(text)
            .setContentText(listOfNotNull(md.title, md.artist).joinToString(" - "))
            .setTicker(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try { nm.notify(LYRIC_ID, n) } catch (_: SecurityException) {}
    }

    // ───── 수면 타이머 ─────
    fun setSleep(minutes: Int) {
        sleepAt = if (minutes <= 0) 0 else System.currentTimeMillis() + minutes * 60_000L
        NowBus.emit()
    }

    // ───── 이퀄라이저 ─────
    fun equalizer(): Equalizer? {
        if (eq == null) eq = try { Equalizer(0, player.audioSessionId) } catch (_: Throwable) { null }
        return eq
    }

    fun applyEq() {
        val e = equalizer() ?: return
        try {
            val lv = Prefs.eqLevels.split(',').mapNotNull { it.toShortOrNull() }
            for (b in 0 until e.numberOfBands) lv.getOrNull(b)?.let { e.setBandLevel(b.toShort(), it) }
            e.enabled = Prefs.eqOn
        } catch (_: Throwable) {}
    }
}
