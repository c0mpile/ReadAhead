package com.c0mpile.readahead.narration

/** A chapter of the book as an export sees it: its title, its text and whether it is front matter, story or back matter. */
data class ExportChapter(
    val title: String,
    val text: String,
    val matter: Matter,
)

/** The parts of an EPUB an export needs. [chapters] are in reading order; the story ones are what is narrated by default. */
data class EpubBook(
    val title: String?,
    val author: String?,
    val chapters: List<ExportChapter>,
)

/** What a table-of-contents entry is, for choosing what an export narrates. */
enum class Matter { FRONT, STORY, BACK }
