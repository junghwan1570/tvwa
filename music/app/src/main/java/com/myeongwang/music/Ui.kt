package com.myeongwang.music

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

object Col {
    val BG = Color.parseColor("#121212")
    val CARD = Color.parseColor("#1E1E1E")
    val TEXT = Color.parseColor("#EEEEEE")
    val SUB = Color.parseColor("#9E9E9E")
    val ACCENT = Color.parseColor("#FF5252")
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

fun Context.label(s: String, size: Float = 15f, color: Int = Col.TEXT, bold: Boolean = false) = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

/** 누를 수 있는 글자 단추 */
fun Context.btn(s: String, size: Float = 15f, onClick: () -> Unit) = label(s, size).apply {
    gravity = Gravity.CENTER
    setPadding(dp(14), dp(10), dp(14), dp(10))
    isClickable = true
    val tv = TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    setBackgroundResource(tv.resourceId)
    setOnClickListener { onClick() }
}

class Row(
    val title: String,
    val sub: String = "",
    val accent: Boolean = false,
    val header: Boolean = false,
    val click: (() -> Unit)? = null,
    val long: (() -> Unit)? = null,
)

/** 목록 한 칸 = 제목 + 작은 설명 */
class RowAdapter : RecyclerView.Adapter<RowAdapter.H>() {
    var rows: List<Row> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class H(val box: LinearLayout, val t: TextView, val s: TextView) : RecyclerView.ViewHolder(box)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
        val c = parent.context
        val t = c.label("", 16f).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        val s = c.label("", 12.5f, Col.SUB).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(10))
            val tv = TypedValue()
            c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            addView(t); addView(s)
        }
        return H(box, t, s)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: H, i: Int) {
        val r = rows[i]
        h.t.text = r.title
        h.t.setTextColor(if (r.accent) Col.ACCENT else if (r.header) Col.SUB else Col.TEXT)
        h.t.setTypeface(null, if (r.header) Typeface.BOLD else Typeface.NORMAL)
        h.s.text = r.sub
        h.s.visibility = if (r.sub.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        h.box.setOnClickListener(r.click?.let { f -> { _: android.view.View -> f() } })
        h.box.isClickable = r.click != null
        h.box.setOnLongClickListener(r.long?.let { f -> { _: android.view.View -> f(); true } })
    }
}
