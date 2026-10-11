package com.c0mpile.readahead.narration

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The PCM of a WAV file the engine wrote: where the samples start and how many bytes there are. */
internal class Wav(
    val sampleRate: Int,
    val channels: Int,
    val dataOffset: Long,
    val dataLength: Long,
) {
    val frameSize get() = channels * BYTES_PER_SAMPLE
    val durationMs get() = dataLength * MS_PER_SECOND / (sampleRate.toLong() * frameSize)
}

private const val BYTES_PER_SAMPLE = 2
private const val MS_PER_SECOND = 1000L
private const val PCM = 1
private const val EXTENSIBLE = 0xFFFE
private const val BITS = 16
private const val CHUNK_HEADER = 8
private const val FILE_HEADER = 12
private const val FMT_SIZE = 16
private const val TAG_LENGTH = 4
private const val RATE_AT = 4
private const val CHANNELS_AT = 2
private const val BITS_AT = 14
private const val USHORT = 0xFFFF
private const val UINT = 0xFFFFFFFFL

private class Format(
    val rate: Int,
    val channels: Int,
)

/** Reads the header of a 16-bit PCM WAV file; null for anything else (or no audio in it). */
internal fun readWav(file: File): Wav? = runCatching { RandomAccessFile(file, "r").use(::parseWav) }.getOrNull()

private fun parseWav(f: RandomAccessFile): Wav? {
    val head = ByteArray(FILE_HEADER).also { f.readFully(it) }
    if (String(head, 0, TAG_LENGTH) != "RIFF" || String(head, TAG_LENGTH * 2, TAG_LENGTH) != "WAVE") return null
    var format: Format? = null
    var pos = FILE_HEADER.toLong()
    while (pos + CHUNK_HEADER <= f.length()) {
        f.seek(pos)
        val h = ByteBuffer.wrap(ByteArray(CHUNK_HEADER).also { f.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
        val id = String(h.array(), 0, TAG_LENGTH)
        val size = h.getInt(TAG_LENGTH).toLong() and UINT
        when (id) {
            "fmt " -> format = readFormat(f)
            "data" -> return format?.let { dataOf(it, pos + CHUNK_HEADER, size, f.length()) }
        }
        pos += CHUNK_HEADER + size + (size and 1)
    }
    return null
}

private fun readFormat(f: RandomAccessFile): Format? {
    val b = ByteBuffer.wrap(ByteArray(FMT_SIZE).also { f.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
    val tag = b.getShort(0).toInt() and USHORT
    val usable = (tag == PCM || tag == EXTENSIBLE) && b.getShort(BITS_AT).toInt() == BITS
    return if (usable) Format(b.getInt(RATE_AT), b.getShort(CHANNELS_AT).toInt()) else null
}

private fun dataOf(
    format: Format,
    offset: Long,
    declared: Long,
    fileLength: Long,
): Wav? {
    val rest = fileLength - offset
    // A streaming engine leaves the size 0 or -1: the data runs to the end of the file.
    val length = if (declared == 0L || declared > rest) rest else declared
    val frame = format.channels * BYTES_PER_SAMPLE
    val whole = length - length % frame
    val ok = format.channels in 1..2 && format.rate > 0 && whole > 0
    return if (ok) Wav(format.rate, format.channels, offset, whole) else null
}
