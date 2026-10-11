package com.c0mpile.readahead.narration

/** The table of contents of an EPUB, from the EPUB 3 `nav` document or the EPUB 2 NCX; both read by one forward scan each. */
internal object EpubToc {
    class TocLink(
        val title: String,
        val href: String,
    )

    /** The links of the `toc` navigation, in order. */
    fun navEntries(xhtml: String): List<TocLink> {
        val out = ArrayList<TocLink>()
        var inToc = false
        for (tag in Markup.tags(xhtml)) {
            when {
                tag.name == "nav" -> inToc = Markup.hasToken(tag.attrs, "epub:type", "toc")
                tag.name == "/nav" -> inToc = false
                inToc && tag.name == "a" && out.size < MAX_ENTRIES -> {
                    val href = Markup.attr(tag.attrs, "href") ?: continue
                    val label = Markup.untilClose(xhtml, tag.end, "a", LABEL_LIMIT)?.first.orEmpty()
                    out += TocLink(plain(label), decodeEntities(href))
                }
            }
        }
        return out
    }

    /** The first link of the `landmarks` navigation marked `bodymatter`: where the story starts, by the book's own account. */
    fun bodyMatterHref(xhtml: String): String? {
        var inLandmarks = false
        for (tag in Markup.tags(xhtml)) {
            when {
                tag.name == "nav" -> inLandmarks = Markup.hasToken(tag.attrs, "epub:type", "landmarks")
                tag.name == "/nav" -> inLandmarks = false
                inLandmarks && tag.name == "a" && Markup.hasToken(tag.attrs, "epub:type", "bodymatter") ->
                    return Markup.attr(tag.attrs, "href")?.let(::decodeEntities)
            }
        }
        return null
    }

    /** The NCX's `navPoint`s in document order (parents before their children). */
    fun ncxEntries(xml: String): List<TocLink> {
        val out = ArrayList<TocLink>()
        var label: String? = null
        var src: String? = null

        fun flush() {
            val l = label
            val s = src
            if (l != null && s != null && out.size < MAX_ENTRIES) out += TocLink(plain(l), decodeEntities(s))
            label = null
            src = null
        }
        for (tag in Markup.tags(xml)) {
            when (tag.name) {
                "navpoint" -> flush()
                "text" -> if (label == null) label = Markup.untilClose(xml, tag.end, "text", LABEL_LIMIT)?.first
                "content" -> if (src == null) src = Markup.attr(tag.attrs, "src")
            }
        }
        flush()
        return out
    }

    private fun plain(html: String) = HtmlToText.convert(html).replace('\n', ' ')

    fun decodeEntities(value: String) = value.replace("&amp;", "&")

    private const val MAX_ENTRIES = 5_000
    private const val LABEL_LIMIT = 2_000
}
