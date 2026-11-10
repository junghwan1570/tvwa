package com.myeongwang.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LyricsTest {
    @Test fun 싱크가사_풀기() {
        val l = LyricsLoader.parse("[ar:가수]\n[00:01.50]첫 줄\n[00:10.00][01:00.00]후렴\n[00:05.123]둘째 줄")!!
        assertTrue(l.synced)
        assertEquals(listOf(1500L, 5123L, 10000L, 60000L), l.lines.map { it.t })
        assertEquals("후렴", l.lines[2].text)
        assertEquals(-1, l.indexAt(1000))
        assertEquals(1, l.indexAt(6000))
        assertEquals(3, l.indexAt(99999))
    }

    @Test fun 보정값_적용() {
        val l = LyricsLoader.parse("[offset:500]\n[00:02.00]가")!!
        assertEquals(1500L, l.lines[0].t)
    }

    @Test fun 일반가사() {
        val l = LyricsLoader.parse("\n첫 줄\n둘째 줄\n")!!
        assertFalse(l.synced)
        assertEquals(2, l.lines.size)
    }

    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte())

    @Test fun 노래파일_안의_가사_UTF8() {
        val text = "[00:01.00]안녕하세요".toByteArray(Charsets.UTF_8)
        val body = byteArrayOf(3) + "kor".toByteArray() + byteArrayOf(0) + text
        val frame = "USLT".toByteArray() + syncsafe(body.size) + byteArrayOf(0, 0) + body
        val other = "TIT2".toByteArray() + syncsafe(4) + byteArrayOf(0, 0) + byteArrayOf(3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())
        val tag = other + frame + ByteArray(20)
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray()); out.write(byteArrayOf(4, 0, 0)); out.write(syncsafe(tag.size)); out.write(tag)
        out.write(ByteArray(100))
        val s = LyricsLoader.readEmbedded(ByteArrayInputStream(out.toByteArray()))
        assertEquals("[00:01.00]안녕하세요", s)
    }

    @Test fun 노래파일_안의_가사_UTF16() {
        val text = "가사".toByteArray(Charsets.UTF_16) // 앞에 BOM 포함
        val desc = "설명".toByteArray(Charsets.UTF_16) + byteArrayOf(0, 0)
        val body = byteArrayOf(1) + "kor".toByteArray() + desc + text
        val size = body.size
        val frame = "USLT".toByteArray() + byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) + byteArrayOf(0, 0) + body
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray()); out.write(byteArrayOf(3, 0, 0)); out.write(syncsafe(frame.size)); out.write(frame)
        assertEquals("가사", LyricsLoader.readEmbedded(ByteArrayInputStream(out.toByteArray())))
    }

    @Test fun 플락_가사() {
        fun le(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
        val vendor = "v".toByteArray()
        val c1 = "TITLE=노래".toByteArray(); val c2 = "LYRICS=[00:00.10]가사".toByteArray()
        val vc = le(vendor.size) + vendor + le(2) + le(c1.size) + c1 + le(c2.size) + c2
        val streamInfo = ByteArray(34)
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        out.write(byteArrayOf(0, 0, 0, 34)); out.write(streamInfo)
        out.write(byteArrayOf(0x84.toByte(), (vc.size shr 16).toByte(), (vc.size shr 8).toByte(), vc.size.toByte())); out.write(vc)
        assertEquals("[00:00.10]가사", LyricsLoader.readEmbedded(ByteArrayInputStream(out.toByteArray())))
    }
}
