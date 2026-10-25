package com.myeongwang.tv

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 명왕사신TV 앱
 *  - 화면(채널 목록·편성표·설정)은 tvwa의 index.html을 WebView로 그대로 씀 (앱 안에 내장)
 *  - 영상은 ExoPlayer가 재생: WebView 뒤에 깔고, 웹 화면의 플레이어 칸을 투명하게 뚫어 보여 줌
 *  - 방송사 토큰 API는 앱이 직접 받음(https://relay.app/?url=…) → 휴대폰의 국내 IP로 MBC 등도 재생
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private val main = Handler(Looper.getMainLooper())

    private var reqId = ""
    private var readySent = false
    private var fullscreen = false
    private var inPip = false
    private var pausedByStop = false
    private var lastBack = 0L
    // 웹 화면이 알려 준 플레이어 칸 (CSS px)
    private var rx = 0f; private var ry = 0f; private var rw = 0f; private var rh = 0f
    private var cornerPx = 0f

    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF0B1018.toInt()) }
        playerView = LayoutInflater.from(this).inflate(R.layout.player_view, root, false) as PlayerView
        playerView.visibility = View.INVISIBLE
        cornerPx = 16f * resources.displayMetrics.density
        playerView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, if (fullscreen || inPip) 0f else cornerPx)
            }
        }
        playerView.clipToOutline = true
        root.addView(playerView, FrameLayout.LayoutParams(1, 1))

        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        setupPlayer()
        setupWeb()
        setupGestures()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) { exitFullscreen(); return }
                web.evaluateJavascript("(window.__appBack && window.__appBack()) ? 'y' : 'n'") { r ->
                    if (r?.contains("y") == true) return@evaluateJavascript
                    val now = System.currentTimeMillis()
                    if (now - lastBack < 2000) finish()
                    else { lastBack = now; Toast.makeText(this@MainActivity, "한 번 더 누르면 종료합니다", Toast.LENGTH_SHORT).show() }
                }
            }
        })
    }

    /* ───────────────────────────── 재생기 ───────────────────────────── */
    private fun setupPlayer() {
        // 채널을 빨리 바꾸려고 재생 시작에 필요한 버퍼를 짧게(0.6초) 잡음
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 600, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(load).build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { sendReady() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    // 소리만 있는 채널: 영상 첫 화면이 없으니 조금 기다렸다가 준비 완료로 봄
                    val id = reqId
                    main.postDelayed({ if (id == reqId && player.videoFormat == null && player.playbackState == Player.STATE_READY) sendReady() }, 1500)
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) send("size", JSONObject().put("h", videoSize.height))
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                send("error", JSONObject().put("msg", describe(error)))
            }
        })
    }

    private fun sendReady() {
        if (readySent) return
        readySent = true
        send("ready", JSONObject().put("h", player.videoSize.height))
    }

    private fun describe(e: PlaybackException): String {
        var c: Throwable? = e
        while (c != null) {
            if (c is HttpDataSource.InvalidResponseCodeException) return "거부됨 ${c.responseCode}"
            if (c is HttpDataSource.HttpDataSourceException) return "연결 실패"
            c = c.cause
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "재생목록 형식 오류"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "이 기기에서 풀 수 없는 영상"
            else -> e.errorCodeName
        }
    }

    private fun play(url: String, referer: String, id: String) {
        reqId = id
        readySent = false
        val headers = HashMap<String, String>()
        if (referer.isNotEmpty()) { headers["Referer"] = referer; headers["Origin"] = referer.trimEnd('/') }
        val ds = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(12_000)
            .setDefaultRequestProperties(headers)
        val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        val src = HlsMediaSource.Factory(ds).setAllowChunklessPreparation(true).createMediaSource(item)
        player.setMediaSource(src)
        player.prepare()
        player.playWhenReady = true
        applyRect()
    }

    private fun send(type: String, data: JSONObject) {
        data.put("t", type).put("id", reqId)
        val js = "window.__appEvent && window.__appEvent(${data})"
        main.post { web.evaluateJavascript(js, null) }
    }

    /* ─────────────────────── 웹 화면 ↔ 앱 연결 ─────────────────────── */
    inner class Bridge {
        @JavascriptInterface fun play(url: String, referer: String, id: String) { main.post { this@MainActivity.play(url, referer, id) } }
        @JavascriptInterface fun stop() { main.post { reqId = ""; player.stop(); player.clearMediaItems(); playerView.visibility = View.INVISIBLE } }
        @JavascriptInterface fun setPaused(p: Boolean) { main.post { player.playWhenReady = !p } }
        @JavascriptInterface fun setFullscreen(on: Boolean) { main.post { if (on) enterFullscreen() else exitFullscreen() } }
        @JavascriptInterface fun setRect(x: Float, y: Float, w: Float, h: Float) {
            main.post { rx = x; ry = y; rw = w; rh = h; applyRect() }
        }
        @JavascriptInterface fun isApp(): Boolean = true
    }

    /** 웹 화면의 플레이어 칸 위치로 영상을 옮김 */
    private fun applyRect() {
        if (fullscreen || inPip) return
        val d = resources.displayMetrics.density
        val w = (rw * d).roundToInt(); val h = (rh * d).roundToInt()
        val hasMedia = player.mediaItemCount > 0
        if (w <= 0 || h <= 0 || !hasMedia) { playerView.visibility = View.INVISIBLE; return }
        val lp = playerView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; playerView.layoutParams = lp; playerView.invalidateOutline() }
        playerView.translationX = rx * d
        playerView.translationY = ry * d
        playerView.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString + " MyeongwangTVApp/1"
        }
        web.addJavascriptInterface(Bridge(), "AppBridge")
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.d("tvwa", "${m.message()} (${m.lineNumber()})"); return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host == "relay.app") return relay(u)
                return assets.shouldInterceptRequest(u)
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // 앱 화면 밖으로 나가는 링크는 열지 않음
                return req.url.host != "appassets.androidplatform.net"
            }
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    /** 방송사 토큰 API를 앱이 직접 받아 웹 화면에 돌려줌 */
    private fun relay(u: Uri): WebResourceResponse {
        val cors = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")
        val target = u.getQueryParameter("url") ?: return WebResourceResponse("text/plain", "utf-8", 400, "Bad Request", cors, ByteArrayInputStream("bad url".toByteArray()))
        val host = try { URL(target).host } catch (e: Exception) { "" }
        val referer = when {
            host.endsWith("sbs.co.kr") -> "https://www.sbs.co.kr/"
            host.endsWith("imbc.com") -> "https://onair.imbc.com/"
            host.endsWith("kbs.co.kr") -> "https://onair.kbs.co.kr/"
            else -> return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", cors, ByteArrayInputStream("host not allowed".toByteArray()))
        }
        return try {
            val c = URL(target).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 10000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Referer", referer)
            c.setRequestProperty("Origin", referer.trimEnd('/'))
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)
            val type = (c.contentType ?: "text/plain").substringBefore(';').trim()
            WebResourceResponse(type, "utf-8", code, if (code < 400) "OK" else "Error", cors, ByteArrayInputStream(body))
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", cors, ByteArrayInputStream((e.message ?: "error").toByteArray()))
        }
    }

    /* ───────────────────────────── 전체화면 ───────────────────────────── */
    private fun setupGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (fullscreen) exitFullscreen(); return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (!fullscreen || e1 == null) return false
                val dx = e2.x - e1.x
                if (abs(dx) > 120 && abs(dx) > abs(e2.y - e1.y) * 1.5f) {
                    val n = if (dx < 0) 1 else -1
                    web.evaluateJavascript("window.__appStep && window.__appStep($n); window.__appChannelName ? window.__appChannelName() : ''") { name ->
                        val t = name?.trim('"') ?: ""
                        if (t.isNotEmpty()) Toast.makeText(this@MainActivity, t, Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }
        })
        playerView.setOnTouchListener { _, ev -> if (fullscreen) gd.onTouchEvent(ev) else false }
    }

    private fun enterFullscreen() {
        if (fullscreen) return
        fullscreen = true
        web.visibility = View.GONE
        fillScreen()
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        Toast.makeText(this, "좌우로 밀면 채널 이동 · 누르면 나가기", Toast.LENGTH_SHORT).show()
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        fullscreen = false
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        web.visibility = View.VISIBLE
        playerView.invalidateOutline()
        applyRect()
        main.postDelayed({ web.evaluateJavascript("window.__syncRect && window.__syncRect()", null) }, 300)
    }

    private fun fillScreen() {
        val lp = playerView.layoutParams as FrameLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        playerView.layoutParams = lp
        playerView.translationX = 0f; playerView.translationY = 0f
        playerView.visibility = View.VISIBLE
        playerView.invalidateOutline()
    }

    /* ─────────────────────── 작은 창(PIP)·앱 전환 ─────────────────────── */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying && player.videoFormat != null) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (e: Exception) { /* PIP 꺼진 기기 */ }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) { web.visibility = View.GONE; fillScreen() }
        else if (fullscreen) fillScreen()
        else { web.visibility = View.VISIBLE; playerView.invalidateOutline(); applyRect() }
    }

    override fun onStop() {
        super.onStop()
        // 작은 창이 아니면 화면을 떠날 때 잠시 멈춤
        if (!inPip && player.playWhenReady) { pausedByStop = true; player.playWhenReady = false }
    }

    override fun onStart() {
        super.onStart()
        if (pausedByStop) { pausedByStop = false; player.playWhenReady = true }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (fullscreen || inPip) main.post { fillScreen() }
        else main.postDelayed({ web.evaluateJavascript("window.__syncRect && window.__syncRect()", null) }, 200)
    }

    override fun onDestroy() {
        player.release()
        web.destroy()
        super.onDestroy()
    }
}
