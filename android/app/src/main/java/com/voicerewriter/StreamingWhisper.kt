package com.voicerewriter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Decodes a Whisper dictation in pieces while the user is still talking, so that when they stop
 * only the last few seconds are left to transcribe.
 *
 * Whisper's encoder cost grows with the clip, so a 20s dictation used to mean ~20s of waiting
 * after the tap. Here the take is cut at pauses — the first pause of >= [PAUSE_S] once the
 * pending piece is >= [MIN_PIECE_S] long — and each piece is decoded as soon as it exists, with
 * the text so far as its prompt. Benchmarked on Korean (scripts/chunk_bench_*.py, 2026-11-08):
 * same accuracy on 15s code-switched dictation (22.6% vs 23.8% CER), much better on longer or
 * pause-y takes (whole-clip decoding dropped entire sentences past ~30s: 45% vs 14% CER on real
 * speech), and ~3s instead of ~20s of waiting after stop.
 *
 * Only for Whisper models; SenseVoice decodes a whole take in about a second.
 * Saved recordings (Retry, Compare) still decode whole.
 */
class StreamingWhisper(
    private val context: Context,
    private val settings: Settings,
    private val recorder: AudioRecorder,
    private val scope: CoroutineScope,
    private val bias: suspend () -> String?,
) {
    companion object {
        private const val TAG = "StreamingWhisper"
        const val PAUSE_S = 0.5
        const val MIN_PIECE_S = 5.0
        private const val FRAME = 320 // 20ms
        private const val SR = AudioRecorder.SAMPLE_RATE
        /** A piece needs this much sound to be decoded; whisper invents text for silence. */
        private const val MIN_VOICED_S = 0.3

        /** Whether a take with these settings should stream (setting on, a Whisper model in use). */
        fun applies(context: Context, s: Settings): Boolean =
            s.pipelinedWhisper &&
                WhisperModelManager.model(OnDeviceStt.resolveModel(context, s.sttModel)).engine == SpeechEngine.WHISPER

        /** RMS of [a][from, to), normalized to 0..1. */
        internal fun rms(a: ShortArray, from: Int = 0, to: Int = a.size): Float {
            if (to <= from) return 0f
            var sum = 0.0
            for (i in from until to) { val v = a[i] / 32768.0; sum += v * v }
            return sqrt(sum / (to - from)).toFloat()
        }

        /** 95th percentile of [e] — the speech level, robust to a few loud clicks. */
        internal fun level(e: List<Float>): Float {
            if (e.isEmpty()) return 0f
            val sorted = e.sorted()
            return sorted[((sorted.size - 1) * 0.95).toInt()]
        }

        /**
         * Pure cut finder, shared with tests: given frame energies and where the pending piece
         * starts (sample offset), the sample offset to cut at, or null. Quiet = 26dB under the
         * speech level heard so far.
         */
        internal fun findCut(e: List<Float>, pieceStart: Int, fromFrame: Int, level: Float): Pair<Int, Int>? {
            val thr = maxOf(level * 0.05f, 1e-4f)
            val need = (PAUSE_S * SR / FRAME).toInt()
            var run = 0
            // A run already in progress at fromFrame is counted from its start.
            var i = fromFrame
            while (i > 0 && e[i - 1] < thr) { i--; run++ }
            i = fromFrame
            while (i < e.size) {
                run = if (e[i] < thr) run + 1 else 0
                if (run >= need && (i - run + 1) * FRAME - pieceStart >= (MIN_PIECE_S * SR).toInt()) {
                    return (i - run / 2) * FRAME to i + 1
                }
                i++
            }
            return null
        }
    }

    private val energies = ArrayList<Float>()  // touched only by scan()
    @Volatile private var currentLevel = 0f   // speech level for decode(), set by scan()
    private var pieceStart = 0      // sample where the pending piece begins
    private var scanFrom = 0        // first frame not yet checked for a cut
    private val queue = Channel<IntRange>(Channel.UNLIMITED)
    private val texts = ArrayList<String>()
    @Volatile private var failure: Throwable? = null
    private var scanner: Job? = null
    private var worker: Job? = null
    private val model = OnDeviceStt.resolveModel(context, settings.sttModel)
    private val s = settings.copy(sttModel = model)
    private var pieces = 0

    fun start() {
        worker = scope.launch(Dispatchers.Default) {
            for (r in queue) {
                if (failure != null) continue
                try { decode(r) } catch (t: Throwable) { failure = t; Log.w(TAG, "piece failed", t) }
            }
        }
        scanner = scope.launch(Dispatchers.Default) {
            while (isActive && recorder.isRecording) {
                scan()
                delay(250)
            }
        }
    }

    private fun scan() {
        val len = recorder.rawLength()
        val have = energies.size * FRAME
        if (len - have >= FRAME) {
            val pcm = recorder.rawCopy(have, len - (len - have) % FRAME)
            var off = 0
            while (off + FRAME <= pcm.size) { energies.add(rms(pcm, off, off + FRAME)); off += FRAME }
        }
        val lvl = level(energies)
        currentLevel = lvl
        while (true) {
            val (cut, next) = findCut(energies, pieceStart, scanFrom, lvl) ?: break
            queue.trySend(pieceStart until cut)
            pieces++
            pieceStart = cut
            scanFrom = next
        }
        // Frames already checked can't start a new qualifying run on their own; keep a pause
        // that is still growing (findCut walks back over it).
        scanFrom = maxOf(scanFrom, energies.size)
    }

    private suspend fun decode(r: IntRange) {
        val pcm = recorder.rawCopy(r.first, r.last + 1)
        val lvl = currentLevel
        var voiced = 0
        var off = 0
        while (off + FRAME <= pcm.size) {
            if (rms(pcm, off, off + FRAME) > maxOf(lvl * 0.1f, 1e-4f)) voiced += FRAME
            off += FRAME
        }
        if (voiced < MIN_VOICED_S * SR) return
        val t0 = System.nanoTime()
        val text = LocalWhisperStt.transcribe(
            context, s, WavIo.toFloats(pcm), bias(), previousText = texts.joinToString(" "),
        ).trim()
        Log.i(TAG, "piece ${"%.1f".format(pcm.size / SR.toFloat())}s -> ${(System.nanoTime() - t0) / 1_000_000}ms")
        if (text.isNotEmpty()) texts.add(text)
    }

    /**
     * Call after the recorder has stopped. Decodes what is left and returns the whole text, or
     * null if any piece failed — the caller then decodes the full take the old way.
     */
    suspend fun finish(): String? {
        scanner?.cancel()
        scanner?.join()
        scan()
        val end = recorder.rawLength()
        if (end > pieceStart) queue.trySend(pieceStart until end)
        queue.close()
        val t0 = System.nanoTime()
        worker?.join()
        Log.i(TAG, "finished: ${pieces + 1} pieces, wait after stop ${(System.nanoTime() - t0) / 1_000_000}ms")
        if (failure != null) return null
        return texts.joinToString(" ").trim()
    }

    fun cancel() {
        scanner?.cancel()
        queue.close()
        worker?.cancel()
    }
}
