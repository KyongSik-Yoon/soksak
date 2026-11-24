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
 * The NPU path takes no prompt, so its pieces don't see the text before them.
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

        /**
         * Quiet mode's pause. Longer, because its speech test ([QuietSpeechDetector]) misses the
         * weakest whispers, and a missed stretch must not pass for a pause and split a word.
         */
        const val QUIET_PAUSE_S = 0.7

        /**
         * Quiet mode takes a shorter pause the longer a piece has been waiting for one: 0.7s was
         * never found inside 28s of whispering read aloud (phone log, build-166), where spoken
         * takes cut every 5-9s. Past [RELAX_2_S] the piece is close to the NPU's 14s window,
         * which is cut at its quietest 0.2s whatever happens, so a 0.3s gap loses nothing.
         */
        const val RELAX_1_S = 8.0
        const val RELAX_1_PAUSE_S = 0.5
        const val RELAX_2_S = 11.0
        const val RELAX_2_PAUSE_S = 0.3

        /** Frames in a row before a sound counts as whispering (0.1s), as the auto-stop asks: a click or a breath is not a word and must not end a pause. */
        const val QUIET_SPEECH_RUN = 5
        const val MIN_PIECE_S = 5.0
        private const val FRAME = 320 // 20ms
        private const val SR = AudioRecorder.SAMPLE_RATE
        /** A piece needs this much sound to be decoded; whisper invents text for silence. */
        private const val MIN_VOICED_S = 0.3

        const val TAIL_MARGIN_S = 0.4

        /**
         * End sample for the last piece: [TAIL_MARGIN_S] after the last voiced frame at or after
         * [pieceStart], never past [rawEnd]. Voiced is the same test the pieces use (10% of the
         * speech level). With no voiced frame, [rawEnd] — the voiced-length check skips it then.
         */
        internal fun tailEnd(e: List<Float>, pieceStart: Int, rawEnd: Int, lvl: Float): Int {
            val thr = maxOf(lvl * 0.1f, 1e-4f)
            val first = pieceStart / FRAME
            var last = -1
            for (i in e.indices.reversed()) {
                if (i < first) break
                if (e[i] > thr) { last = i; break }
            }
            if (last < 0) return rawEnd
            return minOf(rawEnd, (last + 1) * FRAME + (TAIL_MARGIN_S * SR).toInt())
        }

        /** How many pauses of each length a quiet-mode take had, for tuning the cut rule from a phone log. */
        internal fun pauseStats(flags: List<Float>): String {
            val edges = listOf(0.2, 0.3, 0.5, 0.7, 1.0)
            val counts = IntArray(edges.size)
            var run = 0
            var speech = 0
            fun close() { val s = run * FRAME / SR.toDouble(); edges.forEachIndexed { k, e -> if (s >= e) counts[k]++ }; run = 0 }
            for (f in flags) if (f < 0.5f) run++ else { speech++; close() }
            close()
            val total = flags.size * FRAME / SR.toDouble()
            return "%.1fs, speech %d%%, pauses %s".format(total, if (flags.isEmpty()) 0 else speech * 100 / flags.size,
                edges.indices.joinToString(" ") { ">=${edges[it]}s:${counts[it]}" })
        }

        /**
         * Silero's per-chunk verdicts ([AudioRecorder.speechFlags], 512 samples each) as frame
         * values from [fromFrame] on: 1 for speech, 0 for none, by the chunk under the frame's
         * middle. Stops at the last frame the chunks cover.
         */
        internal fun framesFromFlags(flags: BooleanArray, fromFrame: Int): List<Float> {
            val n = flags.size * SileroVad.CHUNK / FRAME
            return (fromFrame until n).map { if (flags[(it * FRAME + FRAME / 2) / SileroVad.CHUNK]) 1f else 0f }
        }

        /** Speech level and how far under it the take's quieter frames sit, for the log: the loudness cut needs pauses 26 dB down. */
        internal fun levelStats(e: List<Float>): String {
            if (e.isEmpty()) return "no frames"
            val sorted = e.sorted()
            fun db(p: Double) = (20 * kotlin.math.log10(maxOf(sorted[((sorted.size - 1) * p).toInt()], 1e-5f).toDouble())).toInt()
            return "p95 ${db(0.95)}dB p50 ${db(0.5)}dB p25 ${db(0.25)}dB p10 ${db(0.1)}dB"
        }

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
        internal fun findCut(e: List<Float>, pieceStart: Int, fromFrame: Int, level: Float, pauseS: Double = PAUSE_S, relax: Boolean = false): Pair<Int, Int>? {
            val thr = maxOf(level * 0.05f, 1e-4f)
            fun frames(s: Double) = (s * SR / FRAME).toInt()
            val full = frames(pauseS)
            var run = 0
            // A run already in progress at fromFrame is counted from its start.
            var i = fromFrame
            while (i > 0 && e[i - 1] < thr) { i--; run++ }
            i = fromFrame
            while (i < e.size) {
                run = if (e[i] < thr) run + 1 else 0
                val len = (i - run + 1) * FRAME - pieceStart // the piece up to where this pause began
                val need = when {
                    !relax -> full
                    len >= RELAX_2_S * SR -> minOf(full, frames(RELAX_2_PAUSE_S))
                    len >= RELAX_1_S * SR -> minOf(full, frames(RELAX_1_PAUSE_S))
                    else -> full
                }
                if (run >= need && len >= (MIN_PIECE_S * SR).toInt()) {
                    return (i - run / 2) * FRAME to i + 1
                }
                i++
            }
            return null
        }
    }

    /**
     * Quiet mode (whispering): frames are judged by [QuietSpeechDetector] and [energies] holds 1
     * for speech, 0 for none. By plain loudness a whispered take was never cut — the room's
     * rumble under 500 Hz is louder than the whisper, so a pause is nowhere near 26 dB down —
     * and the whole of it was decoded after the stop: 39.8s in one piece, 6.8s of waiting,
     * against 1.1s for a spoken take in ten pieces (phone log, 2026-11-22).
     */
    private val quiet = if (settings.quietMode) QuietSpeechDetector(Int.MAX_VALUE, Int.MAX_VALUE) else null
    private var quietRun = 0 // speech frames in a row, for [QUIET_SPEECH_RUN]

    /**
     * Voice isolation ("내 목소리만"): frames come from the recorder's Silero verdicts on the
     * isolated audio, 1 for speech and 0 for none. By loudness no pause was found in it — every
     * isolated take went as one piece, 100.7s with 18.8s of waiting (phone log, 2026-11-24).
     */
    private val byVad = quiet == null && recorder.speechFlags() != null
    private val flagged get() = quiet != null || byVad
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
    // Audio sent to the decoder and audio it has finished, for [TranscribeProgress].
    private val queuedSamples = java.util.concurrent.atomic.AtomicLong()
    private val doneSamples = java.util.concurrent.atomic.AtomicLong()

    private fun enqueue(r: IntRange) {
        queuedSamples.addAndGet((r.last + 1 - r.first).toLong())
        queue.trySend(r)
    }

    fun start() {
        worker = scope.launch(Dispatchers.Default) {
            for (r in queue) {
                if (failure != null) continue
                try { decode(r) } catch (t: Throwable) { failure = t; Log.w(TAG, "piece failed", t) }
                val sec = (r.last + 1 - r.first) / SR.toFloat()
                doneSamples.addAndGet((r.last + 1 - r.first).toLong())
                TranscribeProgress.pieceDone(sec)
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
        if (byVad) {
            recorder.speechFlags()?.let { energies.addAll(framesFromFlags(it, energies.size)) }
        } else if (len - have >= FRAME) {
            val pcm = recorder.gatedCopy(have, len - (len - have) % FRAME)
            var off = 0
            while (off + FRAME <= pcm.size) {
                if (quiet != null) {
                    quiet.feedPcm(pcm.copyOfRange(off, off + FRAME), FRAME, have + off + FRAME)
                    quietRun = if (quiet.lastFrameSpeech) quietRun + 1 else 0
                    energies.add(if (quietRun >= QUIET_SPEECH_RUN) 1f else 0f)
                } else {
                    energies.add(rms(pcm, off, off + FRAME))
                }
                off += FRAME
            }
        }
        val lvl = if (flagged) 1f else level(energies)
        currentLevel = lvl
        while (true) {
            val (cut, next) = findCut(energies, pieceStart, scanFrom, lvl, if (quiet != null) QUIET_PAUSE_S else PAUSE_S, relax = flagged) ?: break
            enqueue(pieceStart until cut)
            pieces++
            pieceStart = cut
            scanFrom = next
        }
        // Frames already checked can't start a new qualifying run on their own; keep a pause
        // that is still growing (findCut walks back over it).
        scanFrom = maxOf(scanFrom, energies.size)
    }

    private suspend fun decode(r: IntRange) {
        val pcm = recorder.gatedCopy(r.first, r.last + 1)
        val samples = WavIo.toFloats(pcm)
        if (quiet != null) {
            if (!QuietSpeechDetector.hasSpeech(samples)) return
        } else if (byVad) {
            val flags = recorder.speechFlags() ?: BooleanArray(0)
            val a = r.first / SileroVad.CHUNK
            val b = minOf(flags.size, (r.last + 1) / SileroVad.CHUNK + 1)
            val voiced = (a until b).count { flags[it] } * SileroVad.CHUNK
            if (voiced < MIN_VOICED_S * SR) return
        } else {
            val lvl = currentLevel
            var voiced = 0
            var off = 0
            while (off + FRAME <= pcm.size) {
                if (rms(pcm, off, off + FRAME) > maxOf(lvl * 0.1f, 1e-4f)) voiced += FRAME
                off += FRAME
            }
            if (voiced < MIN_VOICED_S * SR) return
        }
        val t0 = System.nanoTime()
        val text = when (WhisperModelManager.model(model).engine) {
            SpeechEngine.WHISPER -> LocalWhisperStt.transcribe(context, s, samples, bias(), previousText = texts.joinToString(" "))
            else -> LocalSherpaStt.transcribe(context, s, samples)
        }.trim()
        val ms = (System.nanoTime() - t0) / 1_000_000
        TranscribeProgress.record(model, pcm.size / SR.toFloat(), ms)
        Log.i(TAG, "piece ${"%.1f".format(pcm.size / SR.toFloat())}s -> ${ms}ms")
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
        // The take ends with the auto-stop wait (1.6–5s of silence). Encoder time grows with clip
        // length, so decoding that silence was most of the wait after stop on Turbo, and silence
        // is where Whisper invents text. Keep a short margin after the last voiced frame.
        // Quiet mode keeps the whole tail, as its recorder trims nothing: the last whispered
        // syllables are the ones its speech test is likeliest to miss.
        val end = if (quiet != null) recorder.rawLength() else tailEnd(energies, pieceStart, recorder.rawLength(), currentLevel)
        if (end > pieceStart) enqueue(pieceStart until end)
        queue.close()
        // What is still to decode now that the user has stopped: the tail plus any piece queued
        // or in flight. The ring on the bubble estimates from this.
        TranscribeProgress.begin(model, (queuedSamples.get() - doneSamples.get()) / SR.toFloat(), android.os.SystemClock.uptimeMillis())
        val t0 = System.nanoTime()
        worker?.join()
        if (quiet != null) Log.i(TAG, "quiet pauses: ${pauseStats(energies)}")
        else {
            // Why a take was or was not cut, readable from a copied debug log: the loudness rule
            // needs pauses 26 dB under p95; an isolated take is cut by the VAD pauses instead.
            val all = recorder.gatedCopy(0, recorder.rawLength())
            val rmsFrames = List(all.size / FRAME) { rms(all, it * FRAME, (it + 1) * FRAME) }
            Log.i(TAG, "levels: ${levelStats(rmsFrames)}" + if (byVad) ", vad pauses: ${pauseStats(energies)}" else "")
        }
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
