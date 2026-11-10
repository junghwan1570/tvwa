package com.myeongwang.music

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.URLUtil

/**
 * 영상·음악 내려받기 (안드로이드 내려받기 관리자 사용 → 알림에 진행 상황 표시).
 * 파일 주소를 바로 받는 방식이라, 저작권자가 허락한 파일이나 내 파일 주소에만 쓰세요.
 * 실시간 재생 목록(m3u8)은 파일 하나가 아니라서 받을 수 없음.
 */
object Downloader {
    fun start(ctx: Context, url: String, music: Boolean) {
        val u = url.trim()
        val uri = Uri.parse(u)
        if (uri.scheme != "http" && uri.scheme != "https") { ctx.toast("인터넷 주소(http로 시작)가 아니에요"); return }
        if (u.substringBefore('?').lowercase().endsWith(".m3u8")) { ctx.toast("실시간 재생 목록(m3u8)은 내려받을 수 없어요. 영상으로 열어서 보세요"); return }
        val name = URLUtil.guessFileName(u, null, null).replace('/', '_')
        val req = DownloadManager.Request(uri)
            .setTitle(name)
            .setDescription("사신뮤직에서 내려받는 중")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(
                if (music) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES, "사신뮤직/$name",
            )
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 29) req.allowScanningByMediaScanner()
        try {
            ctx.getSystemService(DownloadManager::class.java).enqueue(req)
            ctx.toast("내려받기 시작: $name\n(" + (if (music) "음악" else "동영상") + " › 사신뮤직 폴더)")
        } catch (e: Exception) {
            ctx.toast("내려받기를 시작하지 못했어요")
        }
    }
}
