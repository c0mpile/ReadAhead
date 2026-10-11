package com.c0mpile.readahead.narration

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The speech engine and voice an export uses; null engine and voice mean the system defaults. */
data class VoiceChoice(
    val engine: String? = null,
    val voice: String? = null,
    val rate: Float = 1f,
)

/** An installed engine, as the user picks it. */
data class EngineInfo(
    val packageName: String,
    val label: String,
)

/** One voice of the engine in use. */
data class VoiceInfo(
    val name: String,
    val language: String,
)

/**
 * [ChunkRenderer] over an Android text-to-speech engine, through `synthesizeToFile`: no audio is played. The engine is
 * started on first use and rebuilt by [reset]. The app makes no network call; the engine may.
 */
class EngineRenderer(
    private val context: Context,
    private val choice: VoiceChoice,
) : ChunkRenderer,
    AutoCloseable {
    private var tts: TextToSpeech? = null
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var counter = 0

    override suspend fun render(
        text: String,
        file: File,
    ): Boolean {
        val engine = ensure()
        val id = "export-${counter++}"
        val done = CompletableDeferred<Boolean>().also { waiting[id] = it }
        val started =
            withContext(Dispatchers.Main) { engine.synthesizeToFile(text.take(TextToSpeech.getMaxSpeechInputLength()), null, file, id) }
        if (started != TextToSpeech.SUCCESS) {
            waiting.remove(id)
            return false
        }
        val ok = withTimeoutOrNull(RENDER_TIMEOUT_MS) { done.await() } == true
        waiting.remove(id)
        if (!ok) withContext(Dispatchers.Main) { engine.stop() }
        return ok && file.length() > 0
    }

    override fun reset() {
        tts?.shutdown()
        tts = null
    }

    override fun close() = reset()

    private suspend fun ensure(): TextToSpeech = tts ?: create().also { tts = it }

    private suspend fun create(): TextToSpeech =
        try {
            withTimeout(INIT_TIMEOUT_MS) {
                withContext(Dispatchers.Main) { start(context, choice.engine) }
            }.also(::configure)
        } catch (e: TimeoutCancellationException) {
            throw IOException("The speech engine did not start in time.", e)
        }

    private fun configure(engine: TextToSpeech) {
        engine.setSpeechRate(choice.rate)
        choice.voice?.let { name -> engine.voices?.firstOrNull { it.name == name }?.let { engine.voice = it } }
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit

                override fun onDone(utteranceId: String) {
                    waiting[utteranceId]?.complete(true)
                }

                @Deprecated("Replaced by the overload with an error code.")
                override fun onError(utteranceId: String) {
                    waiting[utteranceId]?.complete(false)
                }

                override fun onError(
                    utteranceId: String,
                    errorCode: Int,
                ) {
                    waiting[utteranceId]?.complete(false)
                }

                override fun onStop(
                    utteranceId: String,
                    interrupted: Boolean,
                ) {
                    waiting[utteranceId]?.complete(false)
                }
            },
        )
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 20_000L
        private const val RENDER_TIMEOUT_MS = 120_000L

        /** Starts [engine] (the default one when null); must run on the main thread. */
        internal suspend fun start(
            context: Context,
            engine: String?,
        ): TextToSpeech =
            suspendCancellableCoroutine { cont ->
                var created: TextToSpeech? = null
                val onInit =
                    TextToSpeech.OnInitListener { status ->
                        val t = created
                        if (status == TextToSpeech.SUCCESS && t != null) {
                            cont.resume(t)
                        } else {
                            t?.shutdown()
                            cont.resumeWithException(IOException("The speech engine could not start."))
                        }
                    }
                created =
                    try {
                        TextToSpeech(context, onInit, engine)
                    } catch (e: IllegalArgumentException) {
                        cont.resumeWithException(IOException("The speech engine is not available.", e))
                        null
                    }
                cont.invokeOnCancellation { created?.shutdown() }
            }

        /** The installed engines and the voices of [engine]. Starts the engine, which can take a moment. */
        suspend fun catalog(
            context: Context,
            engine: String?,
        ): Pair<List<EngineInfo>, List<VoiceInfo>> =
            withContext(Dispatchers.Main) {
                val t =
                    try {
                        start(context, engine)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        return@withContext emptyList<EngineInfo>() to emptyList()
                    }
                try {
                    val engines = t.engines.map { EngineInfo(it.name, it.label) }
                    val voices =
                        runCatching { t.voices }
                            .getOrNull()
                            .orEmpty()
                            .filter { "notInstalled" !in it.features }
                            .map { VoiceInfo(it.name, it.locale.getDisplayName()) }
                            .sortedWith(compareBy({ it.language }, { it.name }))
                    engines to voices
                } finally {
                    t.shutdown()
                }
            }
    }
}
