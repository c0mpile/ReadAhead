package com.c0mpile.readahead.narration

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterMatterTest {
    private fun e(
        title: String,
        chars: Int = 5_000,
    ) = TocEntry(title, 0, chars)

    private fun kinds(
        vararg entries: TocEntry,
        bodyStart: Int? = null,
    ) = ChapterMatter.classify(entries.toList(), bodyStart)

    private val f = Matter.FRONT
    private val s = Matter.STORY
    private val b = Matter.BACK

    @Test
    fun startsAtTheFirstChapter() {
        val k = kinds(e("Cover", 10), e("Title Page", 40), e("Copyright", 500), e("Contents", 300), e("Chapter 1"), e("Chapter 2"))
        assertEquals(listOf(f, f, f, f, s, s), k)
    }

    @Test
    fun aPrologueIsPartOfTheStory() {
        val k = kinds(e("Copyright", 400), e("Dedication", 50), e("Prologue", 3_000), e("Chapter 1"), e("Chapter 2"))
        assertEquals(listOf(f, f, s, s, s), k)
    }

    @Test
    fun aPrologueAfterAForewordStillStartsTheStory() {
        val k = kinds(e("Foreword", 4_000), e("Prologue"), e("Chapter One"))
        assertEquals(listOf(f, s, s), k)
    }

    @Test
    fun aPrefaceIsNotAPrologue() {
        val k = kinds(e("Preface", 4_000), e("1"), e("2"))
        assertEquals(listOf(f, s, s), k)
    }

    @Test
    fun backMatterAfterTheLastChapterIsLeftOut() {
        val k = kinds(e("Chapter 1"), e("Chapter 2"), e("Epilogue"), e("Acknowledgements"), e("About the Author"), e("Also by Jane Doe"))
        assertEquals(listOf(s, s, s, b, b, b), k)
    }

    @Test
    fun titledChaptersWithoutNumbersStartAtTheFirstLongEntry() {
        val k = kinds(e("Cover", 5), e("Copyright", 300), e("The Storm"), e("The Harbour"))
        assertEquals(listOf(f, f, s, s), k)
    }

    @Test
    fun aMarkerFromTheBookWins() {
        val entries = listOf(TocEntry("Cover", 0, 5), TocEntry("A Note", 1, 4_000), TocEntry("Day One", 2, 4_000))
        assertEquals(listOf(f, f, s), ChapterMatter.classify(entries, bodyStart = 2))
    }

    @Test
    fun wordsThatLookLikeRomanNumeralsAreNotChapters() {
        val k = kinds(e("Civil"), e("Chapter 1"))
        assertEquals(listOf(f, s), k)
    }

    @Test
    fun aBookWithOneEntryIsAllStory() {
        assertEquals(listOf(s), kinds(e("My Story")))
    }
}
