package com.amp.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class LocalTrack(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mime: String,
    val isVideo: Boolean
) {
    val isMp3: Boolean get() = !isVideo && (mime == "audio/mpeg" || mime == "audio/mp3")

    fun toMediaItem() = buildMediaItem(
        id = uri.toString(),
        uri = uri,
        title = title,
        artist = if (isVideo) null else artist,
        album = album.ifBlank { null },
        isVideo = isVideo,
        localMp3 = if (isMp3) uri else null
    )
}

object LocalLibrary {

    fun loadAudio(context: Context): List<LocalTrack> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )
        val result = mutableListOf<LocalTrack>()
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (c.moveToNext()) {
                result += LocalTrack(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    title = c.getString(titleCol)?.ifBlank { null } ?: "Unknown title",
                    artist = cleanArtist(c.getString(artistCol)),
                    album = c.getString(albumCol) ?: "",
                    durationMs = c.getLong(durCol),
                    mime = c.getString(mimeCol) ?: "",
                    isVideo = false
                )
            }
        }
        return result
    }

    fun loadVideo(context: Context): List<LocalTrack> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE
        )
        val result = mutableListOf<LocalTrack>()
        context.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Video.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val title = c.getString(titleCol)?.ifBlank { null }
                    ?: c.getString(nameCol) ?: "Unknown video"
                result += LocalTrack(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    title = title,
                    artist = "",
                    album = "",
                    durationMs = c.getLong(durCol),
                    mime = c.getString(mimeCol) ?: "",
                    isVideo = true
                )
            }
        }
        return result
    }

    private fun cleanArtist(raw: String?): String =
        if (raw.isNullOrBlank() || raw == "<unknown>") "Unknown artist" else raw
}
