package com.c0mpile.readahead.narration

/** One table-of-contents entry of the book, with the length of its text. */
internal data class TocEntry(
    val title: String,
    val section: Int,
    val chars: Int,
)

/** The entries of a book sorted into front matter, story and back matter by their titles and position. */
internal object ChapterMatter {
    /** Entries shorter than this are not a story start (a title page, a dedication). */
    private const val MIN_STORY_CHARS = 600

    fun classify(
        entries: List<TocEntry>,
        bodyStart: Int? = null,
    ): List<Matter> {
        val start = startIndex(entries, bodyStart)
        val end = endIndex(entries, start)
        return entries.indices.map { i ->
            when {
                i < start -> Matter.FRONT
                i > end -> Matter.BACK
                else -> Matter.STORY
            }
        }
    }

    /** The first story entry: the book's own body-matter marker if there is one, else the first one that is not front matter. */
    private fun startIndex(
        entries: List<TocEntry>,
        bodyStart: Int?,
    ): Int {
        if (bodyStart != null) entries.indexOfFirst { it.section >= bodyStart }.let { if (it >= 0) return it }
        val prologue = entries.indexOfFirst { PROLOGUE.matches(norm(it.title)) && it.chars >= MIN_STORY_CHARS }
        val chapter = entries.indexOfFirst { CHAPTER.containsMatchIn(norm(it.title)) }
        if (prologue >= 0 && (chapter < 0 || prologue < chapter)) return prologue
        if (chapter >= 0) return chapter
        val first = entries.indexOfFirst { !FRONT.matches(norm(it.title)) && it.chars >= MIN_STORY_CHARS }
        return if (first >= 0) first else 0
    }

    /** The last story entry: everything after it that is titled like back matter is left out. */
    private fun endIndex(
        entries: List<TocEntry>,
        start: Int,
    ): Int {
        var end = entries.lastIndex
        while (end > start && BACK.matches(norm(entries[end].title))) end--
        return end
    }

    private fun norm(title: String) =
        title
            .lowercase()
            .replace(NON_WORD, " ")
            .trim()
            .replace(SPACES, " ")

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex(" +")
    private val PROLOGUE = Regex("(the )?(prologue|prolog|prelude)( .*)?")
    private val CHAPTER =
        Regex("^((chapter|part|book|section) .+|(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)( .*)?)$")
    private val FRONT =
        Regex(
            "(cover|title( page)?|half ?title|copyright( page)?|also by .*|books by .*|dedication|epigraph|contents|table of contents|" +
                "foreword|preface|author s note|a note .*|praise for .*|about this book|map|maps|list of .*|acknowledg(e)?ments)",
        )
    private val BACK =
        Regex(
            "(acknowledg(e)?ments?|about the author|about the authors|also by .*|books by .*|more from .*|" +
                "afterword|author s note|a note .*|glossary|appendix.*|notes|endnotes|bibliography|index|credits|copyright( page)?|" +
                "excerpt.*|sneak peek.*|preview.*|newsletter.*|join .*|thank you.*|coming soon.*|the end)",
        )
}
