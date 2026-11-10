package com.voicerewriter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicerewriter.textproc.VocabCorrector
import com.voicerewriter.ui.SoksakTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Re-run one saved recording through every downloaded Whisper model, with the current
 * language, glossary and style example, and show the text and time side by side. Picking a
 * model by reading its output on your own speech beats picking it by its size label.
 *
 * Recordings come from [PendingAudio], which keeps each dictation's audio for the retention
 * window set in Settings (nothing to compare if history is off).
 */
class CompareActivity : ComponentActivity() {

    private data class ModelRun(val modelId: String, val label: String, val text: String?, val ms: Long?, val error: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SoksakTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        var recordings by remember { mutableStateOf<List<PendingRecording>>(emptyList()) }
        var selected by remember { mutableStateOf<PendingRecording?>(null) }
        val rows = remember { mutableStateListOf<ModelRun>() }
        var running by remember { mutableStateOf<String?>(null) }
        var job by remember { mutableStateOf<Job?>(null) }

        LaunchedEffect(Unit) {
            recordings = withContext(Dispatchers.IO) { PendingAudio.all(applicationContext).take(20) }
        }

        fun compare(rec: PendingRecording) {
            job?.cancel()
            selected = rec
            rows.clear()
            job = scope.launch {
                val ctx = applicationContext
                val samples = withContext(Dispatchers.IO) { PendingAudio.samples(ctx, rec.id) }
                if (samples == null) {
                    rows.add(ModelRun("", "Recording", null, null, "This recording is no longer on the device."))
                    return@launch
                }
                val base = SettingsRepository(ctx).get()
                val vocab = VocabRepository(ctx).get()
                val bias = if (vocab.isEmpty()) null else VocabCorrector.biasPrompt(vocab)
                val floats = WavIo.toFloats(samples)
                val models = WhisperModelManager.MODELS.filter { WhisperModelManager.isReady(ctx, it.id) }
                if (models.isEmpty()) rows.add(ModelRun("", "Models", null, null, "No on-device model is downloaded."))
                for (m in models) {
                    running = m.label
                    val t0 = System.nanoTime()
                    val row = try {
                        val raw = OnDeviceStt.transcribe(ctx, base.copy(sttModel = m.id), floats, bias)
                        val text = if (vocab.isEmpty()) raw else VocabCorrector.correct(raw, vocab)
                        ModelRun(m.id, m.label, text, (System.nanoTime() - t0) / 1_000_000, null)
                    } catch (e: Exception) {
                        ModelRun(m.id, m.label, null, null, e.message ?: e.toString())
                    }
                    rows.add(row)
                }
                running = null
            }
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Compare models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val sel = selected
            if (sel == null) {
                Text(
                    "Pick a recent dictation. It is re-run on every downloaded model with your current " +
                        "language, dictionary and style example.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (recordings.isEmpty()) {
                    Text("No saved recordings yet. Dictate something first (with history on).",
                        style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recordings, key = { it.id }) { r -> RecordingItem(r) { compare(r) } }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${fmt(sel.timestamp)} · ${sel.durationSec}s · ${sel.appLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { job?.cancel(); running = null; selected = null; rows.clear() }) { Text("Back") }
                }
                Divider()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rows, key = { it.modelId + it.label }) { r -> ResultItem(r) }
                    running?.let { label ->
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("Running $label…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RecordingItem(r: PendingRecording, onClick: () -> Unit) {
        Column(
            Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(12.dp),
        ) {
            Text("${fmt(r.timestamp)} · ${r.durationSec}s · ${r.appLabel}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(r.result?.ifBlank { null } ?: "(not transcribed)", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
    }

    @Composable
    private fun ResultItem(r: ModelRun) {
        Column(
            Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                r.label + (r.ms?.let { " · ${"%.1f".format(it / 1000.0)}s" } ?: ""),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
            if (r.error != null) {
                Text(r.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                SelectionContainer { Text(r.text.orEmpty().ifBlank { "(empty)" }, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }

    private fun fmt(ts: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}
