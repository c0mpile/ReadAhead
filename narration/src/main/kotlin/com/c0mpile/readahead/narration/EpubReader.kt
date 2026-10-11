package com.c0mpile.readahead.narration

import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException

/** The book is not an EPUB this reader can follow. */
class EpubException(
    message: String,
) : IOException(message)

/**
 * Reads the structure of an EPUB without a WebView, for exports that run in the background: the package (title,
 * author, spine, landmarks), the table of contents (EPUB 3 `nav` or EPUB 2 NCX) and the text of each chapter, cut at
 * the table of contents' own anchors. Entries too short to stand alone ("Part One") are read with the chapter after them.
 */
object EpubReader {
    fun read(book: BookHandle): EpubBook = book.zip().use(::read)

    private fun read(zip: ZipFile): EpubBook {
        val pack = EpubPackage.read(zip)
        if (pack.spine.size > MAX_SECTIONS) throw EpubException("Too many sections to export")
        var total = 0L
        val sections =
            pack.spine.map { path ->
                EpubPackage.entryText(zip, path, MAX_SECTION_BYTES).orEmpty().also {
                    total += it.length
                    if (total > MAX_TOTAL_CHARS) throw EpubException("The book is too large to export")
                }
            }
        val anchors = tocAnchors(zip, pack).ifEmpty { fallbackAnchors(pack, sections) }
        val pieces = cut(anchors, pack, sections)
        val entries = pieces.map { TocEntry(it.title, it.section, it.text.length) }
        val bodyStart = pack.bodyHref?.let { pack.spine.indexOf(it).takeIf { i -> i >= 0 } }
        val kinds = ChapterMatter.classify(entries, bodyStart)
        return EpubBook(pack.title, pack.author, merge(pieces, kinds))
    }

    private class Piece(
        val title: String,
        val section: Int,
        val text: String,
    )

    /** Tiny story entries (a part title, an epigraph page) join the chapter after them, which keeps the later title. */
    private fun merge(
        pieces: List<Piece>,
        kinds: List<Matter>,
    ): List<ExportChapter> {
        val out = ArrayList<ExportChapter>()
        var carry = ""
        for (i in pieces.indices) {
            val piece = pieces[i]
            val last = i == pieces.lastIndex || kinds[i + 1] != Matter.STORY
            if (kinds[i] == Matter.STORY && piece.text.length < MIN_STANDALONE && !last) {
                carry += piece.text + "\n\n"
            } else {
                out += ExportChapter(piece.title, carry + piece.text, kinds[i])
                carry = ""
            }
        }
        return out
    }

    private class Anchor(
        val title: String,
        val section: Int,
        val fragment: String?,
    )

    private fun cut(
        anchors: List<Anchor>,
        pack: EpubPackage.Package,
        sections: List<String>,
    ): List<Piece> {
        val ids = HashMap<Int, Map<String, Int>>()
        val starts =
            anchors
                .map {
                    it to
                        offsetOf(
                            if (it.fragment ==
                                null
                            ) {
                                emptyMap()
                            } else {
                                ids.getOrPut(it.section) { idsOf(sections[it.section]) }
                            },
                            it.fragment,
                        )
                }.sortedWith(compareBy({ it.first.section }, { it.second }))
        return starts.mapIndexed { i, (anchor, from) ->
            val next = starts.getOrNull(i + 1)
            val html = StringBuilder()
            var section = anchor.section
            var offset = from
            while (section < pack.spine.size) {
                val html0 = sections[section]
                if (next != null && next.first.section == section) {
                    html.append(html0, minOf(offset, next.second), next.second)
                    break
                }
                html.append(html0, minOf(offset, html0.length), html0.length)
                section++
                offset = 0
            }
            Piece(anchor.title, anchor.section, HtmlToText.convert(html.toString()))
        }
    }

    /** Where the element with [fragment] starts in a section: its opening `<`; 0 without a fragment or when it is not found. */
    private fun offsetOf(
        ids: Map<String, Int>,
        fragment: String?,
    ): Int = if (fragment == null) 0 else ids[fragment] ?: 0

    /** The start of every element with an `id`, from one scan of the section (the first element wins). */
    private fun idsOf(html: String): Map<String, Int> {
        val ids = HashMap<String, Int>()
        for (tag in Markup.tags(html)) {
            if (tag.name.startsWith("/") || ids.size >= MAX_IDS) continue
            Markup.attr(tag.attrs, "id")?.let { ids.putIfAbsent(it, tag.start) }
            Markup.attr(tag.attrs, "xml:id")?.let { ids.putIfAbsent(it, tag.start) }
        }
        return ids
    }

    private fun fallbackAnchors(
        pack: EpubPackage.Package,
        sections: List<String>,
    ): List<Anchor> =
        pack.spine.indices
            .filter { HtmlToText.convert(sections[it]).length >= MIN_STANDALONE }
            .mapIndexed { n, i -> Anchor("Section ${n + 1}", i, null) }

    private fun tocAnchors(
        zip: ZipFile,
        pack: EpubPackage.Package,
    ): List<Anchor> {
        val nav =
            pack.navPath?.let { path ->
                EpubPackage.entryText(zip, path, EpubPackage.MAX_XML_BYTES)?.let { EpubToc.navEntries(it) }?.map {
                    path to
                        it
                }
            }
        val raw =
            nav
                ?: pack.ncxPath
                    ?.let { path ->
                        EpubPackage.entryText(zip, path, EpubPackage.MAX_XML_BYTES)?.let { EpubToc.ncxEntries(it) }?.map {
                            path to
                                it
                        }
                    }.orEmpty()
        return raw.mapNotNull { (base, entry) ->
            val (path, fragment) = EpubPackage.resolve(base, entry.href)
            val section = pack.spine.indexOf(path)
            if (section < 0) null else Anchor(entry.title.ifBlank { "Chapter" }, section, fragment)
        }
    }

    private const val MAX_SECTION_BYTES = 8L shl 20
    private const val MAX_TOTAL_CHARS = 32L shl 20
    private const val MAX_SECTIONS = 5_000
    private const val MAX_IDS = 100_000
    private const val MIN_STANDALONE = 200
}
