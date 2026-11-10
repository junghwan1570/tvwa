package com.myeongwang.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.text.Collator
import java.util.Locale

data class Song(
    val id: Long, val title: String, val artist: String, val album: String, val albumId: Long,
    val duration: Long, val uri: Uri, val fileName: String, val folder: String, val track: Int,
) {
    val artUri: Uri get() = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
}

data class Video(val id: Long, val title: String, val duration: Long, val uri: Uri, val folder: String, val size: Long)

/** 기기 안의 노래·영상 목록 */
object Library {
    @Volatile var songs: List<Song> = emptyList(); private set
    @Volatile var videos: List<Video> = emptyList(); private set
    @Volatile var loaded = false; private set
    @Volatile private var byId: Map<Long, Song> = emptyMap()
    val collator: Collator = Collator.getInstance(Locale.KOREAN)

    @Synchronized fun load(ctx: Context) {
        songs = querySongs(ctx)
        videos = queryVideos(ctx)
        byId = songs.associateBy { it.id }
        loaded = true
    }

    @Synchronized fun ensure(ctx: Context) { if (!loaded) load(ctx) }

    fun song(mediaId: String?): Song? = mediaId?.toLongOrNull()?.let { byId[it] }

    /** 대기열에 저장된 이름(노래 번호 또는 주소)으로 재생 항목 만들기 */
    fun itemFor(mediaId: String): MediaItem? {
        song(mediaId)?.let { return it.toItem() }
        if (mediaId.contains("://")) return uriItem(Uri.parse(mediaId), null)
        return null
    }

    private fun querySongs(ctx: Context): List<Song> {
        val out = ArrayList<Song>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TRACK,
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val path = c.getString(7) ?: ""
                    out += Song(
                        id = id,
                        title = c.getString(1) ?: c.getString(6) ?: "제목 없음",
                        artist = c.getString(2).let { if (it.isNullOrBlank() || it == "<unknown>") "알 수 없는 가수" else it },
                        album = c.getString(3).let { if (it.isNullOrBlank()) "알 수 없는 앨범" else it },
                        albumId = c.getLong(4),
                        duration = c.getLong(5),
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        fileName = c.getString(6) ?: "",
                        folder = path.substringBeforeLast('/', "알 수 없는 폴더"),
                        track = c.getInt(8) % 1000,
                    )
                }
            }
        } catch (_: Exception) {}
        return out.sortedWith { a, b -> collator.compare(a.title, b.title) }
    }

    private fun queryVideos(ctx: Context): List<Video> {
        val out = ArrayList<Video>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA, MediaStore.Video.Media.SIZE,
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += Video(
                        id = id,
                        title = c.getString(1) ?: "영상",
                        duration = c.getLong(2),
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        folder = (c.getString(3) ?: "").substringBeforeLast('/', ""),
                        size = c.getLong(4),
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }
}

fun Song.toItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
            .setArtworkUri(artUri).setIsPlayable(true).setIsBrowsable(false).build()
    ).build()

fun uriItem(uri: Uri, title: String?): MediaItem = MediaItem.Builder()
    .setMediaId(uri.toString())
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder().setTitle(title ?: Uri.decode(uri.lastPathSegment ?: "") .substringAfterLast('/').ifBlank { "노래" })
            .setIsPlayable(true).setIsBrowsable(false).build()
    ).build()

fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
