package com.c0mpile.readahead

import android.net.Uri
import com.c0mpile.readahead.narration.ExportProgress
import com.c0mpile.readahead.narration.ExportRequest
import com.c0mpile.readahead.narration.VoiceChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the export looks like from the screen. */
sealed interface ExportUi {
    data object Idle : ExportUi

    data class Running(
        val progress: ExportProgress?,
    ) : ExportUi

    data class Done(
        /** Where the audiobook was saved, as `Author/Title` inside the destination folder. */
        val location: String,
    ) : ExportUi

    data class Failed(
        val message: String,
    ) : ExportUi

    data object Cancelled : ExportUi
}

/** What the service is asked to do: the export, the engine to narrate with and the folder the result goes to. */
class PendingExport(
    val request: ExportRequest,
    val voice: VoiceChoice,
    val parallel: Int,
    val destination: Uri,
)

/** A request waiting for the service, and the service's state; both live only as long as the process. */
object ExportState {
    @Volatile var pending: PendingExport? = null

    private val _ui = MutableStateFlow<ExportUi>(ExportUi.Idle)
    val ui: StateFlow<ExportUi> = _ui

    fun set(state: ExportUi) {
        _ui.value = state
    }
}
