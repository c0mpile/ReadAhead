package com.c0mpile.readahead.narration

import java.nio.ByteBuffer

/** One tag of an HTML or XML text: its lower-case [name] (with a leading `/` for a closing tag), attributes and where it sits. */
internal class Tag(
    val name: String,
    val attrs: String,
    val start: Int,
    val end: Int,
)

/**
 * A small linear scanner over untrusted markup. It never backtracks: every `<` is looked at once, each `>` is searched
 * for once, and a tag without a `>` ends the scan, so no input makes it slower than a single pass (the regex versions
 * it replaces were quadratic on unclosed tags). It is not an HTML parser; it only finds tags.
 */
internal object Markup {
    fun tags(
        text: String,
        from: Int = 0,
        to: Int = text.length,
    ): Sequence<Tag> =
        sequence {
            var pos = from
            while (pos < to) {
                val lt = text.indexOf('<', pos)
                if (lt < 0 || lt >= to) break
                val gt = tagEnd(text, lt)
                if (gt < 0 || gt >= to) break
                if (!text.startsWith("<!--", lt)) tagAt(text, lt, gt)?.let { yield(it) }
                pos = gt + 1
            }
        }

    /** The index of the `>` that ends the tag (or comment) opening at [lt]; -1 when it never ends. */
    private fun tagEnd(
        text: String,
        lt: Int,
    ): Int =
        if (text.startsWith("<!--", lt)) {
            text.indexOf("-->", lt + COMMENT_OPEN).let { if (it < 0) -1 else it + COMMENT_CLOSE - 1 }
        } else {
            text.indexOf('>', lt + 1)
        }

    private fun tagAt(
        text: String,
        lt: Int,
        gt: Int,
    ): Tag? {
        val inner = text.substring(lt + 1, gt)
        var nameEnd = if (inner.startsWith('/')) 1 else 0
        while (nameEnd < inner.length && !inner[nameEnd].isWhitespace() && inner[nameEnd] != '/') nameEnd++
        val name = inner.substring(0, nameEnd).lowercase()
        return if (name.isEmpty()) null else Tag(name, inner.substring(nameEnd), lt, gt + 1)
    }

    /** The value of attribute [name] in [attrs] (case-insensitive), or null. */
    fun attr(
        attrs: String,
        name: String,
    ): String? {
        val reader = AttrReader(attrs)
        while (true) {
            val (key, value) = reader.next() ?: return null
            if (key.equals(name, ignoreCase = true)) return value
        }
    }

    /** Reads `name="value"` pairs one after the other from the attribute part of a tag. */
    private class AttrReader(
        private val s: String,
    ) {
        private var i = 0

        fun next(): Pair<String, String>? {
            while (i < s.length) {
                skip { it.isWhitespace() || it == '/' }
                val key = take { !it.isWhitespace() && it != '=' && it != '/' }
                skip(Char::isWhitespace)
                if (i < s.length && s[i] == '=') {
                    i++
                    skip(Char::isWhitespace)
                    return key to value()
                }
            }
            return null
        }

        private fun value(): String {
            val quote = s.getOrNull(i)
            if (quote != '"' && quote != '\'') return take { !it.isWhitespace() }
            val close = s.indexOf(quote, i + 1).let { if (it < 0) s.length else it }
            return s.substring(i + 1, close).also { i = close + 1 }
        }

        private inline fun skip(test: (Char) -> Boolean) {
            while (i < s.length && test(s[i])) i++
        }

        private inline fun take(test: (Char) -> Boolean): String {
            val from = i
            skip(test)
            return s.substring(from, i)
        }
    }

    /** Whether the space-separated attribute [name] (such as `epub:type`) holds [token]. */
    fun hasToken(
        attrs: String,
        name: String,
        token: String,
    ): Boolean = attr(attrs, name)?.split(' ', '\t', '\n')?.any { it == token } == true

    /**
     * The index after the next `</name` tag at or after [from] (case-insensitive), or null when there is none. One
     * forward search that copies nothing, so skipping many closed elements stays one pass over the text.
     */
    fun closeEnd(
        text: String,
        from: Int,
        name: String,
    ): Int? {
        val close = text.indexOf("</$name", from, ignoreCase = true)
        if (close < 0) return null
        return text.indexOf('>', close).let { if (it < 0) text.length else it + 1 }
    }

    /**
     * Everything from [from] up to the next `</name` and the index after that tag, or null when there is none within
     * [limit] characters. The search window is at most [limit] characters (copied), so many unclosed tags cannot
     * each search to the end of the text.
     */
    fun untilClose(
        text: String,
        from: Int,
        name: String,
        limit: Int,
    ): Pair<String, Int>? {
        val window = text.substring(from, minOf(text.length, from + limit))
        val close = window.indexOf("</$name", ignoreCase = true)
        if (close < 0) return null
        val gt = window.indexOf('>', close).let { if (it < 0) window.length else it + 1 }
        return window.substring(0, close) to from + gt
    }

    fun readInt(
        bytes: ByteArray,
        at: Int,
    ): Int = ByteBuffer.wrap(bytes).getInt(at)

    fun writeInt(
        bytes: ByteArray,
        at: Int,
        value: Int,
    ) {
        ByteBuffer.wrap(bytes).putInt(at, value)
    }

    private const val COMMENT_OPEN = 4
    private const val COMMENT_CLOSE = 3
}
