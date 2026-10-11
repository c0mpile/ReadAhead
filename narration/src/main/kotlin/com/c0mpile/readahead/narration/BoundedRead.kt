package com.c0mpile.readahead.narration

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads at most [max] bytes. Returns null when the stream is longer, so a zip bomb or an oversized entry is
 * skipped instead of exhausting memory (never trust an archive entry's declared size).
 */
internal fun InputStream.readBounded(max: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
    }
}

private const val BUFFER = 8192
