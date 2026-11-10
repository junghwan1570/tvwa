package com.myeongwang.music

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.nio.charset.Charset

/** 가사. 시간이 붙은 가사(싱크 가사)면 synced = true */
class Lyrics(val lines: List<Line>, val synced: Boolean) {
    data class Line(val t: Long, val text: String)

    /** 재생 위치에 맞는 줄 번호 (없으면 -1) */
    fun indexAt(pos: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val m = (lo + hi) / 2
            if (lines[m].t <= pos) { ans = m; lo = m + 1 } else hi = m - 1
        }
        return ans
    }
}

/**
 * 가사 찾기 순서
 * 1) 설정에서 고른 가사 폴더의 같은 이름 .lrc 파일 (또는 "가수 - 제목.lrc")
 * 2) 노래 파일 안에 들어 있는 가사 (MP3의 USLT, FLAC의 LYRICS)
 */
object LyricsLoader {
    private val ms949: Charset = try { Charset.forName("MS949") } catch (_: Exception) { Charsets.ISO_8859_1 }
    private var indexTree: String? = null
    private var index: Map<String, Uri> = emptyMap()

    @Synchronized fun resetIndex() { indexTree = null; index = emptyMap() }

    fun load(ctx: Context, mediaId: String, uri: Uri?, title: String?, artist: String?): Lyrics? {
        val song = Library.song(mediaId)
        val keys = buildList {
            song?.fileName?.substringBeforeLast('.')?.let { add(it) }
            if (!artist.isNullOrBlank() && !title.isNullOrBlank()) add("$artist - $title")
            if (!title.isNullOrBlank()) add(title)
        }.map { it.lowercase().trim() }
        folderIndex(ctx)?.let { idx ->
            for (k in keys) idx[k]?.let { f -> readText(ctx, f)?.let { parse(it) }?.let { return it } }
        }
        val src = song?.uri ?: uri ?: return null
        if (src.scheme == "http" || src.scheme == "https") return null
        return try {
            ctx.contentResolver.openInputStream(src)?.use { readEmbedded(it) }?.let { parse(it) }
        } catch (_: Exception) { null }
    }

    @Synchronized private fun folderIndex(ctx: Context): Map<String, Uri>? {
        val tree = Prefs.lyricsTree ?: return null
        if (tree == indexTree) return index
        val map = HashMap<String, Uri>()
        try {
            DocumentFile.fromTreeUri(ctx, Uri.parse(tree))?.let { walk(it, map, 0) }
        } catch (_: Exception) {}
        indexTree = tree; index = map
        return map
    }

    private fun walk(d: DocumentFile, map: HashMap<String, Uri>, depth: Int) {
        for (f in d.listFiles()) {
            if (f.isDirectory) { if (depth < 3) walk(f, map, depth + 1); continue }
            val n = f.name ?: continue
            val ext = n.substringAfterLast('.', "").lowercase()
            if (ext == "lrc" || ext == "txt") map.putIfAbsent(n.substringBeforeLast('.').lowercase().trim(), f.uri)
        }
    }

    private fun readText(ctx: Context, uri: Uri): String? {
        return try {
            val b = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            var s = String(b, Charsets.UTF_8)
            if (s.contains(0xFFFD.toChar())) s = String(b, ms949)
            s.removePrefix(0xFEFF.toChar().toString())
        } catch (_: Exception) { null }
    }

    // ───── 가사 글 풀기 ─────
    private val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("""^\[[a-zA-Z]+:.*]$""")

