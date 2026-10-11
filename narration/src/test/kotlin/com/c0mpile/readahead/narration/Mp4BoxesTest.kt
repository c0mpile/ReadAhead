package com.c0mpile.readahead.narration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

class Mp4BoxesTest {
    @get:Rule val tmp = TemporaryFolder()

    private data class Box(
        val type: String,
        val start: Int,
        val size: Int,
    )

    private fun children(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        while (pos + 8 <= to) {
            val size = ByteBuffer.wrap(bytes).getInt(pos)
            out += Box(String(bytes, pos + 4, 4, Charsets.ISO_8859_1), pos, size)
            pos += size
        }
        assertEquals("boxes tile their parent exactly", to, pos)
        return out
    }

    /** Reads a chpl box the way ffmpeg's mov demuxer does (version 1: flags, 4 reserved bytes, a count byte, then 100 ns times). */
    private fun readChpl(
        bytes: ByteArray,
        box: Box,
    ): List<Pair<String, Long>> {
        val b = ByteBuffer.wrap(bytes)
        var p = box.start + 8
        assertEquals(1, b.get(p).toInt())
        p += 4 + 4
        val count = b.get(p++).toInt() and 0xFF
        val out = ArrayList<Pair<String, Long>>()
        repeat(count) {
            val start = b.getLong(p) / 10_000
            p += 8
            val len = b.get(p++).toInt() and 0xFF
            out += String(bytes, p, len, Charsets.UTF_8) to start
            p += len
        }
        assertEquals(box.start + box.size, p)
        return out
    }

    private fun moov(vararg inner: ByteArray) = Mp4Boxes.box("moov", inner.fold(ByteArray(0)) { a, b -> a + b })

    @Test
    fun udtaHoldsChaptersAndTags() {
        val marks = listOf(ChapterMark("Opening", 0), ChapterMark("Chapter 1", 4_500), ChapterMark("Chapter 2 – é", 3_600_000))
        val udta = Mp4Boxes.udta(marks, "Nineteen Eighty-Four", "George Orwell")
        val top = children(udta)
        assertEquals(listOf("udta"), top.map { it.type })
        val inner = children(udta, 8, udta.size)
        assertEquals(listOf("meta", "chpl"), inner.map { it.type })
        assertEquals(marks.map { it.title to it.startMs }, readChpl(udta, inner[1]))
        val meta = inner[0]
        val tags = children(udta, meta.start + 12, meta.start + meta.size)
        assertEquals(listOf("hdlr", "ilst"), tags.map { it.type })
        val items = children(udta, tags[1].start + 8, tags[1].start + tags[1].size).map { it.type }
        assertEquals(listOf("©nam", "©ART", "©alb", "©gen"), items)
        assertTrue(String(udta, Charsets.ISO_8859_1).contains("George Orwell"))
    }

    @Test
    fun aLongTitleIsCutOnACharacterBoundary() {
        val title = "é".repeat(200) // 400 bytes
        val udta = Mp4Boxes.udta(listOf(ChapterMark(title, 0)), null, null)
        val chpl = children(udta, 8, udta.size).single { it.type == "chpl" }
        val (read, _) = readChpl(udta, chpl).single()
        assertTrue(read.isNotEmpty() && read.all { it == 'é' })
        assertTrue(read.toByteArray().size <= 255)
    }

    @Test
    fun withUdtaAppendsAndReplaces() {
        val trak = Mp4Boxes.box("trak", byteArrayOf(1, 2, 3))
        val first = Mp4Boxes.withUdta(moov(trak), Mp4Boxes.udta(emptyList(), "Alpha", null))
        assertEquals(listOf("trak", "udta"), children(first, 8, first.size).map { it.type })
        assertEquals(first.size, ByteBuffer.wrap(first).getInt(0))
        val second = Mp4Boxes.withUdta(first, Mp4Boxes.udta(emptyList(), "Bravo", null))
        assertEquals(listOf("trak", "udta"), children(second, 8, second.size).map { it.type })
        val text = String(second, Charsets.ISO_8859_1)
        assertTrue("Bravo" in text && "Alpha" !in text)
    }

