package com.amp.player

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

enum class Source { PHONE, PLEX }

/** One song, from this phone or from Plex. */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long,
    val disc: Int,
    val track: Int,
    val source: Source,
    val uri: Uri?,
    val isMp3: Boolean,
    val plex: PlexItem?
)

data class Album(val key: String, val title: String, val artist: String, val songs: List<Song>)
data class ArtistGroup(val key: String, val name: String, val songs: List<Song>)

data class LocalVideo(val uri: Uri, val title: String, val durationMs: Long)

data class BookPart(val uri: Uri, val title: String, val durationMs: Long)

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val parts: List<BookPart>,
    /** True when you added the file yourself with "Add audiobook files". */
    val added: Boolean
) {
    val durationMs: Long get() = parts.sumOf { it.durationMs }
}

private val trackOrder = compareBy<Song>({ it.disc }, { it.track }, { it.title.lowercase() })

fun groupAlbums(songs: List<Song>): List<Album> =
    songs.groupBy { it.album.lowercase() + "|" + it.albumArtist.lowercase() }
        .map { (key, list) ->
            val first = list.first()
            Album(
                key = key,
                title = first.album.ifBlank { "Unknown album" },
                artist = first.albumArtist.ifBlank { first.artist },
                songs = list.sortedWith(trackOrder)
            )
        }
        .sortedBy { it.title.lowercase() }

fun groupArtists(songs: List<Song>): List<ArtistGroup> =
    songs.groupBy { it.artist.lowercase() }
        .map { (key, list) ->
            ArtistGroup(
                key = key,
                name = list.first().artist,
                songs = list.sortedWith(
                    compareBy<Song>({ it.album.lowercase() }, { it.disc }, { it.track }, { it.title.lowercase() })
                )
            )
        }
        .sortedBy { it.name.lowercase() }

fun PlexItem.toSong() = Song(
    id = "plex:$ratingKey",
    title = title,
    artist = artist.ifBlank { "Unknown artist" },
    album = album,
    albumArtist = albumArtist.ifBlank { artist },
    durationMs = durationMs,
    disc = parentIndex,
    track = index,
    source = Source.PLEX,
    uri = null,
    isMp3 = false,
    plex = this
)

object LocalLibrary {

    /** Files that count as audiobooks rather than music. */
    private const val AUDIOBOOK_WHERE =
        "(COALESCE(is_audiobook, 0) != 0 OR _display_name LIKE '%.m4b' " +
            "OR COALESCE(relative_path, '') LIKE '%audiobook%')"