    fun parse(text: String): Lyrics? {
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val timed = ArrayList<Lyrics.Line>()
        val plain = ArrayList<Lyrics.Line>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trim()
            var rest = line
            val times = ArrayList<Long>()
            while (true) {
                val m = tag.find(rest) ?: break
                if (m.range.first != 0) break
                val (mm, ss, fr) = m.destructured
                val frac = when (fr.length) { 0 -> 0L; 1 -> fr.toLong() * 100; 2 -> fr.toLong() * 10; else -> fr.take(3).toLong() }
                times += mm.toLong() * 60000 + ss.toLong() * 1000 + frac
                rest = rest.substring(m.range.last + 1)
            }
            if (times.isNotEmpty()) times.forEach { timed += Lyrics.Line((it - offset).coerceAtLeast(0), rest.trim()) }
            else if (!metaTag.matches(line)) plain += Lyrics.Line(0, line)
        }
        if (timed.isNotEmpty()) return Lyrics(timed.sortedBy { it.t }, true)
        val p = plain.dropWhile { it.text.isEmpty() }.dropLastWhile { it.text.isEmpty() }
        return if (p.isEmpty()) null else Lyrics(p, false)
    }

    // ───── 노래 파일 안의 가사 ─────
    private fun readFully(s: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = s.read(b, off, n - off); if (r < 0) break; off += r }
        return if (off == n) b else b.copyOf(off)
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k <= 0) { if (s.read() < 0) return; left-- } else left -= k
        }
    }

    fun readEmbedded(s: InputStream): String? {
        val head = readFully(s, 4)
        if (head.size < 4) return null
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) return readId3(head, s)
        if (String(head, Charsets.ISO_8859_1) == "fLaC") return readFlac(s)
        return null
    }

    private fun syncsafe(b: ByteArray, o: Int) =
        (b[o].toInt() and 0x7f shl 21) or (b[o + 1].toInt() and 0x7f shl 14) or (b[o + 2].toInt() and 0x7f shl 7) or (b[o + 3].toInt() and 0x7f)

    private fun be32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff shl 24) or (b[o + 1].toInt() and 0xff shl 16) or (b[o + 2].toInt() and 0xff shl 8) or (b[o + 3].toInt() and 0xff)

    private fun readId3(head: ByteArray, s: InputStream): String? {
        val rest = readFully(s, 6)
        if (rest.size < 6) return null
        val h = head + rest
        val ver = h[3].toInt()
        if (ver < 3) return null
        val flags = h[5].toInt()
        val size = syncsafe(h, 6).coerceAtMost(32 * 1024 * 1024)
        var tag = readFully(s, size)
        if (ver == 3 && flags and 0x80 != 0) tag = unsync(tag)
        var p = 0
        if (flags and 0x40 != 0 && tag.size >= 4) p = if (ver == 4) syncsafe(tag, 0) else be32(tag, 0) + 4
        while (p + 10 <= tag.size) {
            if (tag[p].toInt() == 0) break
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            val fs = if (ver == 4) syncsafe(tag, p + 4) else be32(tag, p + 4)
            if (fs <= 0 || p + 10 + fs > tag.size) break
            if (id == "USLT") return uslt(tag, p + 10, fs)
            p += 10 + fs
        }
        return null
    }

    private fun unsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun uslt(b: ByteArray, off: Int, len: Int): String? {
        if (len < 5) return null
        val enc = b[off].toInt()
        val end = off + len
        var i = off + 4 // 글자 방식 1 + 언어 3
        if (enc == 1 || enc == 2) {
            while (i + 1 < end && !(b[i].toInt() == 0 && b[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < end && b[i].toInt() != 0) i++
            i += 1
        }
        if (i >= end) return null
        return decode(b, i, end - i, enc).trimEnd('\u0000').ifBlank { null }
    }

    private fun decode(b: ByteArray, off: Int, len: Int, enc: Int): String = when (enc) {
        1 -> String(b, off, len, Charsets.UTF_16)
        2 -> String(b, off, len, Charsets.UTF_16BE)
        3 -> String(b, off, len, Charsets.UTF_8)
        else -> {
            // 한국 노래 파일은 이 칸에 완성형(MS949) 글자를 넣은 경우가 많음
            val high = (off until off + len).any { b[it].toInt() and 0x80 != 0 }
            String(b, off, len, if (high) ms949 else Charsets.ISO_8859_1)
        }
    }

    private fun readFlac(s: InputStream): String? {
        while (true) {
            val h = readFully(s, 4)
            if (h.size < 4) return null
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7f
            val len = (h[1].toInt() and 0xff shl 16) or (h[2].toInt() and 0xff shl 8) or (h[3].toInt() and 0xff)
            if (type == 4) {
                val b = readFully(s, len)
                return vorbis(b)
            }
            skipFully(s, len.toLong())
            if (last) return null
        }
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or (b[o + 1].toInt() and 0xff shl 8) or (b[o + 2].toInt() and 0xff shl 16) or (b[o + 3].toInt() and 0xff shl 24)

    private fun vorbis(b: ByteArray): String? {
        var p = 0
        if (b.size < 8) return null
        p += 4 + le32(b, 0)
        if (p + 4 > b.size) return null
        val n = le32(b, p); p += 4
        var unsynced: String? = null
        repeat(n) {
            if (p + 4 > b.size) return unsynced
            val l = le32(b, p); p += 4
            if (l < 0 || p + l > b.size) return unsynced
            val kv = String(b, p, l, Charsets.UTF_8); p += l
            val k = kv.substringBefore('=').uppercase()
            if (k == "LYRICS") return kv.substringAfter('=')
            if (k == "UNSYNCEDLYRICS") unsynced = kv.substringAfter('=')
        }
        return unsynced
    }
}
