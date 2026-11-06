package com.amp.player

import android.content.Context
import android.net.Uri
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class Id3Fields(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    val track: String = "",
    val genre: String = "",
    val composer: String = "",
    val comment: String = ""
)

class EditedFile(val file: File, val warning: String?)

/**
 * Reads and writes ID3 tags. Android doesn't let apps edit music files in
 * place, so we copy the file, edit the copy, then write it back.
 */
class TagEditor(private val context: Context) {

    private val inFile get() = File(context.cacheDir, "tag_in.mp3")
    private val outFile get() = File(context.cacheDir, "tag_out.mp3")

    private fun copyIn(uri: Uri): File {
        val target = inFile
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the file")
        input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
        return target
    }

    suspend fun read(uri: Uri): Id3Fields = withContext(Dispatchers.IO) {
        val mp3 = Mp3File(copyIn(uri).absolutePath)
        val v2 = if (mp3.hasId3v2Tag()) mp3.id3v2Tag else null
        val v1 = if (mp3.hasId3v1Tag()) mp3.id3v1Tag else null
        Id3Fields(
            title = pick(v2?.title, v1?.title),
            artist = pick(v2?.artist, v1?.artist),
            album = pick(v2?.album, v1?.album),
            albumArtist = pick(v2?.albumArtist, null),
            year = pick(v2?.year, v1?.year),
            track = pick(v2?.track, v1?.track),
            genre = pick(v2?.genreDescription, v1?.genreDescription),
            composer = pick(v2?.composer, null),
            comment = pick(v2?.comment, v1?.comment)
        )
    }

    /** Blank fields are left as they were. */
    suspend fun buildEditedFile(uri: Uri, f: Id3Fields): EditedFile = withContext(Dispatchers.IO) {
        val mp3 = Mp3File(copyIn(uri).absolutePath)
        val tag = if (mp3.hasId3v2Tag()) mp3.id3v2Tag else ID3v24Tag().also { mp3.id3v2Tag = it }

        if (f.title.isNotBlank()) tag.title = f.title.trim()
        if (f.artist.isNotBlank()) tag.artist = f.artist.trim()
        if (f.album.isNotBlank()) tag.album = f.album.trim()
        if (f.albumArtist.isNotBlank()) tag.albumArtist = f.albumArtist.trim()
        if (f.year.isNotBlank()) tag.year = f.year.trim()
        if (f.track.isNotBlank()) tag.track = f.track.trim()
        if (f.composer.isNotBlank()) tag.composer = f.composer.trim()
        if (f.comment.isNotBlank()) tag.comment = f.comment.trim()

        var warning: String? = null
        val genre = f.genre.trim()
        if (genre.isNotBlank() && !genre.equals(tag.genreDescription ?: "", ignoreCase = true)) {
            try {
                tag.genreDescription = genre
            } catch (e: IllegalArgumentException) {
                warning = "Genre was not changed, because $genre is not a standard ID3 genre name."
            }
        }

        // Keep the old-style ID3v1 tag in step, if the file has one.
        if (mp3.hasId3v1Tag()) {
            runCatching {
                val v1 = mp3.id3v1Tag
                if (f.title.isNotBlank()) v1.title = f.title.trim().take(30)
                if (f.artist.isNotBlank()) v1.artist = f.artist.trim().take(30)
                if (f.album.isNotBlank()) v1.album = f.album.trim().take(30)
                if (f.year.isNotBlank()) v1.year = f.year.trim().take(4)
            }
        }

        val out = outFile
        out.delete()
        mp3.save(out.absolutePath)
        EditedFile(out, warning)
    }

    /** Returns false if Android needs to ask you for permission first. */
    suspend fun tryWriteBack(uri: Uri, file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            writeBackInternal(uri, file)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    suspend fun writeBack(uri: Uri, file: File) {
        withContext(Dispatchers.IO) { writeBackInternal(uri, file) }
    }

    private fun writeBackInternal(uri: Uri, file: File) {
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not open the file for writing")
        out.use { o -> file.inputStream().use { i -> i.copyTo(o) } }
    }

    fun cleanup() {
        inFile.delete()
        outFile.delete()
    }

    private fun pick(a: String?, b: String?): String =
        a?.takeIf { it.isNotBlank() } ?: b?.takeIf { it.isNotBlank() } ?: ""
}
