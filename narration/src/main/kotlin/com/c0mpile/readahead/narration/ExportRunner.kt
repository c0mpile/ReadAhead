package com.c0mpile.readahead.narration

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException

/** What to export: the chapters (already chosen and in order), who wrote the book, and where the files go. */
class ExportRequest(
    val title: String,
    val author: String?,
    val chapters: List<ExportChapter>,
    /** Start with "Title. By Author." as a chapter of its own. */
    val opening: Boolean,
    val outDir: File,
    val keepChapterFiles: Boolean,
)

sealed interface ExportProgress {
    data class Rendering(
        val chapter: Int,
        val chapters: Int,
        val title: String,
        val chunk: Int,
        val chunks: Int,
        /** Share of all the audio to render that is done, 0 to 1, over every chapter being rendered. */
        val overall: Float = 0f,
    ) : ExportProgress

    data object Joining : ExportProgress

    data class Done(
        val file: File,
    ) : ExportProgress
}

/** An export stopped; [message] says which chapter and why, in words for the user. */
class ExportException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Renders chapters one after the other: each chapter's text is cut into pieces of at most [ExportChunker.LIMIT]
 * characters, each piece goes to the speech engine, and the pieces' audio is written in order to one file per
 * chapter. A chapter file that already exists is kept, so an interrupted export resumes where it stopped. When every
 * chapter file exists they are joined into one audiobook.
 */
