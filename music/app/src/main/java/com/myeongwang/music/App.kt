package com.myeongwang.music

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}

/** 설정값 모음 */
object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) { sp = ctx.getSharedPreferences("설정", Context.MODE_PRIVATE) }
    val raw: SharedPreferences get() = sp

    /** 상단바 알림에 지금 가사 줄 표시 */
    var statusNotify: Boolean
        get() = sp.getBoolean("상단바알림", true)
        set(v) = sp.edit().putBoolean("상단바알림", v).apply()

    /** 상단바 위에 가사를 직접 띄우기 (다른 앱 위에 표시 권한 필요) */
    var statusOverlay: Boolean
        get() = sp.getBoolean("상단바띄우기", false)
        set(v) = sp.edit().putBoolean("상단바띄우기", v).apply()

    /** 띄운 가사 위치: 0 왼쪽 · 1 가운데 · 2 오른쪽 */
    var overlayPos: Int
        get() = sp.getInt("띄운가사위치", 1)
        set(v) = sp.edit().putInt("띄운가사위치", v).apply()

    var overlayBg: Boolean
        get() = sp.getBoolean("띄운가사배경", false)
        set(v) = sp.edit().putBoolean("띄운가사배경", v).apply()

    var overlaySize: Int
        get() = sp.getInt("띄운가사크기", 12)
        set(v) = sp.edit().putInt("띄운가사크기", v).apply()

    /** 가사 파일(.lrc)을 둔 폴더 */
    var lyricsTree: String?
        get() = sp.getString("가사폴더", null)
        set(v) = sp.edit().putString("가사폴더", v).apply()

    var eqOn: Boolean
        get() = sp.getBoolean("이퀄라이저켜짐", false)
        set(v) = sp.edit().putBoolean("이퀄라이저켜짐", v).apply()

    /** 이퀄라이저 단계별 값 (쉼표로 구분) */
    var eqLevels: String
        get() = sp.getString("이퀄라이저값", "") ?: ""
        set(v) = sp.edit().putString("이퀄라이저값", v).apply()

    var speed: Float
        get() = sp.getFloat("재생속도", 1f)
        set(v) = sp.edit().putFloat("재생속도", v).apply()
}
