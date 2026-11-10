package com.myeongwang.music

/** 가사·가사 줄이 바뀌었음을 화면에 알림 */
object NowBus {
    private val listeners = mutableSetOf<() -> Unit>()
    fun add(l: () -> Unit) { listeners += l }
    fun remove(l: () -> Unit) { listeners -= l }
    fun emit() { listeners.toList().forEach { it() } }
}
