package com.amp.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** A chapter inside one audiobook file. */
data class FileChapter(val title: String, val startMs: Long)

/**
 * Reads the chapter list stored inside .m4b, .m4a and .mp4 files.
 * Handles both kinds of chapters: a QuickTime chapter text track (iTunes,
 * Audible conversions, ffmpeg) and Nero chapters (the "chpl" box).
 */
object Mp4Chapters {

    fun read(context: Context, uri: Uri): List<FileChapter> = runCatching {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            parse(stream.channel)
        }
    }.getOrDefault(emptyList())

    private class Box(val type: String, val start: Long, val end: Long)

    private class Reader(val ch: FileChannel) {
        val size: Long = ch.size()

        fun read(pos: Long, len: Int): ByteBuffer {
            val b = ByteBuffer.allocate(len.coerceAtLeast(0))
            var p = pos
            while (b.hasRemaining()) {
                val n = ch.read(b, p)
                if (n <= 0) break
                p += n
            }
            b.flip()
            return b
        }

        fun children(start: Long, end: Long): List<Box> {
            val out = mutableListOf<Box>()
            var pos = start
            while (pos + 8 <= end) {
                val h = read(pos, 16)
                if (h.remaining() < 8) break
                var size = h.int.toLong() and 0xffffffffL
                val t = ByteArray(4)
                h.get(t)
                var header = 8
                if (size == 1L) {
                    if (h.remaining() < 8) break
                    size = h.long
                    header = 16
                } else if (size == 0L) {
                    size = end - pos
                }
                if (size < header) break
                out += Box(String(t, Charsets.ISO_8859_1), pos + header, minOf(pos + size, end))
                pos += size
            }
            return out
        }

        fun child(parent: Box, type: String): Box? = children(parent.start, parent.end).firstOrNull { it.type == type }

        fun bytes(box: Box, limit: Int = 4_000_000): ByteBuffer =
            read(box.start, (box.end - box.start).coerceAtMost(limit.toLong()).toInt())
    }

    private fun parse(ch: FileChannel): List<FileChapter> {
        val r = Reader(ch)
        val moov = r.children(0, r.size).firstOrNull { it.type == "moov" } ?: return emptyList()
        val fromTrack = runCatching { chapterTrack(r, moov) }.getOrDefault(emptyList())
        if (fromTrack.isNotEmpty()) return fromTrack
        return runCatching { neroChapters(r, moov) }.getOrDefault(emptyList())
    }

    // ---------- Nero chapters (moov/udta/chpl) ----------

    private fun neroChapters(r: Reader, moov: Box): List<FileChapter> {
        val udta = r.child(moov, "udta") ?: return emptyList()
        val chpl = r.child(udta, "chpl") ?: return emptyList()
        val b = r.bytes(chpl)
        val version = b.get().toInt()
        b.position(4)
        if (version != 0) b.int
        val count = b.get().toInt() and 0xff
        val out = mutableListOf<FileChapter>()
        repeat(count) {
            if (b.remaining() < 9) return@repeat
            val start = b.long // in 100-nanosecond units
            val len = b.get().toInt() and 0xff
            val text = ByteArray(minOf(len, b.remaining()))
            b.get(text)
            out += FileChapter(String(text, Charsets.UTF_8).trim(), start / 10_000)
        }
        return clean(out)
    }

    // ---------- QuickTime chapter track ----------

    private fun chapterTrack(r: Reader, moov: Box): List<FileChapter> {
        val traks = r.children(moov.start, moov.end).filter { it.type == "trak" }
        val chapterIds = mutableSetOf<Int>()
        val idOf = mutableMapOf<Box, Int>()
        for (t in traks) {
            r.child(t, "tkhd")?.let { tkhd ->
                val b = r.bytes(tkhd, 64)
                val version = b.get().toInt()
                b.position(if (version == 1) 4 + 16 else 4 + 8)
                idOf[t] = b.int
            }
            val chap = r.child(t, "tref")?.let { r.child(it, "chap") }
            if (chap != null) {
                val b = r.bytes(chap, 4096)
                while (b.remaining() >= 4) chapterIds += b.int
            }
        }
        val target = traks.firstOrNull { idOf[it] in chapterIds }
            ?: traks.firstOrNull { handler(r, it) == "text" }
            ?: return emptyList()
        return readTextTrack(r, target)
    }

    private fun handler(r: Reader, trak: Box): String? {
        val mdia = r.child(trak, "mdia") ?: return null
        val hdlr = r.child(mdia, "hdlr") ?: return null
        val b = r.bytes(hdlr, 32)
        if (b.remaining() < 12) return null
        b.position(8)
        val t = ByteArray(4)
        b.get(t)
        return String(t, Charsets.ISO_8859_1)
    }

    private fun readTextTrack(r: Reader, trak: Box): List<FileChapter> {
        val mdia = r.child(trak, "mdia") ?: return emptyList()
        val mdhd = r.child(mdia, "mdhd") ?: return emptyList()
        val hb = r.bytes(mdhd, 64)
        val version = hb.get().toInt()
        hb.position(if (version == 1) 4 + 16 else 4 + 8)
        val timescale = (hb.int.toLong() and 0xffffffffL).takeIf { it > 0 } ?: return emptyList()

        val stbl = r.child(mdia, "minf")?.let { r.child(it, "stbl") } ?: return emptyList()

        // Start time of each sample.
        val times = mutableListOf<Long>()
        r.child(stbl, "stts")?.let { box ->
            val b = r.bytes(box)
            b.position(4)
            val entries = b.int
            var t = 0L
            repeat(entries) {
                if (b.remaining() < 8) return@repeat
                val count = b.int
                val delta = b.int.toLong() and 0xffffffffL
                repeat(count.coerceAtMost(100_000)) {
                    times += t
                    t += delta
                }
            }
        } ?: return emptyList()

        // Size of each sample.
        val sizes = mutableListOf<Int>()
        r.child(stbl, "stsz")?.let { box ->
            val b = r.bytes(box)
            b.position(4)
            val fixed = b.int
            val count = b.int
            repeat(count.coerceAtMost(100_000)) {
                sizes += if (fixed != 0) fixed else if (b.remaining() >= 4) b.int else 0
            }
        } ?: return emptyList()

        // Where each chunk starts in the file.
        val chunkOffsets = mutableListOf<Long>()
        val stco = r.child(stbl, "stco")
        val co64 = r.child(stbl, "co64")
        when {
            stco != null -> {
                val b = r.bytes(stco)
                b.position(4)
                repeat(b.int) { if (b.remaining() >= 4) chunkOffsets += b.int.toLong() and 0xffffffffL }
            }
            co64 != null -> {
                val b = r.bytes(co64)
                b.position(4)
                repeat(b.int) { if (b.remaining() >= 8) chunkOffsets += b.long }
            }
            else -> return emptyList()
        }

        // How many samples are in each chunk.
        val stscEntries = mutableListOf<Pair<Int, Int>>() // first chunk (1-based), samples per chunk
        r.child(stbl, "stsc")?.let { box ->
            val b = r.bytes(box)
            b.position(4)
            repeat(b.int) {
                if (b.remaining() >= 12) {
                    val first = b.int
                    val per = b.int
                    b.int
                    stscEntries += first to per
                }
            }
        }
        if (stscEntries.isEmpty()) stscEntries += 1 to 1

        // Work out the file position of every sample.
        val sampleOffsets = mutableListOf<Long>()
        var sample = 0
        for (chunk in chunkOffsets.indices) {
            val chunkNumber = chunk + 1
            val per = stscEntries.lastOrNull { it.first <= chunkNumber }?.second ?: 1
            var offset = chunkOffsets[chunk]
            repeat(per) {
                if (sample < sizes.size) {
                    sampleOffsets += offset
                    offset += sizes[sample]
                    sample++
                }
            }
        }

        val out = mutableListOf<FileChapter>()
        val n = minOf(times.size, sizes.size, sampleOffsets.size)
        for (i in 0 until n) {
            val b = r.read(sampleOffsets[i], sizes[i].coerceIn(0, 4096))
            if (b.remaining() < 2) continue
            val len = b.short.toInt() and 0xffff
            val raw = ByteArray(minOf(len, b.remaining()))
            b.get(raw)
            val text = if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
                String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
            } else {
                String(raw, Charsets.UTF_8)
            }
            out += FileChapter(text.trim(), times[i] * 1000 / timescale)
        }
        return clean(out)
    }

    private fun clean(list: List<FileChapter>): List<FileChapter> {
        val sorted = list.sortedBy { it.startMs }.distinctBy { it.startMs }
        return sorted.mapIndexed { i, c -> if (c.title.isBlank()) c.copy(title = "Chapter ${i + 1}") else c }
            .takeIf { it.size > 1 } ?: emptyList()
    }
}
