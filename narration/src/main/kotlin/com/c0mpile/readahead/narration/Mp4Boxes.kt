package com.c0mpile.readahead.narration

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** One chapter marker of an audiobook: its title and where it starts. */
data class ChapterMark(
    val title: String,
    val startMs: Long,
)

/**
 * The MP4 boxes that turn an M4A into an audiobook: a `chpl` chapter list (Nero style, read by Audiobookshelf, VLC,
 * ffmpeg and most audiobook players) and iTunes-style tags (title, artist, album, genre) in `udta`. Pure byte
 * building; [MediaMuxer] writes the audio and the `moov` box last, so appending these to it moves no audio offsets.
 */
internal object Mp4Boxes {
    private const val CHPL_VERSION = 1
    private const val HUNDRED_NS_PER_MS = 10_000L
    private const val MAX_TITLE_BYTES = 255
    private const val MAX_CHAPTERS = 255
    private const val UTF8_FLAG = 1
    private const val HEADER = 8

    fun udta(
        chapters: List<ChapterMark>,
        title: String?,
        author: String?,
    ): ByteArray {
        val children = ByteArrayOutputStream()
        meta(title, author).let(children::write)
        if (chapters.isNotEmpty()) children.write(chpl(chapters))
        return box("udta", children.toByteArray())
    }

    private fun chpl(chapters: List<ChapterMark>): ByteArray {
        val marks = chapters.take(MAX_CHAPTERS)
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeByte(CHPL_VERSION)
            write(byteArrayOf(0, 0, 0)) // flags
            writeInt(0) // reserved
            writeByte(marks.size)
            for (mark in marks) {
                writeLong(mark.startMs * HUNDRED_NS_PER_MS)
                val text = clip(mark.title.toByteArray(Charsets.UTF_8))
                writeByte(text.size)
                write(text)
            }
        }
        return box("chpl", body.toByteArray())
    }

    /** At most 255 bytes, not cutting a multi-byte character in half. */
    private fun clip(bytes: ByteArray): ByteArray {
        if (bytes.size <= MAX_TITLE_BYTES) return bytes
        var end = MAX_TITLE_BYTES
        while (end > 0 && (bytes[end].toInt() and CONTINUATION_MASK) == CONTINUATION) end--
        return bytes.copyOf(end)
    }

    private fun meta(
        title: String?,
        author: String?,
    ): ByteArray {
        val items = ByteArrayOutputStream()
        title?.let { text("©nam", it) }?.let(items::write)
        author?.let { text("©ART", it) }?.let(items::write)
        title?.let { text("©alb", it) }?.let(items::write)
        text("©gen", "Audiobook").let(items::write)
        val body = ByteArrayOutputStream()
        DataOutputStream(body).writeInt(0) // version and flags
        body.write(handler())
        body.write(box("ilst", items.toByteArray()))
        return box("meta", body.toByteArray())
    }

    private fun handler(): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeInt(0) // version and flags
            writeInt(0) // predefined
            write("mdir".toByteArray(Charsets.ISO_8859_1))
            write("appl".toByteArray(Charsets.ISO_8859_1))
            writeInt(0)
            writeInt(0)
            writeByte(0) // empty name
        }
        return box("hdlr", body.toByteArray())
    }

    private fun text(
        type: String,
        value: String,
    ): ByteArray {
        val data = ByteArrayOutputStream()
        DataOutputStream(data).apply {
            writeInt(UTF8_FLAG) // version 0, flags 1: UTF-8 text
            writeInt(0) // locale
            write(value.toByteArray(Charsets.UTF_8))
        }
        return box(type, box("data", data.toByteArray()))
    }

    /** [moov] with [udta] as its last child, replacing a `udta` it already had. */
    fun withUdta(
        moov: ByteArray,
        udta: ByteArray,
    ): ByteArray {
        val kept = ByteArrayOutputStream()
        var pos = HEADER
        while (pos + HEADER <= moov.size) {
            val size = readInt(moov, pos)
            val type = String(moov, pos + 4, 4, Charsets.ISO_8859_1)
            require(size >= HEADER && pos + size <= moov.size) { "Malformed moov box" }
            if (type != "udta") kept.write(moov, pos, size)
            pos += size
        }
        kept.write(udta)
        return box("moov", kept.toByteArray())
    }

    /**
     * Adds [delta] to every chunk offset (`stco` and `co64` entries) of [moov] that points at or after [from]: the
     * audio those offsets address has moved. Walks only the boxes that can hold them; works in place.
     */
    fun shiftChunkOffsets(
        moov: ByteArray,
        delta: Long,
        from: Long,
    ) {
        val buffer = java.nio.ByteBuffer.wrap(moov)

        fun walk(
            start: Int,
            end: Int,
        ) {
            var pos = start
            while (pos + HEADER <= end) {
                val size = buffer.getInt(pos)
                require(size >= HEADER && pos + size <= end) { "Malformed moov box" }
                val type = String(moov, pos + 4, 4, Charsets.ISO_8859_1)
                when (type) {
                    "trak", "mdia", "minf", "stbl" -> walk(pos + HEADER, pos + size)
                    "stco" -> shiftEntries(buffer, pos, size, entryBytes = NARROW, delta, from)
                    "co64" -> shiftEntries(buffer, pos, size, entryBytes = WIDE, delta, from)
                }
                pos += size
            }
        }
        walk(HEADER, moov.size)
    }

    private fun shiftEntries(
        buffer: java.nio.ByteBuffer,
        box: Int,
        size: Int,
        entryBytes: Int,
        delta: Long,
        from: Long,
    ) {
        val count = buffer.getInt(box + HEADER + 4)
        require(count >= 0 && TABLE_START + count.toLong() * entryBytes <= size) { "Malformed chunk offset box" }
        var at = box + TABLE_START
        repeat(count) {
            if (entryBytes == NARROW) {
                val offset = buffer.getInt(at).toLong() and UINT
                if (offset >= from) buffer.putInt(at, (offset + delta).toInt())
            } else {
                val offset = buffer.getLong(at)
                if (offset >= from) buffer.putLong(at, offset + delta)
            }
            at += entryBytes
        }
    }

    fun box(
        type: String,
        body: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(HEADER + body.size)
        DataOutputStream(out).apply {
            writeInt(HEADER + body.size)
            write(type.toByteArray(Charsets.ISO_8859_1))
            write(body)
        }
        return out.toByteArray()
    }

    fun readInt(
        bytes: ByteArray,
        at: Int,
    ): Int = Markup.readInt(bytes, at)

    private const val TABLE_START = 16 // box header, version and flags, entry count
    private const val NARROW = 4
    private const val WIDE = 8
    private const val UINT = 0xFFFFFFFFL
    private const val CONTINUATION_MASK = 0xC0
    private const val CONTINUATION = 0x80
}
