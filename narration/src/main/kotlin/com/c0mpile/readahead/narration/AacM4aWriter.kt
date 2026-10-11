package com.c0mpile.readahead.narration

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** [AudioEncoder] over the device's AAC encoder and [MediaMuxer]: AAC-LC, 64 kbit/s, in an MP4 (`.m4a`) file. */
class AacEncoder(
    private val bitrate: Int = DEFAULT_BITRATE,
) : AudioEncoder {
    override fun open(
        file: File,
        sampleRate: Int,
        channels: Int,
    ): ChapterAudio = AacM4aWriter(file, sampleRate, channels, bitrate)

    private companion object {
        const val DEFAULT_BITRATE = 64_000
    }
}

internal class AacM4aWriter(
    file: File,
    override val sampleRate: Int,
    override val channels: Int,
    bitrate: Int,
) : ChapterAudio {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private val frameSize = channels * BYTES_PER_SAMPLE
    private var track = -1
    private var frames = 0L
    private var released = false

    init {
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BYTES)
            }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun writeWav(file: File) {
        val wav = readWav(file) ?: throw IOException("The speech engine wrote no usable audio")
        if (wav.sampleRate != sampleRate || wav.channels != channels) {
            throw IOException("The speech engine changed its audio format in the middle of a chapter")
        }
        RandomAccessFile(file, "r").use { f ->
            f.seek(wav.dataOffset)
            val buffer = ByteArray(INPUT_BYTES - INPUT_BYTES % frameSize)
            var left = wav.dataLength
            while (left > 0) {
                val n = minOf(left, buffer.size.toLong()).toInt()
                f.readFully(buffer, 0, n)
                feed(buffer, n)
                left -= n
            }
        }
    }

    override fun writeSilence(ms: Int) {
        val buffer = ByteArray(INPUT_BYTES - INPUT_BYTES % frameSize)
        var left = sampleRate.toLong() * ms / MS_PER_SECOND * frameSize
        while (left > 0) {
            val n = minOf(left, buffer.size.toLong()).toInt() / frameSize * frameSize
            if (n == 0) break
            feed(buffer, n)
            left -= n
        }
    }

    /** Hands [length] bytes of PCM to the encoder, writing out what it has finished in between. */
    private fun feed(
        pcm: ByteArray,
        length: Int,
    ) {
        var at = 0
        while (at < length) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val input = codec.getInputBuffer(index) ?: throw IOException("The audio encoder gave no input buffer")
            input.clear()
            val n = minOf(length - at, input.capacity()) / frameSize * frameSize
            input.put(pcm, at, n)
            codec.queueInputBuffer(index, 0, n, frames * US_PER_SECOND / sampleRate, 0)
            frames += n / frameSize
            at += n
            drain(endOfStream = false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    writeOut(index)
                    if (done) return
                }
            }
        }
    }

    private fun writeOut(index: Int) {
        val data = codec.getOutputBuffer(index)
        val sample = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
        if (data != null && sample && track >= 0) {
            data.position(info.offset)
            data.limit(info.offset + info.size)
            muxer.writeSampleData(track, data, info)
        }
        codec.releaseOutputBuffer(index, false)
    }

    override fun finish(): Long {
        var index = codec.dequeueInputBuffer(TIMEOUT_US)
        while (index < 0) {
            drain(endOfStream = false)
            index = codec.dequeueInputBuffer(TIMEOUT_US)
        }
        codec.queueInputBuffer(index, 0, 0, frames * US_PER_SECOND / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(endOfStream = true)
        release(stopMuxer = track >= 0)
        return frames * MS_PER_SECOND / sampleRate
    }

    override fun close() = release(stopMuxer = false)

    private fun release(stopMuxer: Boolean) {
        if (released) return
        released = true
        runCatching { codec.stop() }
        codec.release()
        if (stopMuxer) runCatching { muxer.stop() }
        muxer.release()
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val INPUT_BYTES = 16_384
        const val TIMEOUT_US = 10_000L
        const val US_PER_SECOND = 1_000_000L
        const val MS_PER_SECOND = 1_000L
    }
}
