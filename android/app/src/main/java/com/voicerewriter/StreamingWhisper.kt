package com.voicerewriter

import android.content.Context
import android.util.Log
import com.voicerewriter.textproc.HallucinationFilter
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

        /** A spoken piece this long that loudness has not cut is cut at a Silero pause of [VAD_PAUSE_S]. */
        const val VAD_AFTER_S = RELAX_2_S
        const val VAD_PAUSE_S = RELAX_2_PAUSE_S

        /**
         * Speech Silero must have heard in a piece for it to be decoded. Shorter than
         * [MIN_VOICED_S]: a one-word answer is under 0.3s of its chunks, and this check only has
         * to stop pieces with no speech at all.
         */
        private const val MIN_VAD_VOICED_S = 0.1

        /**
         * See [vadTrusted]. Not measured, set between what the phone logs showed: takes Silero
         * did not hear (soft or whispered) had it at 0-10% of the voiced frames — one sat right
         * on a first value of 10% and was trusted — and takes it heard at 65% and more.
         */
        const val VAD_TRUST = 0.3
        private const val FRAME = 320 // 20ms
        private const val SR = AudioRecorder.SAMPLE_RATE
        /** A piece needs this much sound to be decoded; whisper invents text for silence. */
        private const val MIN_VOICED_S = 0.3

        const val TAIL_MARGIN_S = 0.4

        /**
         * 조용히 말하기에서 이미 앞 조각이 있는 긴 녹음의 마지막 꼬리 조각은 자동 중지 대기(2.2~5초)가
         * 통째로 붙어 전체 길이가 길어질 수 있다(2026-11-28 로그: 총 2.77s 중 실제 소리 0.1s에서 "감사합니다.").
         * 전체 길이 대신 [QuietSpeechDetector]가 말로 본 소리 길이 상한으로 무음·잡음 꼬리 환각을 거른다.
         */
        const val QUIET_TAIL_MAX_VOICED_S = 1.0

        private val QUIET_TAIL_HALLUCINATIONS = setOf("감사합니다", "감사합니다감사합니다", "고맙습니다")

        /**
         * 조용히 말하기에서 앞 조각이 이미 있는 녹음의 마지막 꼬리 조각이 짧은 잡음만으로
         * 단독 감사 상투구를 냈는지 판정한다. 단독 1조각 녹음이나 다른 말이 섞인 문장은 건드리지 않는다.
         */
        internal fun dropsQuietTailHallucination(text: String, voicedSamples: Int, isQuietTail: Boolean): Boolean {
            if (!isQuietTail || voicedSamples > (QUIET_TAIL_MAX_VOICED_S * SR).toInt()) return false
            val n = HallucinationFilter.norm(text)
            return n.isNotEmpty() && n in QUIET_TAIL_HALLUCINATIONS
        }

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

        /** Where to cut, the first frame not yet examined, and whether the VAD rule found it. */
        internal class Cut(val sample: Int, val nextFrame: Int, val byVad: Boolean)

        /**
         * The cut rule for spoken takes. By loudness first ([findCut] over the frame energies
         * [e]): that is the rule the chunk benchmark measured, and in a quiet room it cuts every
         * 5-9s. Where the room never gets 26 dB under the voice (a moving car: 100s in one
         * piece), a piece that has reached [VAD_AFTER_S] is cut at a [VAD_PAUSE_S] pause in
         * Silero's verdicts [vad] instead. The earlier of the two wins.
         */
        internal fun pickCut(e: List<Float>, vad: List<Float>?, pieceStart: Int, fromE: Int, fromVad: Int, level: Float): Cut? {
            val loud = findCut(e, pieceStart, fromE, level)
            val v = vad?.let { findCut(it, pieceStart, fromVad, 1f, pauseS = VAD_PAUSE_S, minPieceS = VAD_AFTER_S) }
            return when {
                loud == null && v == null -> null
                v == null || (loud != null && loud.first <= v.first) -> Cut(loud!!.first, loud.second, false)
                else -> Cut(v.first, v.second, true)
            }
        }

        /**
         * Whether Silero's verdicts can end the take or drop a piece: only when it has heard at
         * least [VAD_TRUST] of what the loudness test calls voiced ([voicedFrames] against
         * [vadFrames], counted over the take so far). A soft voice it does not hear at all
         * otherwise costs the whole take: 23.4s that Whisper could have read came back empty
         * because Silero heard 0.03s of it (phone log, build-175).
         */
        internal fun vadTrusted(voicedFrames: Int, vadSpeechFrames: Int): Boolean =
            vadSpeechFrames > 0 && vadSpeechFrames >= voicedFrames * VAD_TRUST

        /**
         * Why the piece [from, to) is not worth decoding, or null if it is. [voiced] is its
         * samples over the loudness test. Loud is not speech — road noise passes that test — so
         * Silero ([flags], per 512-sample chunk) must have heard some too, if it is [trusted]
         * ([vadTrusted]): one that hears next to nothing leaves the old rule and loses no words.
         */
        internal fun skipReason(voiced: Int, flags: BooleanArray?, from: Int, to: Int, trusted: Boolean): String? {
            if (voiced < MIN_VOICED_S * SR) return "sound %.2fs".format(voiced / SR.toFloat())
            if (flags == null || !trusted) return null
            val a = from / SileroVad.CHUNK
            val b = minOf(flags.size, (to - 1) / SileroVad.CHUNK + 1)
            val speech = (a until b).count { flags[it] } * SileroVad.CHUNK
            return if (speech < MIN_VAD_VOICED_S * SR) "no speech by the VAD (%.2fs)".format(speech / SR.toFloat()) else null
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
        internal fun findCut(e: List<Float>, pieceStart: Int, fromFrame: Int, level: Float, pauseS: Double = PAUSE_S, relax: Boolean = false, minPieceS: Double = MIN_PIECE_S): Pair<Int, Int>? {
            val thr = maxOf(level * 0.05f, 1e-4f)
            fun frames(s: Double) = (s * SR / FRAME).toInt()
            val full = frames(pauseS)
            var run = 0
            // A run already in progress at fromFrame is counted from its start.
            val from = minOf(fromFrame, e.size)
            var i = from
            while (i > 0 && e[i - 1] < thr) { i--; run++ }
            i = from
            while (i < e.size) {
                run = if (e[i] < thr) run + 1 else 0
                val len = (i - run + 1) * FRAME - pieceStart // the piece up to where this pause began
                val need = when {
                    !relax -> full
                    len >= RELAX_2_S * SR -> minOf(full, frames(RELAX_2_PAUSE_S))
                    len >= RELAX_1_S * SR -> minOf(full, frames(RELAX_1_PAUSE_S))
                    else -> full
                }
                if (run >= need && len >= (minPieceS * SR).toInt()) {
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
     * Silero's verdict per frame (1 speech, 0 none) for a spoken take, beside [energies]: the
     * second chance of [pickCut]. Null in quiet mode or without the VAD.
     */
    private val vadFrames: ArrayList<Float>? = if (quiet == null && recorder.speechFlags() != null) ArrayList() else null
    private var vadScanFrom = 0
    private var loudCuts = 0
    private var vadCuts = 0
    private val energies = ArrayList<Float>()  // touched only by scan()
    @Volatile private var currentLevel = 0f   // speech level for decode(), set by scan()
    @Volatile private var trustVad = false     // [vadTrusted] for the take so far, set by scan()
    private data class Piece(val range: IntRange, val isQuietTail: Boolean = false, val quietVoicedSamples: Int = 0, val gen: Int = 0)

    private var pieceStart = 0      // sample where the pending piece begins
    private var scanFrom = 0        // first frame not yet checked for a cut
    @Volatile private var activeGen = 0
    private var workerGen = 0
    private val queue = Channel<Piece>(Channel.UNLIMITED)
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

    private fun enqueue(p: Piece) {
        queuedSamples.addAndGet((p.range.last + 1 - p.range.first).toLong())
        queue.trySend(p)
    }

    fun start() {
        worker = scope.launch(Dispatchers.Default) {
            for (p in queue) {
                if (failure != null) continue
                if (p.gen != activeGen) {
                    doneSamples.addAndGet((p.range.last + 1 - p.range.first).toLong())
                    continue
                }
                if (p.gen != workerGen) {
                    texts.clear()
                    workerGen = p.gen
                }
                try { decode(p) } catch (t: Throwable) { failure = t; Log.w(TAG, "piece failed", t) }
                val sec = (p.range.last + 1 - p.range.first) / SR.toFloat()
                doneSamples.addAndGet((p.range.last + 1 - p.range.first).toLong())
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
        val gen = recorder.streamGeneration
        if (gen != activeGen) {
            activeGen = gen
            energies.clear()
            vadFrames?.clear()
            pieceStart = 0
            scanFrom = 0
            vadScanFrom = 0
            pieces = 0
            loudCuts = 0
            vadCuts = 0
            currentLevel = 0f
            trustVad = false
        }
        val len = recorder.rawLength()
        val have = energies.size * FRAME
        if (len - have >= FRAME) {
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
        if (vadFrames != null) recorder.speechFlags()?.let { vadFrames.addAll(framesFromFlags(it, vadFrames.size)) }
        val lvl = if (quiet != null) 1f else level(energies)
        currentLevel = lvl
        if (vadFrames != null) {
            val thr = maxOf(lvl * 0.1f, 1e-4f)
            trustVad = vadTrusted(energies.count { it > thr }, vadFrames.count { it > 0.5f })
        }
        while (true) {
            val c = if (quiet != null) {
                findCut(energies, pieceStart, scanFrom, lvl, QUIET_PAUSE_S, relax = true)?.let { Cut(it.first, it.second, false) }
            } else {
                pickCut(energies, vadFrames, pieceStart, scanFrom, vadScanFrom, lvl)
            } ?: break
            enqueue(Piece(pieceStart until c.sample, gen = activeGen))
            pieces++
            if (c.byVad) vadCuts++ else loudCuts++
            pieceStart = c.sample
            scanFrom = c.nextFrame
            vadScanFrom = c.nextFrame
        }
        // Frames already checked can't start a new qualifying run on their own; keep a pause
        // that is still growing (findCut walks back over it).
        scanFrom = maxOf(scanFrom, energies.size)
        if (vadFrames != null) vadScanFrom = maxOf(vadScanFrom, vadFrames.size)
    }

    private suspend fun decode(p: Piece) {
        val r = p.range
        val pcm = recorder.gatedCopy(r.first, r.last + 1)
        val samples = WavIo.toFloats(pcm)
        if (quiet != null) {
            if (!QuietSpeechDetector.hasSpeech(samples)) return
        } else {
            val lvl = currentLevel
            var voiced = 0
            var off = 0
            while (off + FRAME <= pcm.size) {
                if (rms(pcm, off, off + FRAME) > maxOf(lvl * 0.1f, 1e-4f)) voiced += FRAME
                off += FRAME
            }
            skipReason(voiced, recorder.speechFlags(), r.first, r.last + 1, trustVad)?.let {
                Log.i(TAG, "piece ${"%.1f".format(pcm.size / SR.toFloat())}s skipped: $it")
                return
            }
        }
        val t0 = System.nanoTime()
        val text = when (WhisperModelManager.model(model).engine) {
            SpeechEngine.WHISPER -> LocalWhisperStt.transcribe(context, s, samples, bias(), previousText = texts.joinToString(" "))
            else -> LocalSherpaStt.transcribe(context, s, samples)
        }.trim()
        val ms = (System.nanoTime() - t0) / 1_000_000
        TranscribeProgress.record(model, pcm.size / SR.toFloat(), ms)
        if (dropsQuietTailHallucination(text, p.quietVoicedSamples, p.isQuietTail)) {
            Log.i(TAG, "piece ${"%.1f".format(pcm.size / SR.toFloat())}s -> ${ms}ms (dropped quiet tail hallucination: ${text.length} chars, voiced ${"%.2f".format(p.quietVoicedSamples / SR.toFloat())}s)")
            return
        }
        Log.i(TAG, "piece ${"%.1f".format(pcm.size / SR.toFloat())}s -> ${ms}ms" +
            if (p.isQuietTail) " (quiet tail voiced ${"%.2f".format(p.quietVoicedSamples / SR.toFloat())}s)" else "")
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
        // A spoken take ends where either test stops hearing a voice: over road noise the
        // loudness test hears one to the very end.
        val end = if (quiet != null) recorder.rawLength() else minOf(
            tailEnd(energies, pieceStart, recorder.rawLength(), currentLevel),
            vadFrames?.takeIf { trustVad }?.let { tailEnd(it, pieceStart, recorder.rawLength(), 1f) } ?: Int.MAX_VALUE,
        )
        if (end > pieceStart) {
            val isQuietTail = quiet != null && pieces > 0
            val quietVoiced = if (isQuietTail) {
                val fromF = (pieceStart / FRAME).coerceIn(0, energies.size)
                val toF = ((end + FRAME - 1) / FRAME).coerceIn(fromF, energies.size)
                (fromF until toF).count { energies[it] >= 0.5f } * FRAME
            } else 0
            enqueue(Piece(pieceStart until end, isQuietTail = isQuietTail, quietVoicedSamples = quietVoiced, gen = activeGen))
        }
        queue.close()
        // What is still to decode now that the user has stopped: the tail plus any piece queued
        // or in flight. The ring on the bubble estimates from this.
        TranscribeProgress.begin(model, (queuedSamples.get() - doneSamples.get()) / SR.toFloat(), android.os.SystemClock.uptimeMillis())
        val t0 = System.nanoTime()
        worker?.join()
        if (quiet != null) Log.i(TAG, "quiet pauses: ${pauseStats(energies)}")
        else {
            // Why a take was or was not cut, readable from a copied debug log: the loudness rule
            // needs pauses 26 dB under p95, the VAD rule a 0.3s pause once a piece is 11s long.
            Log.i(TAG, "levels: ${levelStats(energies)}, cuts: $loudCuts by loudness, $vadCuts by vad" +
                (vadFrames?.let { ", vad ${if (trustVad) "trusted" else "not trusted"}, vad pauses: ${pauseStats(it)}" } ?: ""))
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
