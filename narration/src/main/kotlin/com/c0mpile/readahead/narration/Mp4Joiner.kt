package com.c0mpile.readahead.narration

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * [BookJoiner] that copies the AAC samples of the chapter files one after the other into one MP4 (no re-encoding),
 * then adds the chapter list and tags with [Mp4Tagger].
 */
class Mp4Joiner : BookJoiner {
    override fun join(
        chapters: List<File>,
        titles: List<String>,
        output: File,
        bookTitle: String?,
        author: String?,
    ) {
        require(chapters.size == titles.size) { "one title per chapter file" }
        val starts = copy(chapters, output)
        Mp4Tagger.tag(output, titles.zip(starts) { title, start -> ChapterMark(title, start) }, bookTitle, author)
    }

    /** Writes [inputs] into [output]; returns where each one starts, in milliseconds. */
    private fun copy(
        inputs: List<File>,
        output: File,
    ): List<Long> {
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val starts = ArrayList<Long>()
        try {
            var track = -1
            var offsetUs = 0L
            var rate = 0
            for (file in inputs) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    val format = audioTrack(extractor, file)
                    val fileRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (track < 0) {
                        track = muxer.addTrack(format)
                        muxer.start()
                        rate = fileRate
                    } else if (fileRate != rate) {
                        throw IOException("${file.name} has a different sample rate than the chapters before it")
                    }
                    starts += offsetUs / US_PER_MS
                    offsetUs += copySamples(extractor, muxer, track, offsetUs) + AAC_FRAME_SAMPLES * US_PER_SECOND / rate
                } finally {
                    extractor.release()
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
        return starts
    }

    private fun audioTrack(
        extractor: MediaExtractor,
        file: File,
    ): MediaFormat {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                extractor.selectTrack(i)
                return format
            }
        }
        throw IOException("${file.name} holds no audio")
    }

    /** Copies every sample of the selected track; returns the time of the last one. */
    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        offsetUs: Long,
    ): Long {
        val buffer = ByteBuffer.allocate(SAMPLE_BYTES)
        val info = MediaCodec.BufferInfo()
        var last = 0L
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return last
            info.set(0, size, offsetUs + extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            muxer.writeSampleData(track, buffer, info)
            last = extractor.sampleTime
            extractor.advance()
        }
    }

    private companion object {
        const val SAMPLE_BYTES = 256 * 1024
        const val US_PER_MS = 1_000L
        const val US_PER_SECOND = 1_000_000L
        const val AAC_FRAME_SAMPLES = 1024L
    }
}
