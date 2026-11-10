package com.myeongwang.music

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.media3.common.Player

object Dialogs {
    fun sleep(ctx: Context) {
        val svc = PlaybackService.instance ?: run { ctx.toast("먼저 노래를 재생하세요"); return }
        val mins = intArrayOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120)
        val left = if (svc.sleepAt > 0) " (남은 시간 " + fmt(svc.sleepAt - System.currentTimeMillis()) + ")" else ""
        AlertDialog.Builder(ctx)
            .setTitle("수면 타이머$left")
            .setItems(mins.map { if (it == 0) "끄기" else "${it}분 뒤 멈춤" }.toTypedArray()) { _, w ->
                svc.setSleep(mins[w])
                ctx.toast(if (mins[w] == 0) "수면 타이머를 껐어요" else "${mins[w]}분 뒤에 멈춰요")
            }.show()
    }

    fun speed(ctx: Context, p: Player) {
        val v = floatArrayOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
        AlertDialog.Builder(ctx)
            .setTitle("재생 속도 (지금 ${p.playbackParameters.speed}배)")
            .setItems(v.map { "${it}배" }.toTypedArray()) { _, w -> p.setPlaybackSpeed(v[w]); Prefs.speed = v[w] }
            .show()
    }

    private fun hz(mHz: Int): String {
        val hz = mHz / 1000
        return if (hz >= 1000) "%.1f킬로헤르츠".format(hz / 1000f) else "${hz}헤르츠"
    }

    fun equalizer(ctx: Context) {
        val svc = PlaybackService.instance ?: run { ctx.toast("먼저 노래를 재생하세요"); return }
        val e = svc.equalizer() ?: run { ctx.toast("이 기기는 이퀄라이저를 쓸 수 없어요"); return }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8)) }
        val on = CheckBox(ctx).apply { text = "이퀄라이저 켜기"; isChecked = Prefs.eqOn; setTextColor(Col.TEXT) }
        box.addView(on)
        val range = e.bandLevelRange
        val min = range[0].toInt(); val max = range[1].toInt()
        val bars = ArrayList<SeekBar>()
        for (b in 0 until e.numberOfBands) {
            val band = b.toShort()
            val lab = ctx.label("", 13f, Col.SUB)
            fun show(level: Int) { lab.text = "${hz(e.getCenterFreq(band))}  ${if (level >= 0) "+" else ""}${"%.1f".format(level / 100f)}데시벨" }
            val sb = SeekBar(ctx).apply {
                this.max = max - min
                progress = e.getBandLevel(band) - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                        show(p + min)
                        if (user) try { e.setBandLevel(band, (p + min).toShort()) } catch (_: Throwable) {}
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            show(e.getBandLevel(band).toInt())
            bars += sb
            box.addView(lab); box.addView(sb)
        }
        on.setOnCheckedChangeListener { _, c -> try { e.enabled = c } catch (_: Throwable) {} }
        AlertDialog.Builder(ctx)
            .setTitle("이퀄라이저")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                Prefs.eqOn = on.isChecked
                Prefs.eqLevels = bars.joinToString(",") { (it.progress + min).toString() }
                svc.applyEq()
            }
            .setNeutralButton("초기화") { _, _ ->
                Prefs.eqLevels = (0 until e.numberOfBands).joinToString(",") { "0" }
                svc.applyEq()
            }
            .setOnCancelListener { svc.applyEq() }
            .show()
    }

    fun input(ctx: Context, title: String, hint: String, ok: (String) -> Unit) {
        val et = EditText(ctx).apply {
            this.hint = hint
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setTextColor(Col.TEXT); setHintTextColor(Col.SUB)
        }
        val box = LinearLayout(ctx).apply { setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0); addView(et, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(ctx).setTitle(title).setView(box)
            .setPositiveButton("확인") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }
            .setNegativeButton("취소", null).show()
    }

    /** 공유받은 주소로 무엇을 할지 */
    fun urlAction(ctx: Context, url: String) {
        AlertDialog.Builder(ctx).setTitle(url)
            .setItems(arrayOf("영상으로 열기", "동영상 폴더로 내려받기", "음악 폴더로 내려받기")) { _, w ->
                when (w) {
                    0 -> VideoActivity.open(ctx, android.net.Uri.parse(url), null)
                    1 -> Downloader.start(ctx, url, false)
                    2 -> Downloader.start(ctx, url, true)
                }
            }.show()
    }
}