class ExportRunner(
    private val newRenderer: () -> ChunkRenderer,
    private val encoder: AudioEncoder,
    private val joiner: BookJoiner,
    private val work: File,
    /** How many chapters are rendered at the same time, each with an engine of its own. */
    private val parallel: Int = 1,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    /** Close the engines this runner made when it is done (they are [AutoCloseable]). */
    private val closeRenderers: Boolean = true,
) {
    /** One engine used for everything, one chapter at a time; the caller keeps ownership of it. */
    constructor(
        renderer: ChunkRenderer,
        encoder: AudioEncoder,
        joiner: BookJoiner,
        work: File,
        retryDelayMs: Long = RETRY_DELAY_MS,
    ) : this({ renderer }, encoder, joiner, work, 1, retryDelayMs, closeRenderers = false)

    private class Step(
        val title: String,
        val text: String,
        val file: File,
    )

    suspend fun run(
        request: ExportRequest,
        onProgress: (ExportProgress) -> Unit = {},
    ): File {
        request.outDir.mkdirs()
        work.mkdirs()
        val steps = steps(request)
        val todo = steps.withIndex().filter { !it.value.file.exists() }
        renderAll(todo, steps.size, onProgress)
        onProgress(ExportProgress.Joining)
        val book = File(request.outDir, "${safeName(listOfNotNull(request.title, request.author).joinToString(" - "))}.m4b")
        book.delete()
        try {
            joiner.join(steps.map { it.file }, steps.map { it.title }, book, request.title, request.author)
        } catch (e: IOException) {
            book.delete()
            throw ExportException("The chapters were rendered but joining them failed: ${e.message}", e)
        }
        if (!request.keepChapterFiles) steps.forEach { it.file.delete() }
        onProgress(ExportProgress.Done(book))
        return book
    }

    /** Renders the chapters in [todo], at most [parallel] at a time; the first failure stops the others. */
    private suspend fun renderAll(
        todo: List<IndexedValue<Step>>,
        count: Int,
        onProgress: (ExportProgress) -> Unit,
    ) {
        if (todo.isEmpty()) return
        val width = parallel.coerceIn(1, todo.size)
        val made = ArrayList<ChunkRenderer>()
        val pool = Channel<Pair<Int, ChunkRenderer>>(width)
        repeat(width) { slot -> pool.trySend(slot to newRenderer().also { made += it }) }
        val sizes =
            todo.associate {
                it.index to
                    it.value.text.length
                        .coerceAtLeast(1)
            }
        val total = sizes.values.sum().toFloat()
        val done = HashMap<Int, Float>()
        try {
            coroutineScope {
                val gate = Semaphore(width)
                for ((index, step) in todo) {
                    launch {
                        gate.withPermit {
                            val (slot, renderer) = pool.receive()
                            try {
                                renderChapter(step, index, count, renderer, File(work, "chunk-$slot.wav")) { p ->
                                    synchronized(done) {
                                        done[index] = p.chunk.toFloat() / p.chunks * sizes.getValue(index)
                                        onProgress(p.copy(overall = done.values.sum() / total))
                                    }
                                }
                                synchronized(done) { done[index] = sizes.getValue(index).toFloat() }
                            } finally {
                                pool.trySend(slot to renderer)
                            }
                        }
                    }
                }
            }
        } finally {
            if (closeRenderers) made.forEach { (it as? AutoCloseable)?.close() }
        }
    }

    private fun steps(request: ExportRequest): List<Step> {
        val all = ArrayList<Pair<String, String>>()
        if (request.opening) {
            all += OPENING_TITLE to (listOfNotNull(request.title, request.author?.let { "By $it" }).joinToString(". ") + ".")
        }
        request.chapters.mapTo(all) { it.title to it.text }
        return all.mapIndexed { i, (title, text) ->
            Step(title, text, File(request.outDir, "%02d - %s.m4a".format(i, safeName(title))))
        }
    }

    private suspend fun renderChapter(
        step: Step,
        index: Int,
        count: Int,
        renderer: ChunkRenderer,
        wavFile: File,
        onProgress: (ExportProgress.Rendering) -> Unit,
    ) {
        val chunks = ExportChunker.split(step.text)
        if (chunks.isEmpty()) throw ExportException("${step.title} has no text to read.")
        val part = File(step.file.path + ".part")
        var audio: ChapterAudio? = null
        try {
            for ((n, chunk) in chunks.withIndex()) {
                onProgress(ExportProgress.Rendering(index, count, step.title, n, chunks.size))
                val wav = renderWithRetries(renderer, wavFile, chunk, step.title, n)
                val readable = readWav(wav) ?: throw ExportException("The speech engine wrote unusable audio for ${step.title}.")
                val sink = audio ?: encoder.open(part, readable.sampleRate, readable.channels).also { audio = it }
                try {
                    sink.writeWav(wav)
                    sink.writeSilence(PAUSE_MS)
                } catch (e: IOException) {
                    throw ExportException("${step.title}: ${e.message}", e)
                } finally {
                    wav.delete()
                }
            }
            audio?.finish()
            audio = null
            if (!part.renameTo(step.file)) throw ExportException("Could not save ${step.file.name}.")
        } finally {
            audio?.close()
            part.delete()
        }
    }

    private suspend fun renderWithRetries(
        renderer: ChunkRenderer,
        wav: File,
        text: String,
        title: String,
        chunk: Int,
    ): File {
        repeat(ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            wav.delete()
            val ok = runCatching { renderer.render(text, wav) }.getOrDefault(false)
            if (ok && readWav(wav) != null) return wav
            // After a failure the engine can stay broken until it is rebuilt.
            renderer.reset()
            if (attempt < ATTEMPTS - 1) delay(retryDelayMs)
        }
        throw ExportException(
            "The speech engine failed on part ${chunk + 1} of $title, $ATTEMPTS times in a row. " +
                "The finished chapters are kept; start again to continue.",
        )
    }

    companion object {
        const val OPENING_TITLE = "Opening"
        private const val ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
        private const val PAUSE_MS = 200
        private const val MAX_NAME = 80
        private val UNSAFE = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]+")

        /** A file name made from [text]: no path characters, at most 80 characters, never empty. */
        fun safeName(text: String): String =
            text
                .replace(UNSAFE, " ")
                .trim()
                .replace(Regex(" +"), " ")
                .take(MAX_NAME)
                .trim(' ', '.')
                .ifEmpty { "Book" }
    }
}
