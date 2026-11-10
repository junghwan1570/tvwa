package com.myeongwang.music

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val tabs = listOf("노래", "앨범", "가수", "폴더", "영상", "대기열", "설정")
    private var tab = 0
    private var drill: Pair<String, List<Song>>? = null
    private var query = ""
    private val adapter = RowAdapter()
    private val io = Executors.newSingleThreadExecutor()
    private var ctl: MediaController? = null

    private lateinit var tabRow: LinearLayout
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView
    private lateinit var mini: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: TextView

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refreshMini()
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) render()
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadLibrary() }

    private val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Prefs.lyricsTree = uri.toString()
            LyricsLoader.resetIndex()
            PlaybackService.instance?.reloadLyrics()
            toast("가사 폴더를 정했어요")
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        onBackPressedDispatcher.addCallback(this) {
            when {
                drill != null -> { drill = null; render() }
                query.isNotEmpty() -> search.setText("")
                tab != 0 -> { tab = 0; render() }
                else -> finish()
            }
        }
        askPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Remote.get(this) { c ->
            ctl = c
            c.removeListener(listener)
            c.addListener(listener)
            refreshMini(); render()
        }
    }

    override fun onStop() {
        ctl?.removeListener(listener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        PlaybackService.instance?.refreshStatus()
        if (tab == 6) render()
    }

    private fun withCtl(f: (MediaController) -> Unit) = Remote.get(this, f)

    // ───── 화면 만들기 ─────
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Col.BG) }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(4))
            addView(label("사신뮤직", 22f, Col.ACCENT, bold = true))
        }
        search = EditText(this).apply {
            hint = "노래·가수·앨범 찾기"
            setSingleLine()
            setTextColor(Col.TEXT); setHintTextColor(Col.SUB)
            textSize = 15f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; render() }
            })
        }
        top.addView(search, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        root.addView(top)

        tabRow = LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) })

        val mid = android.widget.FrameLayout(this)
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = this@MainActivity.adapter }
        empty = label("", 15f, Col.SUB).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        mid.addView(list, -1, -1)
        mid.addView(empty, -1, -1)
        root.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))

        // 아래쪽 작은 재생기
        miniTitle = label("재생 중인 노래 없음", 15f).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MARQUEE; isSelected = true }
        miniSub = label("", 12f, Col.SUB).apply { isSingleLine = true }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(miniTitle); addView(miniSub) }
        miniPlay = btn("▶", 22f) { withCtl { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
        mini = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Col.CARD)
            setPadding(dp(16), dp(6), dp(4), dp(6))
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn("⏮", 20f) { withCtl { it.seekToPrevious() } })
            addView(miniPlay)
            addView(btn("⏭", 20f) { withCtl { it.seekToNext() } })
            setOnClickListener { startActivity(Intent(this@MainActivity, NowPlayingActivity::class.java)) }
        }
        root.addView(mini)
        setContentView(root)
    }

    private fun updateTabs() {
        tabRow.removeAllViews()
        tabs.forEachIndexed { i, name ->
            tabRow.addView(btn(name, 15f) { tab = i; drill = null; render(); list.scrollToPosition(0) }.apply {
                setTextColor(if (i == tab) Col.ACCENT else Col.SUB)
                setTypeface(null, if (i == tab) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            })
        }
    }

    private fun refreshMini() {
        val c = ctl ?: return
        val md = c.mediaMetadata
        miniTitle.text = md.title ?: "재생 중인 노래 없음"
        miniSub.text = md.artist ?: ""
        miniPlay.text = if (c.isPlaying) "⏸" else "▶"
    }

    // ───── 권한 · 목록 읽기 ─────
    private fun askPermissions() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            need += Manifest.permission.READ_MEDIA_AUDIO
            need += Manifest.permission.READ_MEDIA_VIDEO
            need += Manifest.permission.POST_NOTIFICATIONS
        } else {
            need += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT < 29) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) loadLibrary() else permLauncher.launch(missing.toTypedArray())
    }

    private fun loadLibrary() {
        empty.text = "노래를 찾는 중…"
        empty.visibility = View.VISIBLE
        io.execute {
            Library.load(this)
            Queues.ensure(this)
            runOnUiThread { render() }
        }
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val url = Regex("""https?://\S+""").find(text)?.value ?: run { toast("공유받은 글에 주소가 없어요"); return }
                Dialogs.urlAction(this, url)
            }
            Intent.ACTION_VIEW -> {
                val uri = i.data ?: return
                withCtl { c -> c.setMediaItems(listOf(uriItem(uri, null))); c.prepare(); c.play() }
                startActivity(Intent(this, NowPlayingActivity::class.java))
            }
        }
        setIntent(Intent())
    }

    // ───── 재생 ─────
    private fun play(songs: List<Song>, index: Int) = withCtl { c ->
        if (songs.isEmpty()) return@withCtl
        c.setMediaItems(songs.map { it.toItem() }, index, 0)
        c.prepare(); c.play()
    }

    private fun songMenu(s: Song) {
        val opts = arrayOf("다음에 재생", "지금 대기열 끝에 추가", "다른 대기열에 추가", "이 가수의 노래 보기", "이 앨범의 노래 보기", "파일 정보")
        AlertDialog.Builder(this).setTitle(s.title).setItems(opts) { _, w ->
            when (w) {
                0 -> withCtl { c ->
                    val empty = c.mediaItemCount == 0
                    c.addMediaItem(if (empty) 0 else c.currentMediaItemIndex + 1, s.toItem())
                    if (empty) c.prepare()
                    toast("다음에 재생해요")
                }
                1 -> withCtl { c ->
                    val empty = c.mediaItemCount == 0
                    c.addMediaItem(s.toItem()); if (empty) c.prepare()
                    toast("대기열 끝에 넣었어요")
                }
                2 -> pickQueue("어느 대기열에 넣을까요?") { qi ->
                    if (qi == Queues.active) withCtl { it.addMediaItem(s.toItem()) }
                    else { Queues.list[qi].ids += s.id.toString(); Queues.save(this) }
                    toast("대기열 ${qi + 1}에 넣었어요")
                }
                3 -> { drill = s.artist to sortedAlbumSongs(Library.songs.filter { it.artist == s.artist }); render() }
                4 -> { drill = s.album to sortedAlbumSongs(Library.songs.filter { it.albumId == s.albumId }); render() }
                5 -> AlertDialog.Builder(this).setTitle("파일 정보").setMessage(
                    "제목: ${s.title}\n가수: ${s.artist}\n앨범: ${s.album}\n길이: ${fmt(s.duration)}\n파일: ${s.fileName}\n폴더: ${s.folder}"
                ).setPositiveButton("확인", null).show()
            }
        }.show()
    }

    private fun pickQueue(title: String, cb: (Int) -> Unit) {
        Queues.ensure(this)
        val names = Queues.list.indices.map { "대기열 ${it + 1}" + if (it == Queues.active) " (지금)" else "" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title).setItems(names) { _, w -> cb(w) }.show()
    }

    private fun switchQueue(i: Int) = withCtl { c ->
        Queues.capture(this, c)
        Queues.active = i
        Queues.loadInto(this, c, i)
        Queues.save(this)
        render()
    }

    private fun sortedAlbumSongs(l: List<Song>) =
        l.sortedWith(compareBy<Song> { it.album }.thenBy { it.track }.thenComparator { a, b -> Library.collator.compare(a.title, b.title) })

    // ───── 목록 그리기 ─────
    private fun match(s: Song, q: String) = q.isEmpty() ||
        s.title.lowercase().contains(q) || s.artist.lowercase().contains(q) || s.album.lowercase().contains(q)

    private fun songRows(rows: MutableList<Row>, songs: List<Song>) {
        if (songs.isEmpty()) return
        val cur = ctl?.currentMediaItem?.mediaId
        rows += Row("▶ 모두 재생", "${songs.size}곡", accent = true, click = { play(songs, 0) })
        rows += Row("🔀 섞어서 재생", click = { play(songs.shuffled(), 0) })
        songs.forEachIndexed { i, s ->
            rows += Row(
                s.title, "${s.artist} · ${s.album} · ${fmt(s.duration)}",
                accent = s.id.toString() == cur,
                click = { play(songs, i) }, long = { songMenu(s) },
            )
        }
    }

    private fun render() {
        updateTabs()
        val q = query.trim().lowercase()
        val rows = mutableListOf<Row>()
        val d = drill
        if (d != null) {
            rows += Row("◀ ${d.first}", "${d.second.size}곡 · 눌러서 돌아가기", header = true, click = { drill = null; render() })
            songRows(rows, d.second.filter { match(it, q) })
        } else when (tab) {
            0 -> songRows(rows, Library.songs.filter { match(it, q) })
            1 -> Library.songs.filter { match(it, q) }.groupBy { it.albumId }.values
                .sortedWith { a, b -> Library.collator.compare(a[0].album, b[0].album) }
                .forEach { g -> rows += Row(g[0].album, "${g[0].artist} · ${g.size}곡", click = { drill = g[0].album to sortedAlbumSongs(g); render() }) }
            2 -> Library.songs.filter { match(it, q) }.groupBy { it.artist }.entries
                .sortedWith { a, b -> Library.collator.compare(a.key, b.key) }
                .forEach { (k, g) -> rows += Row(k, "${g.map { it.albumId }.distinct().size}개 앨범 · ${g.size}곡", click = { drill = k to sortedAlbumSongs(g); render() }) }
            3 -> Library.songs.filter { match(it, q) }.groupBy { it.folder }.entries
                .sortedWith { a, b -> Library.collator.compare(a.key.substringAfterLast('/'), b.key.substringAfterLast('/')) }
                .forEach { (k, g) ->
                    val name = k.substringAfterLast('/')
                    rows += Row("📁 $name", "${g.size}곡 · $k", click = {
                        drill = name to g.sortedWith { a, b -> Library.collator.compare(a.fileName, b.fileName) }; render()
                    })
                }
            4 -> videoRows(rows, q)
            5 -> queueRows(rows)
            6 -> settingRows(rows)
        }
        adapter.rows = rows
        empty.text = when {
            !Library.loaded -> "노래를 찾는 중…"
            Library.songs.isEmpty() && tab in 0..3 -> "노래가 없어요.\n권한을 허용했는지 확인하거나 설정 › 노래·영상 다시 찾기를 누르세요."
            else -> "찾는 결과가 없어요"
        }
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun videoRows(rows: MutableList<Row>, q: String) {
        rows += Row("🌐 주소로 영상 열기", "인터넷 영상 주소(파일·m3u8)를 바로 재생", accent = true, click = {
            Dialogs.input(this, "영상 주소", "https://…") { VideoActivity.open(this, Uri.parse(it), null) }
        })
        rows += Row("⬇ 주소로 영상 내려받기", "동영상 › 사신뮤직 폴더에 저장", accent = true, click = {
            Dialogs.input(this, "내려받을 영상 주소", "https://…/영상.mp4") { Downloader.start(this, it, false) }
        })
        rows += Row("⬇ 주소로 음악 내려받기", "음악 › 사신뮤직 폴더에 저장", accent = true, click = {
            Dialogs.input(this, "내려받을 음악 주소", "https://…/노래.mp3") { Downloader.start(this, it, true) }
        })
        Library.videos.filter { q.isEmpty() || it.title.lowercase().contains(q) }.forEach { v ->
            rows += Row("🎬 ${v.title}", "${fmt(v.duration)} · ${"%.1f".format(v.size / 1048576f)}MB · ${v.folder.substringAfterLast('/')}",
                click = { VideoActivity.open(this, v.uri, v.title) },
                long = {
                    AlertDialog.Builder(this).setTitle(v.title).setItems(arrayOf("영상으로 보기", "소리만 음악처럼 듣기")) { _, w ->
                        if (w == 0) VideoActivity.open(this, v.uri, v.title)
                        else withCtl { c -> c.setMediaItems(listOf(uriItem(v.uri, v.title))); c.prepare(); c.play() }
                    }.show()
                })
        }
    }

    private fun queueRows(rows: MutableList<Row>) {
        Queues.ensure(this)
        val c = ctl
        rows += Row("대기열 목록", "누르면 바꾸기 · 길게 누르면 비우기/지우기 (최대 ${Queues.MAX}개)", header = true)
        Queues.list.forEachIndexed { i, q ->
            val cur = i == Queues.active
            val n = if (cur && c != null) c.mediaItemCount else q.ids.size
            rows += Row(
                (if (cur) "● " else "○ ") + "대기열 ${i + 1}",
                if (cur) "지금 쓰는 대기열 · ${n}곡" else "${n}곡",
                accent = cur,
                click = { if (!cur) switchQueue(i) },
                long = { queueMenu(i) },
            )
        }
        if (Queues.list.size < Queues.MAX) rows += Row("＋ 새 대기열 만들기", click = {
            withCtl { cc -> Queues.capture(this, cc); Queues.list += Queues.Q(); switchQueue(Queues.list.size - 1) }
        })
        if (c == null) return
        rows += Row("지금 대기열의 노래 (${c.mediaItemCount}곡)", "누르면 재생 · 길게 누르면 옮기기/빼기", header = true)
        for (i in 0 until c.mediaItemCount) {
            val md = c.getMediaItemAt(i).mediaMetadata
            rows += Row("${i + 1}. ${md.title ?: "노래"}", md.artist?.toString() ?: "",
                accent = i == c.currentMediaItemIndex,
                click = { c.seekTo(i, 0); c.play() },
                long = {
                    AlertDialog.Builder(this).setTitle(md.title).setItems(arrayOf("위로", "아래로", "다음 곡으로", "대기열에서 빼기")) { _, w ->
                        when (w) {
                            0 -> if (i > 0) c.moveMediaItem(i, i - 1)
                            1 -> if (i < c.mediaItemCount - 1) c.moveMediaItem(i, i + 1)
                            2 -> c.moveMediaItem(i, if (i > c.currentMediaItemIndex) c.currentMediaItemIndex + 1 else c.currentMediaItemIndex)
                            3 -> c.removeMediaItem(i)
                        }
                    }.show()
                })
        }
    }

    private fun queueMenu(i: Int) {
        val opts = if (Queues.list.size > 1) arrayOf("비우기", "지우기") else arrayOf("비우기")
        AlertDialog.Builder(this).setTitle("대기열 ${i + 1}").setItems(opts) { _, w ->
            withCtl { c ->
                if (w == 0) {
                    if (i == Queues.active) c.clearMediaItems() else Queues.list[i].ids.clear()
                } else {
                    Queues.capture(this, c)
                    val wasActive = i == Queues.active
                    Queues.list.removeAt(i)
                    if (Queues.active > i || Queues.active >= Queues.list.size) Queues.active = (Queues.active - 1).coerceAtLeast(0)
                    if (wasActive) Queues.loadInto(this, c, Queues.active)
                }
                Queues.save(this); render()
            }
        }.show()
    }

    private fun onOff(b: Boolean) = if (b) "켜짐" else "꺼짐"

    private fun settingRows(rows: MutableList<Row>) {
        rows += Row("상단바 가사 (사신플레이어식)", header = true)
        rows += Row("상단바 알림에 가사 표시: ${onOff(Prefs.statusNotify)}", "재생 중 상단바 알림에 지금 가사 줄이 나와요", click = {
            Prefs.statusNotify = !Prefs.statusNotify; PlaybackService.instance?.refreshStatus(); render()
        })
        val canOverlay = Settings.canDrawOverlays(this)
        rows += Row("상단바 위에 가사 띄우기: ${onOff(Prefs.statusOverlay && canOverlay)}",
            if (canOverlay) "화면 맨 위 상단바 자리에 가사를 한 줄로 띄워요" else "누르면 '다른 앱 위에 표시' 권한 화면으로 가요",
            click = {
                if (!Settings.canDrawOverlays(this)) {
                    Prefs.statusOverlay = true
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Prefs.statusOverlay = !Prefs.statusOverlay
                    PlaybackService.instance?.refreshStatus(); render()
                }
            })
        rows += Row("띄운 가사 위치: ${listOf("왼쪽", "가운데", "오른쪽")[Prefs.overlayPos]}", "시계·배터리 표시와 겹치지 않는 쪽으로 고르세요", click = {
            Prefs.overlayPos = (Prefs.overlayPos + 1) % 3; PlaybackService.instance?.refreshStatus(); render()
        })
        rows += Row("띄운 가사 배경: ${if (Prefs.overlayBg) "검정 (상단바를 가림)" else "투명"}", click = {
            Prefs.overlayBg = !Prefs.overlayBg; PlaybackService.instance?.refreshStatus(); render()
        })
        rows += Row("띄운 가사 글자 크기: ${Prefs.overlaySize}", "10~16 사이에서 바뀌어요", click = {
            Prefs.overlaySize = if (Prefs.overlaySize >= 16) 10 else Prefs.overlaySize + 1; PlaybackService.instance?.refreshStatus(); render()
        })

        rows += Row("가사", header = true)
        rows += Row("가사 폴더 고르기", Prefs.lyricsTree?.let { "지금: " + Uri.decode(it).substringAfterLast(':') } ?: "노래와 같은 이름의 .lrc 파일을 찾아요 (노래 파일 안의 가사도 읽어요)",
            click = { treeLauncher.launch(null) })

        rows += Row("재생", header = true)
        rows += Row("수면 타이머", click = { Dialogs.sleep(this) })
        rows += Row("이퀄라이저", click = { Dialogs.equalizer(this) })
        rows += Row("재생 속도", click = { withCtl { Dialogs.speed(this, it) } })

        rows += Row("파일", header = true)
        rows += Row("노래·영상 다시 찾기", "새로 넣은 파일이 안 보일 때", click = { loadLibrary() })
        rows += Row("내려받은 파일 보기", "내려받기 관리자 열기", click = {
            try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) { toast("열 수 없어요") }
        })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        rows += Row("사신뮤직 $ver", "노래 ${Library.songs.size}곡 · 영상 ${Library.videos.size}개", header = true)
    }
}
