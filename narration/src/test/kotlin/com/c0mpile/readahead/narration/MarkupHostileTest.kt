package com.c0mpile.readahead.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hostile markup must take about as long as friendly markup of the same size (the regex parser it replaced was quadratic). */
class MarkupHostileTest {
    private val big = 2_000_000

    @Test(timeout = 5_000)
    fun manyUnclosedScriptsAreOnePass() {
        val text = HtmlToText.convert("<p>kept</p>" + "<script>".repeat(big / 8))
        assertEquals("kept", text)
    }

    @Test(timeout = 5_000)
    fun manyOpenAnglesWithoutAnEndAreOnePass() {
        val text = HtmlToText.convert("<p>kept</p>" + "<a ".repeat(big / 3))
        assertEquals("kept", text)
    }

    @Test(timeout = 5_000)
    fun manyUnclosedSupsAreOnePass() {
        val text = HtmlToText.convert("<p>a" + "<sup>".repeat(big / 5) + "b</p>")
        assertTrue(text.startsWith("a"))
    }

    @Test(timeout = 5_000)
    fun manyAmpersandsAreOnePass() {
        val text = HtmlToText.convert("<p>" + "&aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".repeat(big / 40) + "</p>")
        assertTrue(text.isNotEmpty())
    }

    @Test(timeout = 5_000)
    fun aHugeTocWithUnclosedLinksIsOnePass() {
        val nav = "<nav epub:type=\"toc\">" + "<a href=\"x.xhtml\">".repeat(big / 20)
        assertTrue(EpubToc.navEntries(nav).size <= 5_000)
    }

    @Test(timeout = 5_000)
    fun aHugeNcxWithoutEndsIsOnePass() {
        assertTrue(EpubToc.ncxEntries("<navPoint><navLabel><text>x".repeat(big / 25)).size <= 5_000)
    }

    @Test(timeout = 5_000)
    fun attributeJunkIsOnePass() {
        assertEquals(null, Markup.attr("a=\"" + "b".repeat(big), "href"))
        assertEquals(null, Markup.attr("x ".repeat(big / 2), "href"))
    }

    @Test(timeout = 5_000)
    fun manyClosedScriptsAreOnePass() {
        assertEquals("kept", HtmlToText.convert("<p>kept</p>" + "<script></script>".repeat(big / 16)))
    }

    @Test(timeout = 5_000)
    fun manyClosedStylesAndSvgsAreOnePass() {
        assertEquals("kept", HtmlToText.convert("<p>kept</p>" + "<style>a</style><svg><g/></svg>".repeat(big / 30)))
    }

    @Test(timeout = 5_000)
    fun manyShortLinksInAHugeNavAreOnePass() {
        val nav = "<nav epub:type=\"toc\">" + "<a href=\"x.xhtml\">t</a>".repeat(big / 24) + "</nav>"
        assertTrue(EpubToc.navEntries(nav).size <= 5_000)
    }
}
