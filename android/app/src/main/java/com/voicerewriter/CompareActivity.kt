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

    private companion object { const val BENCH_TAG = "SoksakBench" }

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

        // Every listed recording on every model, one model at a time (loaded once, so the time is
        // decode only), into logcat under [BENCH_TAG]: for scoring a set of takes against a
        // script off the device (adb logcat -s SoksakBench). Results show only in the log.
        fun runAll() {
            job?.cancel()
            job = scope.launch {
                val ctx = applicationContext
                val base = SettingsRepository(ctx).get()
                val vocab = VocabRepository(ctx).get()
                val bias = if (vocab.isEmpty()) null else VocabCorrector.biasPrompt(vocab)
                val recs = recordings.sortedBy { it.timestamp }
                val models = WhisperModelManager.MODELS.filter { WhisperModelManager.isReady(ctx, it.id) }
                android.util.Log.i(BENCH_TAG, "start recordings=${recs.size} models=${models.joinToString(",") { it.id }} quiet=${base.quietMode}")
                // Quiet mode's end-of-speech detector replayed over each take as the recorder
                // feeds it (512-sample frames): when it would have auto-stopped, and per half
                // second the loudest frame / noise floor in dB and how many frames counted as
                // speech. A take ended by hand keeps its tail, which shows why it did not fire.
                for ((i, rec) in recs.withIndex()) {
                    val samples = withContext(Dispatchers.IO) { PendingAudio.samples(ctx, rec.id) } ?: continue
                    val sr = AudioRecorder.SAMPLE_RATE
                    val det = QuietSpeechDetector((base.autoStopSeconds * sr).toInt(), (0.4 * sr).toInt())
                    fun db(v: Float) = (20 * kotlin.math.log10(maxOf(v, 1e-6f))).toInt()
                    var fireAt = -1
                    val series = StringBuilder()
                    var blockMax = 0f; var blockSpeech = 0; var blockEnd = sr / 2
                    var pos = 0
                    // Every frame's level too (dB, 100 per line), to try other rules off the device.
                    val frames = StringBuilder()
                    var frameCount = 0
                    while (pos + 512 <= samples.size) {
                        var sum = 0.0
                        for (j in pos until pos + 512) { val v = samples[j] / 32768.0; sum += v * v }
                        val rms = kotlin.math.sqrt(sum / 512).toFloat()
                        pos += 512
                        frames.append(' ').append(db(rms))
                        if (++frameCount % 100 == 0 || pos + 512 > samples.size) {
                            android.util.Log.i(BENCH_TAG, "frames n=${i + 1} at=${frameCount - 1 - (frameCount - 1) % 100}:$frames")
                            frames.setLength(0)
                        }
                        if (det.feed(rms, pos, 512) && fireAt < 0) fireAt = pos
                        if (rms > blockMax) blockMax = rms
                        if (det.lastFrameSpeech) blockSpeech++
                        if (pos >= blockEnd) {
                            series.append(' ').append(db(blockMax)).append('/').append(db(det.floor)).append('/').append(blockSpeech)
                            blockMax = 0f; blockSpeech = 0; blockEnd += sr / 2
                        }
                    }
                    android.util.Log.i(BENCH_TAG, "detector n=${i + 1} rec=${rec.id} sec=${samples.size / sr.toDouble()} " +
                        "fire=${if (fireAt < 0) "never" else "%.1f".format(fireAt / sr.toDouble())} max/floor/speech per 0.5s:$series")
                }
                for (m in models) {
                    running = "${m.label} (load)"
                    runCatching { OnDeviceStt.warm(ctx, m.id) }
                    recs.forEachIndexed { i, rec ->
                        running = "${m.label} ${i + 1}/${recs.size}"
                        val samples = withContext(Dispatchers.IO) { PendingAudio.samples(ctx, rec.id) } ?: return@forEachIndexed
                        val t0 = System.nanoTime()
                        val line = try {
                            val raw = OnDeviceStt.transcribe(ctx, base.copy(sttModel = m.id), WavIo.toFloats(samples), bias)
                            val text = if (vocab.isEmpty()) raw else VocabCorrector.correct(raw, vocab)
                            "ms=${(System.nanoTime() - t0) / 1_000_000} text=${text.replace('\n', ' ')}"
                        } catch (e: Exception) {
                            "error=${e.message ?: e}"
                        }
                        android.util.Log.i(BENCH_TAG, "model=${m.id} n=${i + 1} rec=${rec.id} sec=${samples.size / 16000.0} $line")
                    }
                }
                android.util.Log.i(BENCH_TAG, "done")
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
                if (recordings.isNotEmpty()) {
                    val r = running
                    if (r == null) {
                        TextButton(onClick = { runAll() }) { Text("Run all on every model (to log)") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Running $r…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
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
