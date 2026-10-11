package com.c0mpile.readahead.narration

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Adds chapter markers and tags to a finished MP4 audio file by rewriting its `moov` box. Where `moov` sits depends
 * on the device: after the audio (nothing moves), or before it with a `free` box behind it (the new `moov` takes
 * space from the `free` box, nothing moves), or before it with no room (the audio is shifted and every chunk offset
 * in `moov` is corrected).
 */
internal object Mp4Tagger {
    fun tag(
        file: File,
        chapters: List<ChapterMark>,
        title: String?,
        author: String?,
    ) {
        val udta = Mp4Boxes.udta(chapters, title, author)
        val shifted =
            RandomAccessFile(file, "rw").use { f ->
                val boxes = topLevel(f)
                val index = boxes.indexOfFirst { it.type == "moov" }
                if (index < 0) throw IOException("No metadata box in the audio file")
                val moov = boxes[index]
                if (moov.size > MAX_MOOV) throw IOException("The audio file's metadata is too large")
                val old =
                    ByteArray(moov.size.toInt()).also {
                        f.seek(moov.start)
                        f.readFully(it)
                    }
                val updated = Mp4Boxes.withUdta(old, udta)
                val delta = updated.size - old.size
                val next = boxes.getOrNull(index + 1)
                when {
                    next == null -> replaceLast(f, moov, updated)
                    delta == 0 -> overwrite(f, moov.start, updated)
                    next.type == "free" && next.size - delta >= HEADER -> useFree(f, moov.start, updated, next, delta)
                    else -> return@use Shift(boxes, index, updated, delta)
                }
                null
            }
        if (shifted != null) rewrite(file, shifted)
    }

    private class Box(
        val type: String,
        val start: Long,
        val size: Long,
    ) {
        val end get() = start + size
    }

    private class Shift(
        val boxes: List<Box>,
        val moovIndex: Int,
        val moov: ByteArray,
        val delta: Int,
    )

    private fun topLevel(f: RandomAccessFile): List<Box> {
        val out = ArrayList<Box>()
        var pos = 0L
        val header = ByteArray(HEADER)
        while (pos + HEADER <= f.length()) {
            f.seek(pos)
            f.readFully(header)
            val size = sizeOf(f, header, pos)
            if (size < HEADER || pos + size > f.length()) throw IOException("Not a valid MP4 file")
            out += Box(String(header, TYPE_AT, TYPE_LENGTH, Charsets.ISO_8859_1), pos, size)
            pos += size
        }
        if (out.isEmpty()) throw IOException("Not a valid MP4 file")
        return out
    }

    private fun sizeOf(
        f: RandomAccessFile,
        header: ByteArray,
        pos: Long,
    ): Long =
        when (val size = Markup.readInt(header, 0).toLong() and UINT) {
            0L -> f.length() - pos // to the end of the file
            1L -> f.also { it.seek(pos + HEADER) }.readLong() // 64-bit size follows the type
            else -> size
        }

    private fun replaceLast(
        f: RandomAccessFile,
        moov: Box,
        updated: ByteArray,
    ) {
        f.setLength(moov.start)
        overwrite(f, moov.start, updated)
    }

    private fun overwrite(
        f: RandomAccessFile,
        at: Long,
        bytes: ByteArray,
    ) {
        f.seek(at)
        f.write(bytes)
    }

    /** The new `moov` is [delta] bytes longer; the `free` box behind it gets that much shorter, so the audio stays put. */
    private fun useFree(
        f: RandomAccessFile,
        at: Long,
        updated: ByteArray,
        free: Box,
        delta: Int,
    ) {
        overwrite(f, at, updated)
        overwrite(f, at + updated.size, Mp4Boxes.box("free", ByteArray(0)).also { Markup.writeInt(it, 0, (free.size - delta).toInt()) })
    }

    /** Writes the file again with the new `moov`: everything after the old one moves by [Shift.delta]. */
    private fun rewrite(
        file: File,
        shift: Shift,
    ) {
        val moov = shift.boxes[shift.moovIndex]
        Mp4Boxes.shiftChunkOffsets(shift.moov, shift.delta.toLong(), from = moov.end)
        val temp = File(file.path + ".tmp")
        try {
            RandomAccessFile(file, "r").use { src ->
                temp.outputStream().buffered().use { out ->
                    copy(src, 0, moov.start, out)
                    out.write(shift.moov)
                    copy(src, moov.end, src.length(), out)
                }
            }
            if (!temp.renameTo(file)) throw IOException("Could not replace the audio file")
        } finally {
            temp.delete()
        }
    }

    private fun copy(
        src: RandomAccessFile,
        from: Long,
        to: Long,
        out: java.io.OutputStream,
    ) {
        val buffer = ByteArray(COPY_BYTES)
        var pos = from
        src.seek(from)
        while (pos < to) {
            val n = src.read(buffer, 0, minOf(buffer.size.toLong(), to - pos).toInt())
            if (n < 0) throw IOException("The audio file ended early")
            out.write(buffer, 0, n)
            pos += n
        }
    }

    private const val HEADER = 8
    private const val TYPE_AT = 4
    private const val TYPE_LENGTH = 4
    private const val UINT = 0xFFFFFFFFL
    private const val MAX_MOOV = 64L shl 20
    private const val COPY_BYTES = 1 shl 20
}
