package com.myeongwang.music

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 사신플레이어식 "상단바 가사": 화면 맨 위(상단바 자리)에 지금 가사 줄을 얇게 띄움.
 * 다른 앱 위에 표시 권한이 있어야 하고, 터치는 그대로 아래로 통과함.
 */
class StatusOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var styleKey = ""

    fun canShow() = Settings.canDrawOverlays(ctx)

    fun show(text: String) {
        if (!canShow()) { hide(); return }
        val key = "${Prefs.overlayPos}/${Prefs.overlayBg}/${Prefs.overlaySize}"
        if (key != styleKey) { hide(); styleKey = key }
        val v = view ?: create() ?: return
        if (v.text != text) { v.text = text; v.isSelected = true }
    }

    private fun create(): TextView? {
        val res = ctx.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (id > 0) res.getDimensionPixelSize(id) else (24 * res.displayMetrics.density).toInt()
        val pad = (12 * res.displayMetrics.density).toInt()
        val tv = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.overlaySize.toFloat())
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            setPadding(pad, 0, pad, 0)
            gravity = Gravity.CENTER_VERTICAL or when (Prefs.overlayPos) { 0 -> Gravity.START; 2 -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
            setBackgroundColor(if (Prefs.overlayBg) Color.BLACK else Color.TRANSPARENT)
        }
        @Suppress("DEPRECATION")
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            // 안드로이드 12부터: 불투명도 0.8 이하여야 아래 화면 터치가 막히지 않음
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try { wm.addView(tv, lp); view = tv; tv } catch (_: Exception) { null }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }
}
