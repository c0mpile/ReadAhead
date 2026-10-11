package com.c0mpile.readahead.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportChunkerTest {
    private fun sentence(n: Int) = "Word ".repeat(n).trim() + "."

    @Test
    fun shortTextIsOnePiece() {
        assertEquals(listOf("Hello there. Bye."), ExportChunker.split("  Hello   there.\n Bye. "))
    }

    @Test
    fun piecesNeverExceedTheLimitAndKeepTheOrder() {
        val text = (1..400).joinToString(" ") { "Sentence number $it ends here." }
        val pieces = ExportChunker.split(text)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= ExportChunker.LIMIT })
        assertEquals(text, pieces.joinToString(" "))
    }

    @Test
    fun cutsAtASentenceEnd() {
        val pieces = ExportChunker.split(sentence(150) + " " + sentence(150), limit = 1_000)
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it.endsWith(".") })
    }

    @Test
    fun aClosingQuoteStaysWithItsSentence() {
        val text = "\"" + sentence(100).dropLast(1) + ".\" " + sentence(100)
        val pieces = ExportChunker.split(text, limit = 560)
        assertTrue(pieces.first().endsWith(".\""))
    }

    @Test
    fun aSentenceLongerThanTheLimitBreaksAtASpace() {
        val pieces = ExportChunker.split("word ".repeat(1_000).trim(), limit = 1_200)
        assertTrue(pieces.all { it.length <= 1_200 && !it.startsWith(" ") && !it.endsWith(" ") })
        assertEquals("word ".repeat(1_000).trim(), pieces.joinToString(" "))
    }

    @Test
    fun aWordLongerThanTheLimitIsCutHard() {
        val pieces = ExportChunker.split("x".repeat(2_500), limit = 1_000)
        assertEquals(listOf(1_000, 1_000, 500), pieces.map { it.length })
    }

    @Test
    fun emptyTextHasNoPieces() {
        assertEquals(emptyList<String>(), ExportChunker.split(" \n "))
    }
}
