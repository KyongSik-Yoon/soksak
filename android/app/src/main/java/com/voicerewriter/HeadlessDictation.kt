package com.voicerewriter

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.voicerewriter.textproc.VocabCorrector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dictation without RewriteActivity: record, transcribe and insert from the bubble's own
 * foreground service, so nothing takes window focus from the app being typed into.
 *
 * Why the sheet existed: on Android 14+ the microphone is a while-in-use permission, and a
 * background overlay cannot *create* a microphone foreground service. But a service that was
 * put into the foreground with the microphone type while our UI was visible keeps that
 * capability. [BubbleService] does exactly that whenever it can ([BubbleService.micReady]); this
 * class only runs when it did, and otherwise the caller falls back to the sheet.
 *
 * Scope, deliberately narrow: on-device STT, auto-insert, no review sheet and no LLM polish
 * (the polish model is English-tuned; Korean dictation runs with it off). Everything else —
 * write-ahead audio, vocab bias and correction, history — matches the sheet's voice path.
 *
 * If the OS hands us a silenced microphone anyway (an OEM that revokes it in the background),
 * the take is abandoned within [SILENT_PROBE_MS], [BubbleService.micReady] is cleared, and the
 * sheet takes over, so the failure costs one re-spoken sentence rather than a lost one.
 */
object HeadlessDictation {

    private const val TAG = "HeadlessDictation"
    private const val SILENT_PROBE_MS = 700L
    private const val PROGRESS_TICK_MS = 50L

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var recorder: AudioRecorder? = null
    private var streaming: StreamingWhisper? = null
    private var ampJob: Job? = null
    private var hostPkg: String = ""
    private var startMs = 0L

    @Volatile var active = false
        private set

    /** Whether a headless take can start right now. Cheap; called on every bubble gesture. */
    fun canRun(ctx: Context, s: Settings): Boolean =
        s.headlessRecording &&
            BubbleService.micReady &&
            OpenWisprAccessibilityService.isEnabled &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            OnDeviceStt.isReady(ctx, s.sttModel)

    /**
     * Start a take. Returns false (having done nothing) if the recorder could not start, so
     * the caller can open the sheet instead. Main thread.
     */
    fun start(ctx: Context, s: Settings, pushToTalk: Boolean, onSilentMic: () -> Unit): Boolean {
        if (active) return true
        val app = ctx.applicationContext
        val rec = AudioRecorder(app)
        hostPkg = OpenWisprAccessibilityService.lastHostPackage.orEmpty()
        startMs = System.currentTimeMillis()
        try {
            // Same rule as the sheet: hold-to-talk never auto-stops, the release ends it.
            rec.start(vadAutoStop = s.vadAutoStop && !pushToTalk, hangoverSeconds = s.autoStopSeconds, quiet = s.quietMode, onAutoStop = { stop(app, s) })
        } catch (e: Exception) {
            Log.w(TAG, "recorder failed to start", e)
            return false
        }
        recorder = rec
        active = true
        // Whisper: start decoding at each pause while the user is still talking.
        streaming = if (s.pipelinedWhisper) {
            var cached: String? = null
            var loaded = false
            StreamingWhisper(app, s, rec, scope) {
                if (!loaded) {
                    val vocab = VocabRepository(app).get()
                    cached = if (vocab.isEmpty()) null else VocabCorrector.biasPrompt(vocab)
                    loaded = true
                }
                cached
            }.also { it.start() }
        } else null
        BubbleService.instance?.showRecording()
        BubbleService.recordingStopper = { stop(app, s) }

        var loudest = 0
        ampJob = scope.launch {
            while (isActive && rec.isRecording) {
                val raw = rec.amplitude()
                if (raw > loudest) loudest = raw
                BubbleService.instance?.showAmplitude(raw)
                DictationStatus.silence.value = rec.silenceProgress()
                if (loudest == 0 && System.currentTimeMillis() - startMs > SILENT_PROBE_MS) {
                    // A live microphone is never digital zero for this long; a silenced one is.
                    Log.w(TAG, "microphone is silenced in the background; falling back to the sheet")
                    abandon()
                    BubbleService.micReady = false
                    onSilentMic()
                    return@launch
                }
                delay(55)
            }
        }
        // Hold-to-talk released before the recorder existed: end the take now.
        if (pushToTalk && !BubbleService.holdingToTalk) stop(app, s)
        return true
    }

