package com.c0mpile.readahead

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.c0mpile.readahead.narration.ExportProgress

class MainActivity : ComponentActivity() {
    private val model by viewModels<ExporterViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ExportTheme { Surface(Modifier.fillMaxSize()) { Root(model) } } }
    }
}

@Composable
private fun Root(model: ExporterViewModel) {
    val ui by ExportState.ui.collectAsState()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.start() }
    val start = {
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            model.start()
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        when (val state = ui) {
            is ExportUi.Running -> Running(state)
            is ExportUi.Done -> Done(model, state.file)
            is ExportUi.Failed -> Finished("Stopped", state.message, retry = start)
            ExportUi.Cancelled -> Finished("Cancelled", "The chapters that were finished are kept; start again to continue.", retry = start)
            ExportUi.Idle -> if (model.book == null) Home(model) else Chapters(model, start)
        }
    }
}

@Composable
private fun Home(model: ExporterViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::load) }
    Text("ReadAhead", style = MaterialTheme.typography.headlineMedium)
    Text("Turns an EPUB into a narrated audiobook with your speech engine.", Modifier.padding(vertical = 8.dp))
    Button(onClick = {
        picker.launch(arrayOf("application/epub+zip"))
    }, enabled = !model.loading) { Text(if (model.loading) "Reading…" else "Choose an EPUB") }
    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    Voice(model)
}

@Composable
private fun Voice(model: ExporterViewModel) {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text("Speech", style = MaterialTheme.typography.titleMedium)
    Choice(
        "Engine",
        model.engines.firstOrNull { it.packageName == model.engine }?.label ?: "System default",
        model.engines.map {
            it.label to
                it.packageName
        },
    ) { model.pickEngine(it) }
    Choice(
        "Voice",
        model.voices.firstOrNull { it.name == model.voice }?.let { "${it.language} · ${it.name}" } ?: "Engine default",
        model.voices.map {
            "${it.language} · ${it.name}" to
                it.name
        },
    ) { model.pickVoice(it) }
    Text("Speed ${"%.1f".format(model.rate)}×", Modifier.padding(top = 8.dp))
    Slider(model.rate, onValueChange = { model.pickRate((it * RATE_STEPS).toInt() / RATE_STEPS) }, valueRange = 0.5f..2f)
    Text("Chapters at the same time: ${model.parallel}", Modifier.padding(top = 8.dp))
    Slider(
        model.parallel.toFloat(),
        onValueChange = { model.pickParallel(it.toInt()) },
        valueRange = 1f..MAX_PARALLEL,
        steps =
            MAX_PARALLEL.toInt() - 2,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(model.keepChapters, onCheckedChange = model::toggleKeep)
        Text("  Keep the chapter files next to the audiobook")
    }
}

@Composable
private fun Choice(
    label: String,
    current: String,
    options: List<Pair<String, String>>,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text(current) }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("Default") }, onClick = {
                onPick(null)
                open = false
            })
            options.forEach { (text, value) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(text) }, onClick = {
                    onPick(value)
                    open =
                        false
                })
            }
        }
    }
}

@Composable
private fun ColumnScope.Chapters(
    model: ExporterViewModel,
    start: () -> Unit,
) {
    val book = model.book ?: return
    Text(book.title ?: "Untitled", style = MaterialTheme.typography.titleLarge)
    book.author?.let { Text(it) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Checkbox(model.opening, onCheckedChange = { model.opening = it })
        Text("Begin with the title and author")
    }
    LazyColumn(Modifier.weight(1f)) {
        itemsIndexed(book.chapters) { i, chapter ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Checkbox(model.ticked.getOrElse(i) { false }, onCheckedChange = { model.ticked[i] = it })
                Column(Modifier.weight(1f)) {
                    Text(chapter.title, maxLines = 1)
                    Text(
                        "${chapter.text.length / CHARS_PER_MINUTE_HINT} min · " + chapter.matter.name.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = { model.startHere(i) }) { Text("Start") }
                TextButton(onClick = { model.endHere(i) }) { Text("End") }
            }
        }
    }
    Voice(model)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = model::close) { Text("Back") }
        Button(
            onClick = start,
            enabled = model.selectedCount > 0 || model.opening,
        ) { Text("Start export (${model.selectedCount} chapters)") }
    }
}

@Composable
private fun Running(state: ExportUi.Running) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Text("Rendering", style = MaterialTheme.typography.headlineMedium)
    when (val p = state.progress) {
        is ExportProgress.Rendering -> {
            Text("Chapter ${p.chapter + 1} of ${p.chapters}: ${p.title}", Modifier.padding(top = 8.dp))
            Text("Part ${p.chunk + 1} of ${p.chunks}")
            LinearProgressIndicator(progress = {
                (p.chapter + p.chunk.toFloat() / p.chunks) / p.chapters
            }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
        ExportProgress.Joining -> Text("Joining the chapters…")
        else -> Text("Starting…")
    }
    OutlinedButton(onClick = {
        context.startService(android.content.Intent(context, ExportService::class.java).setAction("com.c0mpile.readahead.CANCEL"))
    }) { Text("Cancel") }
}

@Composable
private fun Done(
    model: ExporterViewModel,
    file: java.io.File,
) {
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { model.saveTo(it, file) } }
    Text("Done", style = MaterialTheme.typography.headlineMedium)
    Text(
        "The audiobook is in this app's own storage, which is erased if the app is uninstalled " +
            "and which file managers cannot open:\n${file.path}",
        Modifier.padding(vertical = 8.dp),
    )
    Text("Save a copy somewhere you can reach it:")
    Button(onClick = { picker.launch(null) }, enabled = !model.saving, modifier = Modifier.padding(vertical = 8.dp)) {
        Text(if (model.saving) "Saving…" else "Save to a folder")
    }
    model.saveMessage?.let { Text(it) }
    OutlinedButton(onClick = {
        model.forgetSave()
        ExportState.set(ExportUi.Idle)
    }) { Text("Back") }
}

@Composable
private fun Finished(
    title: String,
    text: String,
    retry: (() -> Unit)? = null,
) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Text(text, Modifier.padding(vertical = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        retry?.let { Button(onClick = it) { Text("Start again") } }
        OutlinedButton(onClick = { ExportState.set(ExportUi.Idle) }) { Text("Back") }
    }
}

private const val CHARS_PER_MINUTE_HINT = 900
private const val RATE_STEPS = 10f
private const val MAX_PARALLEL = 4f
