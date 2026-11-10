package com.myeongwang.music

import android.content.Context
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

/**
 * 여러 개의 대기열 (뮤직올렛처럼 최대 20개).
 * 지금 쓰는 대기열은 재생기 안에 있고, 나머지는 여기에 저장해 둠. 재생 위치도 기억함.
 */
object Queues {
    const val MAX = 20

    class Q(val ids: MutableList<String> = mutableListOf(), var index: Int = 0, var pos: Long = 0)

    val list = mutableListOf<Q>()
    var active = 0
    private var loaded = false

    @Synchronized fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        val sp = ctx.getSharedPreferences("대기열", Context.MODE_PRIVATE)
        try {
            val o = JSONObject(sp.getString("목록", "{}") ?: "{}")
            val arr = o.optJSONArray("목록") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val q = arr.getJSONObject(i)
                val ids = q.optJSONArray("노래") ?: JSONArray()
                list += Q((0 until ids.length()).map { ids.getString(it) }.toMutableList(), q.optInt("순서"), q.optLong("위치"))
            }
            active = o.optInt("지금")
        } catch (_: Exception) {}
        if (list.isEmpty()) list += Q()
        active = active.coerceIn(0, list.size - 1)
    }

    @Synchronized fun save(ctx: Context) {
        if (!loaded) return
        val arr = JSONArray()
        list.forEach { q ->
            arr.put(JSONObject().put("노래", JSONArray(q.ids)).put("순서", q.index).put("위치", q.pos))
        }
        ctx.getSharedPreferences("대기열", Context.MODE_PRIVATE).edit()
            .putString("목록", JSONObject().put("목록", arr).put("지금", active).toString()).apply()
    }

    /** 재생기의 지금 상태를 '지금 쓰는 대기열'에 옮겨 적기 */
    @Synchronized fun capture(ctx: Context, p: Player) {
        ensure(ctx)
        val q = list[active]
        q.ids.clear()
        for (i in 0 until p.mediaItemCount) q.ids += p.getMediaItemAt(i).mediaId
        q.index = p.currentMediaItemIndex.coerceAtLeast(0)
        q.pos = p.currentPosition.coerceAtLeast(0)
    }

    /** 저장된 대기열을 재생기에 올리기 */
    fun loadInto(ctx: Context, p: Player, i: Int) {
        Library.ensure(ctx)
        val q = list[i]
        val items = q.ids.mapNotNull { Library.itemFor(it) }
        if (items.isEmpty()) { p.clearMediaItems(); return }
        p.setMediaItems(items, q.index.coerceIn(0, items.size - 1), q.pos)
        p.prepare()
    }
}
