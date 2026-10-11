package com.c0mpile.readahead.narration

/**
 * Cuts a chapter's text into pieces for the speech engine: at most [limit] characters each, ended at a sentence end
 * where possible, else at a space, else mid-word. Measured limit of the ElevenReader engine: 1,281 characters
 * rendered, 1,301 failed; 1,200 is the safe size. Pieces are in reading order and together hold every character
 * of the input except the spaces between pieces.
 */
internal object ExportChunker {
    const val LIMIT = 1_200

    fun split(
        text: String,
        limit: Int = LIMIT,
    ): List<String> {
        require(limit > 0) { "limit must be positive" }
        val out = ArrayList<String>()
        var rest = text.replace(WHITESPACE, " ").trim()
        while (rest.length > limit) {
            val cut = cutAt(rest, limit)
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private fun cutAt(
        text: String,
        limit: Int,
    ): Int {
        // The last sentence end that still fits; a closing quote or bracket after the full stop goes with it.
        var sentence = -1
        for (i in 0 until minOf(limit, text.length - 1)) {
            if (text[i + 1] == ' ' && endsSentence(text, i)) sentence = i + 1
        }
        if (sentence >= limit / MIN_FRACTION) return sentence
        val space = text.lastIndexOf(' ', limit)
        return if (space > 0) space else limit
    }

    private fun endsSentence(
        text: String,
        i: Int,
    ): Boolean = text[i] in SENTENCE_END || (text[i] in CLOSERS && i > 0 && text[i - 1] in SENTENCE_END)

    private val WHITESPACE = Regex("\\s+")
    private const val SENTENCE_END = ".!?…"
    private const val MIN_FRACTION = 3
    private const val CLOSERS = "\"'”’)]"
}