    private val audioUri get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun loadSongs(context: Context): List<Song> {
        val collection = audioUri
        val projection = arrayOf("_id", "title", "artist", "album", "album_artist", "duration", "mime_type", "track")
        val result = mutableListOf<Song>()
        context.contentResolver.query(
            collection, projection,
            "is_music != 0 AND NOT $AUDIOBOOK_WHERE", null,
            "title COLLATE NOCASE ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow("_id")
            val titleCol = c.getColumnIndexOrThrow("title")
            val artistCol = c.getColumnIndexOrThrow("artist")
            val albumCol = c.getColumnIndexOrThrow("album")
            val albumArtistCol = c.getColumnIndex("album_artist")
            val durCol = c.getColumnIndexOrThrow("duration")
            val mimeCol = c.getColumnIndexOrThrow("mime_type")
            val trackCol = c.getColumnIndex("track")
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                val artist = clean(c.getString(artistCol), "Unknown artist")
                val mime = c.getString(mimeCol) ?: ""
                val trackRaw = if (trackCol >= 0) c.getInt(trackCol) else 0
                val albumArtist = if (albumArtistCol >= 0) c.getString(albumArtistCol) else null
                result += Song(
                    id = "local:$uri",
                    title = c.getString(titleCol)?.ifBlank { null } ?: "Unknown title",
                    artist = artist,
                    album = c.getString(albumCol) ?: "",
                    albumArtist = albumArtist?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: artist,
                    durationMs = c.getLong(durCol),
                    disc = trackRaw / 1000,
                    track = trackRaw % 1000,
                    source = Source.PHONE,
                    uri = uri,
                    isMp3 = mime == "audio/mpeg" || mime == "audio/mp3",
                    plex = null
                )
            }
        }
        return result
    }

    fun loadVideos(context: Context): List<LocalVideo> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf("_id", "title", "_display_name", "duration")
        val result = mutableListOf<LocalVideo>()
        context.contentResolver.query(
            collection, projection, null, null, "title COLLATE NOCASE ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow("_id")
            val titleCol = c.getColumnIndexOrThrow("title")
            val nameCol = c.getColumnIndexOrThrow("_display_name")
            val durCol = c.getColumnIndexOrThrow("duration")
            while (c.moveToNext()) {
                val title = c.getString(titleCol)?.ifBlank { null }
                    ?: c.getString(nameCol) ?: "Unknown video"
                result += LocalVideo(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    title = title,
                    durationMs = c.getLong(durCol)
                )
            }
        }
        return result
    }

    private data class BookFile(
        val uri: Uri,
        val title: String,
        val album: String,
        val artist: String,
        val durationMs: Long,
        val track: Int,
        val displayName: String
    )

    /**
     * Audiobooks found on the phone, plus files you added yourself.
     * Files from the same album are grouped into one book with chapters.
     */
    fun loadBooks(context: Context, added: Set<String>, hasPermission: Boolean): List<Book> {
        val files = if (hasPermission) runCatching { queryBookFiles(context) }.getOrDefault(emptyList()) else emptyList()
        val books = files
            .groupBy { f ->
                if (f.album.isBlank()) "file:${f.uri}" else "album:${f.album.lowercase()}|${f.artist.lowercase()}"
            }
            .map { (key, list) ->
                val sorted = list.sortedWith(compareBy<BookFile>({ it.track }, { it.displayName.lowercase() }))
                val first = sorted.first()
                Book(
                    id = "ms:$key",
                    title = first.album.ifBlank { first.title },
                    author = first.artist,
                    parts = sorted.map { BookPart(it.uri, it.title, it.durationMs) },
                    added = false
                )
            }
            .toMutableList()
        for (s in added) {
            runCatching { books += documentBook(context, Uri.parse(s)) }
        }
        return books.sortedBy { it.title.lowercase() }
    }

    private fun queryBookFiles(context: Context): List<BookFile> {
        val collection = audioUri
        val projection = arrayOf("_id", "title", "album", "artist", "duration", "track", "_display_name")
        val result = mutableListOf<BookFile>()
        context.contentResolver.query(
            collection, projection, AUDIOBOOK_WHERE, null, null
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow("_id")
            val titleCol = c.getColumnIndexOrThrow("title")
            val albumCol = c.getColumnIndexOrThrow("album")
            val artistCol = c.getColumnIndexOrThrow("artist")
            val durCol = c.getColumnIndexOrThrow("duration")
            val trackCol = c.getColumnIndex("track")
            val nameCol = c.getColumnIndexOrThrow("_display_name")
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: ""
                result += BookFile(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    title = c.getString(titleCol)?.ifBlank { null } ?: name.substringBeforeLast('.'),
                    album = c.getString(albumCol)?.takeIf { it != "<unknown>" } ?: "",
                    artist = clean(c.getString(artistCol), "Unknown author"),
                    durationMs = c.getLong(durCol),
                    track = if (trackCol >= 0) c.getInt(trackCol) else 0,
                    displayName = name
                )
            }
        }
        return result
    }

    /** A single file you picked yourself, read with Android's metadata reader. */
    private fun documentBook(context: Context, uri: Uri): Book {
        var name = "Audiobook"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        var title: String? = null
        var author: String? = null
        var duration = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            author = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            // Keep the file name if the metadata can't be read.
        } finally {
            runCatching { r.release() }
        }
        val bookTitle = title?.ifBlank { null } ?: name.substringBeforeLast('.')
        return Book(
            id = "doc:$uri",
            title = bookTitle,
            author = author?.ifBlank { null } ?: "Unknown author",
            parts = listOf(BookPart(uri, bookTitle, duration)),
            added = true
        )
    }

    private fun clean(raw: String?, fallback: String): String =
        if (raw.isNullOrBlank() || raw == "<unknown>") fallback else raw
}
