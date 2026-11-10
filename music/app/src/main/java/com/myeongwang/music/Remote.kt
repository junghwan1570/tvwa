package com.myeongwang.music

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/** 화면 → 재생 서비스 연결 (하나만 만들어 같이 씀) */
object Remote {
    var controller: MediaController? = null; private set
    private var connecting = false
    private val waiting = mutableListOf<(MediaController) -> Unit>()

    fun get(ctx: Context, cb: (MediaController) -> Unit) {
        controller?.let { if (it.isConnected) { cb(it); return } }
        controller = null
        waiting += cb
        if (connecting) return
        connecting = true
        val app = ctx.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        f.addListener({
            connecting = false
            try {
                val c = f.get()
                controller = c
                val list = waiting.toList()
                waiting.clear()
                list.forEach { it(c) }
            } catch (_: Exception) { waiting.clear() }
        }, ContextCompat.getMainExecutor(app))
    }
}
