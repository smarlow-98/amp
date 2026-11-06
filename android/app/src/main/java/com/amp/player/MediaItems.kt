package com.amp.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.Locale

const val EXTRA_IS_VIDEO = "amp.isVideo"
const val EXTRA_LOCAL_MP3 = "amp.localMp3"

fun buildMediaItem(
    id: String,
    uri: Uri,
    title: String,
    artist: String?,
    album: String?,
    isVideo: Boolean,
    localMp3: Uri?
): MediaItem {
    val extras = Bundle().apply {
        putBoolean(EXTRA_IS_VIDEO, isVideo)
        if (localMp3 != null) putString(EXTRA_LOCAL_MP3, localMp3.toString())
    }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setExtras(extras)
                .build()
        )
        .build()
}

/** "3:05" style, for the screen. */
fun clockTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

/** "3 minutes 5 seconds" style, for TalkBack. */
fun spokenTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    val parts = mutableListOf<String>()
    if (h > 0) parts += plural(h, "hour")
    if (m > 0) parts += plural(m, "minute")
    if (sec > 0 || parts.isEmpty()) parts += plural(sec, "second")
    return parts.joinToString(" ")
}

private fun plural(n: Long, word: String) = if (n == 1L) "1 $word" else "$n ${word}s"