    private fun mp4(audio: ByteArray): File {
        val ftyp = Mp4Boxes.box("ftyp", "M4B \u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
        val file = File(tmp.root, "x.m4b")
        file.writeBytes(ftyp + Mp4Boxes.box("mdat", audio) + moov(Mp4Boxes.box("trak", byteArrayOf(9))))
        return file
    }

    @Test
    fun taggingAFileLeavesTheAudioUntouched() {
        val audio = ByteArray(5_000) { (it % 251).toByte() }
        val file = mp4(audio)
        val before = file.readBytes()
        Mp4Tagger.tag(file, listOf(ChapterMark("One", 0), ChapterMark("Two", 1_000)), "T", "A")
        val after = file.readBytes()
        val head = before.size - moov(Mp4Boxes.box("trak", byteArrayOf(9))).size
        assertArrayEquals(before.copyOf(head), after.copyOf(head))
        val top = children(after)
        assertEquals(listOf("ftyp", "mdat", "moov"), top.map { it.type })
        val inside = children(after, top[2].start + 8, top[2].start + top[2].size)
        assertEquals(listOf("trak", "udta"), inside.map { it.type })
    }

    private val marker = byteArrayOf(0x41, 0x55, 0x44, 0x49, 0x4F) // "AUDIO"

    private fun int(n: Long) = ByteBuffer.allocate(4).putInt(n.toInt()).array()

    private fun long(n: Long) = ByteBuffer.allocate(8).putLong(n).array()

    /** ftyp, moov (one track whose only chunk starts at the marker), an optional free box, then mdat holding the marker. */
    private fun moovFirst(
        freeBytes: Int?,
        wide: Boolean = false,
    ): File {
        val ftyp = Mp4Boxes.box("ftyp", ByteArray(8))

        fun moovWith(offset: Long): ByteArray {
            val table =
                if (wide) {
                    Mp4Boxes.box("co64", int(0) + int(1) + long(offset))
                } else {
                    Mp4Boxes.box("stco", int(0) + int(1) + int(offset))
                }
            return moov(Mp4Boxes.box("trak", Mp4Boxes.box("mdia", Mp4Boxes.box("minf", Mp4Boxes.box("stbl", table)))))
        }
        val free = freeBytes?.let { Mp4Boxes.box("free", ByteArray(it - 8)) } ?: ByteArray(0)
        val offset = (ftyp.size + moovWith(0).size + free.size + 8).toLong()
        val file = File(tmp.root, "front.m4b")
        file.writeBytes(ftyp + moovWith(offset) + free + Mp4Boxes.box("mdat", marker))
        return file
    }

    /** Where the first chunk starts, as the file's own tables say, and whether the marker really is there. */
    private fun chunkPointsAtAudio(file: File): Boolean {
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        val wide = text.indexOf("co64")
        val at = if (wide >= 0) wide + 4 + 8 else text.indexOf("stco") + 4 + 8
        val offset = if (wide >= 0) ByteBuffer.wrap(bytes).getLong(at).toInt() else ByteBuffer.wrap(bytes).getInt(at)
        return bytes.copyOfRange(offset, offset + marker.size).contentEquals(marker)
    }

    private val marks = listOf(ChapterMark("One", 0), ChapterMark("Two", 1_000))

    @Test
    fun metadataBeforeTheAudioUsesTheFreeBoxAndMovesNothing() {
        val file = moovFirst(freeBytes = 4_000)
        val length = file.length()
        Mp4Tagger.tag(file, marks, "T", "A")
        assertEquals(length, file.length())
        assertTrue(chunkPointsAtAudio(file))
        val top = children(file.readBytes())
        assertEquals(listOf("ftyp", "moov", "free", "mdat"), top.map { it.type })
        val bytes = file.readBytes()
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("chpl"))
    }

    @Test
    fun metadataBeforeTheAudioWithoutRoomShiftsTheAudioAndFixesTheOffsets() {
        val file = moovFirst(freeBytes = null)
        val length = file.length()
        Mp4Tagger.tag(file, marks, "T", "A")
        assertTrue(file.length() > length)
        assertTrue(chunkPointsAtAudio(file))
        assertEquals(listOf("ftyp", "moov", "mdat"), children(file.readBytes()).map { it.type })
    }

    @Test
    fun aSmallFreeBoxIsNotEnoughSoTheAudioShifts() {
        val file = moovFirst(freeBytes = 16)
        Mp4Tagger.tag(file, marks, "T", "A")
        assertTrue(chunkPointsAtAudio(file))
        assertTrue(String(file.readBytes(), Charsets.ISO_8859_1).contains("chpl"))
    }

    @Test
    fun sixtyFourBitChunkOffsetsAreFixedToo() {
        val file = moovFirst(freeBytes = null, wide = true)
        Mp4Tagger.tag(file, marks, "T", "A")
        assertTrue(chunkPointsAtAudio(file))
    }

    @Test
    fun taggingTwiceKeepsTheAudioReachable() {
        val file = moovFirst(freeBytes = 4_000)
        Mp4Tagger.tag(file, marks, "T", "A")
        Mp4Tagger.tag(file, marks + ChapterMark("Three", 2_000), "T2", "A")
        assertTrue(chunkPointsAtAudio(file))
    }

    @Test(expected = IOException::class)
    fun garbageIsRefused() {
        val file = File(tmp.root, "junk.m4b").apply { writeBytes(ByteArray(64) { 7 }) }
        Mp4Tagger.tag(file, emptyList(), "T", null)
    }
}
