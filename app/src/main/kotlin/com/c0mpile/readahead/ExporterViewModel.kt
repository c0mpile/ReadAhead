package com.c0mpile.readahead

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.net.toUri
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

    /** The folder finished audiobooks are saved to (as `Author/Title`); null until the user picks one. */
    var destination by mutableStateOf(savedDestination())
        private set

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

    private fun savedDestination(): Uri? = prefs.getString("destination", null)?.toUri()?.takeIf(::canWrite)

    private fun canWrite(uri: Uri): Boolean =
        getApplication<Application>().contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    /** Remembers [tree] across restarts (the service writes to it later, possibly with the app closed). */
    fun pickDestination(tree: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(tree, flags)
        destination?.takeIf { it != tree }?.let { old -> runCatching { resolver.releasePersistableUriPermission(old, flags) } }
        prefs.edit { putString("destination", tree.toString()) }
        destination = tree
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
        // A permission the user revoked in the system settings is found now, not after hours of rendering.
        val destination = savedDestination().also { this.destination = it } ?: return
        val title = epub.title ?: "Book"
        val outDir =
            File(
                getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                ExportRunner.safeName(listOfNotNull(epub.title, epub.author).joinToString(" - ")),
            )
        ExportState.pending =
            PendingExport(
                ExportRequest(title, epub.author, chapters, opening, outDir, keepChapters, epub.cover),
                VoiceChoice(engine, voice, rate),
                parallel,
                destination,
            )
        ExportService.start(getApplication())
    }
}
