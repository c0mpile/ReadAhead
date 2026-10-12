package com.c0mpile.readahead.narration

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class EpubReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun epub(vararg entries: Pair<String, String>): BookHandle =
        epubBytes(
            *entries
                .map {
                    it.first to it.second.toByteArray()
                }.toTypedArray(),
        )

    private fun epubBytes(vararg entries: Pair<String, ByteArray>): BookHandle {
        val file = File(tmp.root, "book.epub")
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, bytes) in entries) {
                out.putNextEntry(ZipEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return BookHandle.open(file)
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 8, 7)
    private val png = "\u0089PNG\r\n\u001A\n".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 2, 3)

    private fun page(vararg paragraphs: String) = "<html><body>" + paragraphs.joinToString("") { "<p>$it</p>" } + "</body></html>"

    private val story = "The clocks were striking thirteen and the wind was cold. ".repeat(20).trim()
    private val container =
        "META-INF/container.xml" to
            "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"

    private fun opf(
        spine: List<String>,
        extra: String = "",
        guide: String = "",
    ) = "OEBPS/content.opf" to
        (
            "<package version=\"3.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Test Book</dc:title>" +
                "<dc:creator>Jane Doe</dc:creator></metadata><manifest>" +
                spine.joinToString("") { "<item id=\"$it\" href=\"$it.xhtml\" media-type=\"application/xhtml+xml\"/>" } +
                "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>$extra</manifest><spine>" +
                spine.joinToString("") { "<itemref idref=\"$it\"/>" } + "</spine>$guide</package>"
        )

    @Test
    fun epub3WithNavLandmarksAndAnchors() {
        val nav =
            "<html><body><nav epub:type=\"toc\"><ol>" +
                "<li><a href=\"cover.xhtml\">Cover</a></li><li><a href=\"copy.xhtml\">Copyright</a></li>" +
                "<li><a href=\"ch.xhtml#c1\">Chapter 1</a></li><li><a href=\"ch.xhtml#c2\">Chapter 2</a></li>" +
                "<li><a href=\"ack.xhtml\">Acknowledgements</a></li></ol></nav>" +
                "<nav epub:type=\"landmarks\"><ol><li><a epub:type=\"bodymatter\" href=\"ch.xhtml#c1\">Start</a></li></ol></nav></body></html>"
        val book =
            epub(
                container,
                opf(listOf("cover", "copy", "ch", "ack")),
                "OEBPS/nav.xhtml" to nav,
                "OEBPS/cover.xhtml" to page("Cover"),
                "OEBPS/copy.xhtml" to page("Copyright 2020 Somebody. All rights reserved. ".repeat(20)),
                "OEBPS/ch.xhtml" to
                    "<html><body><h2 id=\"c1\">Chapter 1</h2><p>First $story</p><h2 id=\"c2\">Chapter 2</h2><p>Second $story</p></body></html>",
                "OEBPS/ack.xhtml" to page("Thanks to everyone. ".repeat(50)),
            ).use(EpubReader::read)
        assertEquals("Test Book", book.title)
        assertEquals("Jane Doe", book.author)
        assertEquals(listOf("Cover", "Copyright", "Chapter 1", "Chapter 2", "Acknowledgements"), book.chapters.map { it.title })
        assertEquals(
            listOf(Matter.FRONT, Matter.FRONT, Matter.STORY, Matter.STORY, Matter.BACK),
            book.chapters.map { it.matter },
        )
        val one = book.chapters[2].text
        assertTrue(one.startsWith("Chapter 1.\n\nFirst The clocks"))
        assertFalse("chapter 1 must stop where chapter 2 starts", "Second" in one || "Chapter 2" in one)
        assertTrue(book.chapters[3].text.startsWith("Chapter 2.\n\nSecond"))
        assertFalse("Thanks" in book.chapters[3].text)
    }

    @Test
    fun epub2NcxWithAPartTitleMergedIntoItsFirstChapter() {
        val ncx =
            "<ncx><navMap>" +
                "<navPoint id=\"a\"><navLabel><text>Part One</text></navLabel><content src=\"part1.xhtml\"/>" +
                "<navPoint id=\"b\"><navLabel><text>Chapter 1</text></navLabel><content src=\"c1.xhtml\"/></navPoint>" +
                "<navPoint id=\"c\"><navLabel><text>Chapter 2</text></navLabel><content src=\"c2.xhtml\"/></navPoint></navPoint>" +
                "</navMap></ncx>"
        val opf =
            "OEBPS/content.opf" to
                (
                    "<package version=\"2.0\"><metadata xmlns:dc=\"x\"><dc:title>Old</dc:title></metadata><manifest>" +
                        "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>" +
                        listOf(
                            "part1",
                            "c1",
                            "c2",
                        ).joinToString("") { "<item id=\"$it\" href=\"$it.xhtml\" media-type=\"application/xhtml+xml\"/>" } +
                        "</manifest><spine toc=\"ncx\">" + listOf("part1", "c1", "c2").joinToString("") { "<itemref idref=\"$it\"/>" } +
                        "</spine></package>"
                )
        val book =
            epub(
                container,
                opf,
                "OEBPS/toc.ncx" to ncx,
                "OEBPS/part1.xhtml" to "<html><body><h1>PART ONE</h1></body></html>",
                "OEBPS/c1.xhtml" to page("One $story"),
                "OEBPS/c2.xhtml" to page("Two $story"),
            ).use(EpubReader::read)
        assertEquals(listOf("Chapter 1", "Chapter 2"), book.chapters.map { it.title })
        assertTrue(book.chapters[0].text.startsWith("PART ONE.\n\nOne The clocks"))
        assertEquals(listOf(Matter.STORY, Matter.STORY), book.chapters.map { it.matter })
    }

    @Test
    fun withoutATableOfContentsEachLongSectionIsAChapter() {
        val book =
            epub(
                container,
                opf(listOf("a", "b")),
                "OEBPS/a.xhtml" to page("Alpha $story"),
                "OEBPS/b.xhtml" to page("Beta $story"),
            ).use(EpubReader::read)
        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters[1].text.startsWith("Beta"))
    }

    private fun coverBook(
        extra: String,
        image: ByteArray,
        path: String = "OEBPS/images/cover.jpg",
        meta: String = "",
    ) = epubBytes(
        container.first to container.second.toByteArray(),
        "OEBPS/content.opf" to
            (
                "<package version=\"3.0\"><metadata xmlns:dc=\"x\"><dc:title>T</dc:title>$meta</metadata><manifest>" +
                    "<item id=\"a\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/>$extra</manifest>" +
                    "<spine><itemref idref=\"a\"/></spine></package>"
            ).toByteArray(),
        "OEBPS/a.xhtml" to page("Alpha $story").toByteArray(),
        path to image,
    ).use(EpubReader::read)

    @Test
    fun theCoverImageOfAnEpub3IsRead() {
        val book = coverBook("<item id=\"c\" href=\"images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>", jpeg)
        assertEquals(CoverFormat.JPEG, book.cover?.format)
        assertArrayEquals(jpeg, book.cover?.bytes)
    }

    @Test
    fun theCoverOfAnEpub2IsFoundThroughItsMetaElement() {
        val book =
            coverBook(
                "<item id=\"pic\" href=\"images/cover.png\" media-type=\"image/png\"/>",
                png,
                path = "OEBPS/images/cover.png",
                meta = "<meta name=\"cover\" content=\"pic\"/>",
            )
        assertEquals(CoverFormat.PNG, book.cover?.format)
    }

    @Test
    fun aCoverThatIsNotAJpegOrPngIsIgnored() {
        val book =
            coverBook(
                "<item id=\"c\" href=\"images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>",
                "<html>not an image</html>".toByteArray(),
            )
        assertNull(book.cover)
        assertEquals(1, book.chapters.size)
    }

    @Test
    fun aBookWithoutACoverHasNone() {
        assertNull(epub(container, opf(listOf("a")), "OEBPS/a.xhtml" to page("Alpha $story")).use(EpubReader::read).cover)
    }

    @Test
    fun notAnEpubIsRefused() {
        val handle = epub("hello.txt" to "hi")
        try {
            EpubReader.read(handle)
            throw AssertionError("expected EpubException")
        } catch (_: EpubException) {
            // expected
        } finally {
            handle.close()
        }
    }
}
