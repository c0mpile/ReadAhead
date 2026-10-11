package com.c0mpile.readahead

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.c0mpile.readahead.narration.BookHandle
import com.c0mpile.readahead.narration.EngineInfo
import com.c0mpile.readahead.narration.EngineRenderer
import com.c0mpile.readahead.narration.EpubBook
import com.c0mpile.readahead.narration.EpubReader
import com.c0mpile.readahead.narration.ExportRequest
import com.c0mpile.readahead.narration.ExportRunner
import com.c0mpile.readahead.narration.Matter
import com.c0mpile.readahead.narration.VoiceChoice
import com.c0mpile.readahead.narration.VoiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The screens' state: the settings, the book that was opened, which chapters are ticked. */
class ExporterViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("export", 0)

    var engine by mutableStateOf(prefs.getString("engine", null))
        private set
    var voice by mutableStateOf(prefs.getString("voice", null))
        private set
    var rate by mutableStateOf(prefs.getFloat("rate", 1f))
        private set
    var keepChapters by mutableStateOf(prefs.getBoolean("keep", false))
        private set
    var parallel by mutableStateOf(prefs.getInt("parallel", 1))
        private set
    var opening by mutableStateOf(true)

    var engines by mutableStateOf<List<EngineInfo>>(emptyList())
        private set
    var voices by mutableStateOf<List<VoiceInfo>>(emptyList())
        private set

    var book by mutableStateOf<EpubBook?>(null)
        private set
    val ticked = mutableStateListOf<Boolean>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        refreshVoices()
    }

    fun pickEngine(value: String?) {
        engine = value
        voice = null
        prefs.edit {
            putString("engine", value)
            remove("voice")
        }
        refreshVoices()
    }

    fun pickVoice(value: String?) {
        voice = value
        prefs.edit { putString("voice", value) }
    }

    fun pickRate(value: Float) {
        rate = value
        prefs.edit { putFloat("rate", value) }
    }

    fun pickParallel(value: Int) {
        parallel = value
        prefs.edit { putInt("parallel", value) }
    }

    fun toggleKeep(value: Boolean) {
        keepChapters = value
        prefs.edit { putBoolean("keep", value) }
    }

    private fun refreshVoices() {
        viewModelScope.launch {
            val (e, v) = EngineRenderer.catalog(getApplication(), engine)
            if (e.isNotEmpty()) engines = e
            voices = v
        }
    }

    fun load(uri: Uri) {
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val opened =
                    withContext(Dispatchers.IO) {
                        val fd =
                            getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")
                                ?: throw IOException("Cannot open the file")
                        BookHandle(fd).use(EpubReader::read)
                    }
                book = opened
                ticked.clear()
                ticked.addAll(opened.chapters.map { it.matter == Matter.STORY })
            } catch (e: IOException) {
                error = e.message ?: "Cannot read this file."
                book = null
            } finally {
                loading = false
            }
        }
    }

    var saveMessage by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    /**
     * Copies the finished audiobook (and the chapter files, when they were kept) from the app's private folder into
     * the folder the user picked, which a file manager and other apps can reach and which survives uninstalling.
     */
    fun saveTo(
        tree: Uri,
        from: File,
    ) {
        saving = true
        saveMessage = null
        viewModelScope.launch {
            saveMessage =
                try {
                    val count = withContext(Dispatchers.IO) { copyAll(tree, from) }
                    "Saved $count ${if (count == 1) "file" else "files"} to the folder you chose."
                } catch (e: IOException) {
                    "Could not save: ${e.message}"
                } finally {
                    saving = false
                }
        }
    }

    private fun copyAll(
        tree: Uri,
        book: File,
    ): Int {
        val resolver = getApplication<Application>().contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val files =
            book.parentFile
                ?.listFiles { f -> f.extension == "m4a" || f.extension == "m4b" }
                .orEmpty()
                .sortedBy { it.name }
        for (file in files) {
            // A generic type, so the provider does not tack ".m4a" on to a ".m4b" name.
            val target =
                DocumentsContract.createDocument(resolver, parent, "application/octet-stream", file.name)
                    ?: throw IOException("the folder refused ${file.name}")
            val out = resolver.openOutputStream(target) ?: throw IOException("cannot write ${file.name}")
            out.use { sink -> file.inputStream().use { it.copyTo(sink) } }
        }
        return files.size
    }

    fun forgetSave() {
        saveMessage = null
    }

    fun close() {
        book = null
        ticked.clear()
    }

    /** Ticks nothing before [index]'s chapter. */
    fun startHere(index: Int) {
        for (i in ticked.indices) if (i < index) ticked[i] = false
        ticked[index] = true
    }

    /** Ticks nothing after [index]'s chapter. */
    fun endHere(index: Int) {
        for (i in ticked.indices) if (i > index) ticked[i] = false
        ticked[index] = true
    }

    val selectedCount get() = ticked.count { it }

    fun start() {
        val epub = book ?: return
        val chapters = epub.chapters.filterIndexed { i, _ -> ticked.getOrNull(i) == true }
        if (chapters.isEmpty() && !opening) return
        val title = epub.title ?: "Book"
        val outDir =
            File(
                getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                ExportRunner.safeName(listOfNotNull(epub.title, epub.author).joinToString(" - ")),
            )
        ExportState.pending =
            Triple(ExportRequest(title, epub.author, chapters, opening, outDir, keepChapters), VoiceChoice(engine, voice, rate), parallel)
        ExportService.start(getApplication())
    }
}
