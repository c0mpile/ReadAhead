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
    val cover: BookCover? = null,
)

/** The image formats an MP4 `covr` tag can hold. */
enum class CoverFormat(
    internal val flag: Int,
) {
    JPEG(13),
    PNG(14),
}

/** A book's cover image as the file holds it (not decoded). */
class BookCover(
    val bytes: ByteArray,
    val format: CoverFormat,
) {
    companion object {
        private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_SIGNATURE =
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

        /** The cover for [bytes] if they start like a JPEG or a PNG; null for anything else. */
        fun of(bytes: ByteArray): BookCover? =
            when {
                bytes.startsWith(JPEG_SIGNATURE) -> BookCover(bytes, CoverFormat.JPEG)
                bytes.startsWith(PNG_SIGNATURE) -> BookCover(bytes, CoverFormat.PNG)
                else -> null
            }

        private fun ByteArray.startsWith(prefix: ByteArray) = size > prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}

/** What a table-of-contents entry is, for choosing what an export narrates. */
enum class Matter { FRONT, STORY, BACK }
