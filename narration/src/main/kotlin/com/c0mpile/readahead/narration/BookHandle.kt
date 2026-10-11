package com.c0mpile.readahead.narration

import android.os.ParcelFileDescriptor
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * Read access to one book file through an open descriptor: a file in app storage or a document the user picked.
 * Documents can only be read through the descriptor the provider hands out (reopening it by path is denied), so
 * every reader works on this instead of a path. All reads are positional (pread), so several readers can use one
 * handle at the same time.
 */
class BookHandle(
    private val pfd: ParcelFileDescriptor,
) : Closeable {
    // Does not own the descriptor: closing the handle closes it.
    private val channel = FileInputStream(pfd.fileDescriptor).channel

    val size: Long get() = channel.size()

    /** Reads into [dst] from [position]; -1 at the end. */
    fun read(
        position: Long,
        dst: ByteBuffer,
    ): Int = channel.read(dst, position)

    /** A stream from [start] with its own position. */
    fun inputStream(start: Long = 0): InputStream = Positional(this, start).let(java.nio.channels.Channels::newInputStream)

    /**
     * Random access zip reading (EPUB, CBZ); close it when done, the handle stays open. The channel constructor
     * (deprecated for the builder in 1.26) also exists in the older copy Robolectric's framework jar bundles,
     * which unit tests load first.
     */
    @Suppress("DEPRECATION")
    fun zip(): ZipFile = ZipFile(Positional(this, 0))

    /** A descriptor of its own for APIs that need one (PdfRenderer); the caller closes it. */
    fun descriptor(): ParcelFileDescriptor = pfd.dup()

    override fun close() = pfd.close()

    /** A read-only channel over the handle with its own position; closing it leaves the handle open. */
    private class Positional(
        private val handle: BookHandle,
        private var position: Long,
    ) : SeekableByteChannel {
        private var open = true

        override fun read(dst: ByteBuffer): Int {
            if (!open) throw ClosedChannelException()
            val n = handle.read(position, dst)
            if (n > 0) position += n
            return n
        }

        override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

        override fun position(): Long = position

        override fun position(newPosition: Long): SeekableByteChannel = apply { position = newPosition }

        override fun size(): Long = handle.size

        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

        override fun isOpen(): Boolean = open

        override fun close() {
            open = false
        }
    }

    companion object {
        fun open(file: File): BookHandle =
            try {
                BookHandle(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            } catch (e: SecurityException) {
                throw IOException("Cannot open ${file.name}", e)
            }
    }
}
