package com.c0mpile.readahead.narration

import java.io.Closeable
import java.io.File

/** Turns one chunk of text into a WAV file by the speech engine; false when the engine failed or produced nothing. */
interface ChunkRenderer {
    suspend fun render(
        text: String,
        file: File,
    ): Boolean

    /** Starts the engine over; after a failure it can stay broken until it is rebuilt. */
    fun reset()
}

/** Writes the audio of one chapter, PCM in, compressed out. */
interface ChapterAudio : Closeable {
    val sampleRate: Int
    val channels: Int

    /** Appends the PCM of the 16-bit WAV [file] the engine wrote. */
    fun writeWav(file: File)

    fun writeSilence(ms: Int)

    /** Ends the file; returns its length in milliseconds. */
    fun finish(): Long
}

interface AudioEncoder {
    /** A writer for [file]; its format is that of the first WAV, so [sampleRate] and [channels] come from it. */
    fun open(
        file: File,
        sampleRate: Int,
        channels: Int,
    ): ChapterAudio
}

/** Joins finished chapter files into one audiobook with chapter markers. */
interface BookJoiner {
    fun join(
        chapters: List<File>,
        titles: List<String>,
        output: File,
        bookTitle: String?,
        author: String?,
        cover: BookCover?,
    )
}

/** The WAV a chunk was rendered to, as [ChapterAudio] needs it. */
internal fun wavFormat(file: File): Wav? = readWav(file)
