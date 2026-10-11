package com.c0mpile.readahead.narration

/**
 * Plain text of an XHTML chapter, for the speech engine: tags dropped, entities decoded, blocks separated by blank
 * lines, headings ended with a full stop (so "CHAPTER 1" is not run into the first sentence), footnote markers left
 * out. One linear pass over the markup ([Markup]); the markup is untrusted and is never parsed as a document or loaded.
 */
internal object HtmlToText {
    fun convert(html: String): String {
        val out = StringBuilder()
        var pos = 0
        for (tag in Markup.tags(html)) {
            if (tag.start < pos) continue
            out.append(html, pos, tag.start)
            pos = tag.end
            pos = onTag(html, tag, out)
        }
        // What follows the last tag, without a tag that never ended (the scan stops at it).
        val tail = html.indexOf('<', pos).let { if (it < 0) html.length else it }
        if (pos < tail) out.append(html, pos, tail)
        return decode(out.toString())
            .split(SEPARATOR)
            .map(::tidy)
            .filter { it.any(Char::isLetterOrDigit) }
            .joinToString("\n\n")
    }

    /** What [tag] adds to [out]; returns where the text goes on (later than the tag's end when it hides what follows). */
    private fun onTag(
        html: String,
        tag: Tag,
        out: StringBuilder,
    ): Int {
        val name = tag.name
        when {
            name in SKIPPED && !tag.attrs.trimEnd().endsWith("/") ->
                // Skip to the matching close tag; an unclosed one hides the rest, which is one search at most.
                return Markup.closeEnd(html, tag.end, name) ?: html.length
            name == "sup" -> return footnoteEnd(html, tag.end) ?: tag.end
            name.length == HEADING_LENGTH && name.startsWith("/h") && name[2] in '1'..'6' -> out.append('.').append(SEPARATOR)
            name in BREAKS || (name.startsWith("/") && name.drop(1) in BLOCKS) -> out.append(SEPARATOR)
            else -> out.append(' ')
        }
        return tag.end
    }

    /** Where a footnote marker (`<sup>` holding only a short number) ends; null when the `<sup>` is something else. */
    private fun footnoteEnd(
        html: String,
        from: Int,
    ): Int? {
        // One bounded window, so unclosed <sup> tags cannot each search to the end of the chapter.
        val window = html.substring(from, minOf(html.length, from + FOOTNOTE_WINDOW))
        val close = window.indexOf("</sup", ignoreCase = true)
        if (close < 0 || !FOOTNOTE.matches(window.substring(0, close))) return null
        val gt = window.indexOf('>', close)
        return if (gt < 0) null else from + gt + 1
    }

    /** Whitespace collapsed; a full stop added by a heading is dropped again where the heading already ended in punctuation. */
    private fun tidy(paragraph: String): String =
        paragraph
            .replace(SPACES, " ")
            .trim()
            .replace(DOUBLE_STOP, "$1")

    private fun decode(text: String): String =
        if ('&' !in text) {
            text
        } else {
            ENTITY.replace(text) { m ->
                val body = m.groupValues[1]
                when {
                    body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.drop(2), HEX) ?: m.value
                    body.startsWith("#") -> codePoint(body.drop(1), DECIMAL) ?: m.value
                    else -> NAMED[body] ?: m.value
                }
            }
        }

    private fun codePoint(
        digits: String,
        radix: Int,
    ): String? =
        digits
            .toIntOrNull(radix)
            ?.takeIf { it in 1..Character.MAX_CODE_POINT && it !in SURROGATES }
            ?.let { String(Character.toChars(it)) }

    private const val SEPARATOR = '\u0001'
    private const val HEX = 16
    private const val DECIMAL = 10
    private const val HEADING_LENGTH = 3
    private const val FOOTNOTE_WINDOW = 400
    private val SURROGATES = 0xD800..0xDFFF
    private val SKIPPED = setOf("script", "style", "svg", "math", "noscript", "rt", "rp", "head")
    private val BREAKS = setOf("br", "br/", "hr", "hr/")
    private val BLOCKS =
        setOf(
            "p",
            "div",
            "li",
            "tr",
            "blockquote",
            "section",
            "article",
            "aside",
            "pre",
            "table",
            "ul",
            "ol",
            "dd",
            "dt",
            "figcaption",
            "caption",
            "header",
            "footer",
        )
    private val FOOTNOTE = Regex("\\s*(<a\\b[^>]*>)?\\s*\\[?\\d{1,3}]?\\s*(</a\\s*>)?\\s*")
    private val SPACES = Regex("[\\s\\u00A0]+")
    private val DOUBLE_STOP = Regex("([.!?:;,…\"”’)\\]])\\.$")
    private val ENTITY = Regex("&(#[xX]?[0-9a-fA-F]{1,8}|[a-zA-Z][a-zA-Z0-9]{0,31});")
    private val NAMED =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "ldquo" to "“",
            "rdquo" to "”",
            "lsquo" to "‘",
            "rsquo" to "’",
            "hellip" to "…",
            "mdash" to "—",
            "ndash" to "–",
            "shy" to "",
            "eacute" to "é",
            "egrave" to "è",
            "agrave" to "à",
            "uuml" to "ü",
            "ouml" to "ö",
            "auml" to "ä",
            "ccedil" to "ç",
            "ntilde" to "ñ",
            "copy" to "©",
        )
}
