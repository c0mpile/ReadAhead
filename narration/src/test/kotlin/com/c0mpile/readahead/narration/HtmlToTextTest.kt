package com.c0mpile.readahead.narration

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlToTextTest {
    @Test
    fun paragraphsBecomeBlankLineSeparatedText() {
        assertEquals("One.\n\nTwo.", HtmlToText.convert("<body><p>One.</p><p>Two.</p></body>"))
    }

    @Test
    fun headingsEndWithAFullStop() {
        assertEquals("CHAPTER 1.\n\nIt was cold.", HtmlToText.convert("<h2>CHAPTER 1</h2><p>It was cold.</p>"))
        assertEquals("Why?\n\nBecause.", HtmlToText.convert("<h2>Why?</h2><p>Because.</p>"))
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals("Tom & Jerry – “hi” é€", HtmlToText.convert("<p>Tom &amp; Jerry &ndash; &ldquo;hi&rdquo; &eacute;&#x20AC;</p>"))
    }

    @Test
    fun headScriptStyleAndFootnoteMarkersAreLeftOut() {
        val html =
            "<html><head><title>T</title><style>p{}</style></head><body><p>Hello<sup><a href=\"#n1\">1</a></sup> there</p>" +
                "<script>x()</script></body></html>"
        assertEquals("Hello there", HtmlToText.convert(html))
    }

    @Test
    fun lineBreaksSeparateLines() {
        assertEquals("Roses\n\nViolets", HtmlToText.convert("<p>Roses<br/>Violets</p>"))
    }

    @Test
    fun markupOnlyHasNoText() {
        assertEquals("", HtmlToText.convert("<p> <img src=\"a.png\"/> </p>"))
    }

    @Test
    fun brokenEntitiesStayAsTheyAre() {
        assertEquals("a &bogus; b &#99999999;", HtmlToText.convert("<p>a &bogus; b &#99999999;</p>"))
    }
}