    /** Abandon the take without transcribing it. */
    fun abandon() {
        ampJob?.cancel()
        streaming?.cancel()
        streaming = null
        BubbleService.recordingStopper = null
        recorder?.cancel()
        recorder = null
        active = false
        BubbleService.instance?.showIdle()
    }

    private fun stop(ctx: Context, s: Settings) {
        val rec = recorder ?: return
        recorder = null
        ampJob?.cancel()
        BubbleService.recordingStopper = null
        val samples = rec.stop()
        val stream = streaming
        streaming = null
        if (samples == null) {
            stream?.cancel()
            active = false
            BubbleService.instance?.showIdle()
            return
        }
        BubbleService.instance?.showWorking()
        val durationSec = ((System.currentTimeMillis() - startMs) / 1000L).toInt().coerceAtLeast(1)
        val host = hostPkg
        scope.launch {
            var pendingId: String? = null
            try {
                val label = appLabel(ctx, host)
                // Write-ahead, as in the sheet: a failed or killed transcription stays
                // retryable from Home instead of taking the words with it.
                pendingId = withContext(Dispatchers.IO) {
                    PendingAudio.begin(ctx, samples, durationSec, host, label, s.sttModel)?.id
                }
                val vocab = VocabRepository(ctx).get()
                val bias = if (vocab.isEmpty()) null else VocabCorrector.biasPrompt(vocab)
                // Pieces already decoded while recording; only the tail is left. If any piece
                // failed, decode the whole take as before. The ticker feeds the progress ring.
                val ticker = launch {
                    while (isActive) {
                        DictationStatus.progress.value = TranscribeProgress.fraction(SystemClock.uptimeMillis()) ?: 0f
                        delay(PROGRESS_TICK_MS)
                    }
                }
                val raw = try {
                    stream?.finish() ?: run {
                        val floats = WavIo.toFloats(samples)
                        TranscribeProgress.begin(
                            OnDeviceStt.resolveModel(ctx, s.sttModel),
                            floats.size / AudioRecorder.SAMPLE_RATE.toFloat(),
                            SystemClock.uptimeMillis(),
                        )
                        OnDeviceStt.transcribe(ctx, s, floats, bias)
                    }
                } finally {
                    ticker.cancel()
                    TranscribeProgress.end()
                }
                DictationStatus.progress.value = 1f
                val text = if (vocab.isEmpty()) raw else VocabCorrector.correct(raw, vocab)
                if (text.isBlank()) {
                    Log.i(TAG, "empty transcript (${s.sttModel}, ${durationSec}s)")
                    toast(ctx, tr("Nothing was recognized."))
                    BubbleService.instance?.showIdle()
                    pendingId?.let { PendingAudio.release(it) }
                    return@launch
                }
                PcLink.deliver(s, text) {
                    if (!OpenWisprAccessibilityService.enqueueInsert(text)) {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("rewrite", text))
                        BubbleService.instance?.showClipboardFallback()
                    }
                }
                LastDictation.set(ctx, text)
                recordHistory(ctx, host, label, durationSec, raw, text, s)
                pendingId?.let { id -> withContext(Dispatchers.IO) { runCatching { PendingAudio.settle(ctx, id, text) } } }
            } catch (e: Exception) {
                Log.e(TAG, "headless transcription failed; audio kept for retry", e)
                // Say why: a silent return to idle looked like "nothing happened".
                toast(ctx, e.message ?: e.javaClass.simpleName)
                pendingId?.let { PendingAudio.release(it) }
                BubbleService.instance?.showIdle()
            } finally {
                active = false
            }
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun recordHistory(ctx: Context, pkg: String, label: String, durationSec: Int, before: String, after: String, s: Settings) {
        val words = after.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val entry = DictationEntry(
            id = "${System.currentTimeMillis()}-${after.hashCode() and 0xffff}",
            timestamp = System.currentTimeMillis(),
            appPackage = pkg,
            appLabel = label,
            durationSec = durationSec,
            words = words,
            accepted = true,
            onDevice = true,
            before = before,
            after = after,
        )
        Thread { runCatching { DictationHistory.record(ctx, entry) } }.start()
    }

    private fun appLabel(ctx: Context, pkg: String): String {
        if (pkg.isBlank()) return "Dictation"
        return runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    }
}
