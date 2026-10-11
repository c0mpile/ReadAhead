package com.c0mpile.readahead

import com.c0mpile.readahead.narration.ExportProgress
import com.c0mpile.readahead.narration.ExportRequest
import com.c0mpile.readahead.narration.VoiceChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** What the export looks like from the screen. */
sealed interface ExportUi {
    data object Idle : ExportUi

    data class Running(
        val progress: ExportProgress?,
    ) : ExportUi

    data class Done(
        val file: File,
    ) : ExportUi

    data class Failed(
        val message: String,
    ) : ExportUi

    data object Cancelled : ExportUi
}

/** A request waiting for the service, and the service's state; both live only as long as the process. */
object ExportState {
    @Volatile var pending: Triple<ExportRequest, VoiceChoice, Int>? = null

    private val _ui = MutableStateFlow<ExportUi>(ExportUi.Idle)
    val ui: StateFlow<ExportUi> = _ui

    fun set(state: ExportUi) {
        _ui.value = state
    }
}
