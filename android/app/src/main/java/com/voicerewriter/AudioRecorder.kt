package com.voicerewriter

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Captures raw 16 kHz mono PCM16 from the mic with AudioRecord. [stop] hands back the
 * take as plain samples; persisting it is [PendingAudio]'s job, and every backend is fed
 * from that durable copy rather than from a buffer that only exists in this process.
 *
 * Requires the RECORD_AUDIO permission (the activity checks before start()).
 */
class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000 // Whisper is trained at 16 kHz

        /**
         * Peak sample value treated as "full" by the level meters.
         *
         * Measured, not guessed: a real 5.3s dictation recorded through this class peaks at 3592
         * of a possible 32767, with ordinary speech between 1300 and 2900 and the gaps between
         * words around 150. [MediaRecorder.AudioSource.VOICE_RECOGNITION] records quiet by
         * design — it is tuned for a recognizer, not for playback — so scaling against full
         * scale, or against the 14000 both meters used to use, pins every bar to its floor and
         * the waveform never visibly moves.
         */
        private const val FULL_SCALE_PEAK = 6000f

        /**
         * Map a raw peak from [amplitude] onto 0..1 for a level meter.
         *
         * Square root, because loudness is perceived roughly logarithmically: a linear meter
         * spends most of its travel on the loudest tenth of the range and leaves normal speech
         * crawling along the bottom. Shared by the bubble's waveform and the sheet's orb so the
         * two cannot drift apart — they are showing the same signal and should agree.
         */
        fun level(peak: Int): Float =
            sqrt((peak / FULL_SCALE_PEAK).coerceIn(0f, 1f))
        private const val SPEECH_START_PROB = SileroVad.SPEECH_PROB
        private const val SPEECH_END_PROB = 0.35f

        /**
         * Silence needed before auto-stop fires. Was 0.8s, which is shorter than an ordinary
         * mid-sentence pause ("so... the thing is") and cut people off mid-thought - the main
         * reason auto-stop felt broken on a phone. Tap is now the long-form gesture, so this
         * leans patient; tapping the bubble again ends it instantly.
         */
        private const val DEFAULT_HANGOVER_S = 1.6

        /**
         * Speech that must accumulate before auto-stop can arm at all. Without this, one
         * 32ms frame over the start threshold - a cough, a door, a keyboard click - counted
         * as "they started talking", and the hangover then ended the recording before the
         * user had said a word.
         */
        private const val MIN_SPEECH_SAMPLES = (0.4 * SAMPLE_RATE).toInt()

        private const val PRE_PAD_SAMPLES = (0.2 * SAMPLE_RATE).toInt()
        private const val POST_PAD_SAMPLES = (0.3 * SAMPLE_RATE).toInt()

        // A second Silero for the microphone's speaker gate under voice isolation; one take at a time.
        @Volatile private var secondVadInstance: SileroVad? = null
        private fun secondVad(context: Context): SileroVad? =
            secondVadInstance ?: synchronized(this) {
                secondVadInstance ?: SileroVad.create(context)?.also { secondVadInstance = it }
            }
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var recording = false
    @Volatile private var lastPeak = 0 // most recent buffer peak, for the waveform
    /** 원시 마이크에서 0이 아닌 샘플을 한 번이라도 받았는지 여부 (배경 무음 차단 감지용). */
    @Volatile var micDeliveredNonZero = false
        private set
    private val chunks = ArrayList<ShortArray>()

    // The raw take as one growing array, for [StreamingWhisper] to read pieces of while the
    // recording is still going. Kept after stop() until the next start().
    private val pcmLock = Any()
    private var pcm = ShortArray(SAMPLE_RATE * 30)
    @Volatile private var pcmLen = 0
    // One flag per Silero chunk of a spoken take (see [speechFlags]); guarded by pcmLock.
    private var speechFlagBuf = BooleanArray(2048)
    private var speechFlagLen = 0
    @Volatile private var keepSpeechFlags = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // VAD state (only used when a Silero model is available + enabled)
    private var vad: SileroVad? = null
    private var hangoverSamples = (DEFAULT_HANGOVER_S * SAMPLE_RATE).toInt()
    private var totalSamples = 0
    private var speechStarted = false
    private var firstSpeechSample = 0
    private var lastSpeechSample = 0
    private var autoStopFired = false
    // Microphone level before and after voice isolation, for the log line a take leaves.
    private var micSq = 0.0
    private var micPeak = 0
    private var micN = 0L
    private var isoSq = 0.0
    private var isoPeak = 0
    private var isoN = 0L
    // Longest single isolation run and when the microphone opened: an isolation run that waits
    // behind a Whisper decode on the same NPU for longer than the mic buffer loses audio.
    private var voxMaxMs = 0L
    // Voice isolation only: whether the voice is still coming through ([voiceUnheard]), and the
    // level before/after isolation in 5s steps for the log — "it stopped hearing me" is otherwise
    // a soft voice or a dead isolation stream with nothing to tell them apart.
    private var isolationWatch: IsolationWatch? = null
    private var micSound: QuietSpeechDetector? = null
    private val levelSteps = ArrayList<String>()
    private var stepMicSq = 0.0
    private var stepMicN = 0
    private var stepIsoSq = 0.0
    private var stepIsoN = 0
    private var startedAtMs = 0L
    private var speechSamples = 0
    private var quietDetector: QuietSpeechDetector? = null
    private var speakerGateSession: SpeakerGateSession? = null
    private var voxLockStream: VoxLockEngine.Stream? = null
    // Voice isolation only: the microphone's own audio, and the speaker gate run over it (not used
    // for auto-stop), for when isolation loses the voice ([isolationFellBack]). Guarded by pcmLock.
    private var micPcm = ShortArray(0)
    private var micPcmLen = 0
    private var micGate: SpeakerGateSession? = null
    private var micVad: SileroVad? = null
    private val micVadTail = FloatArray(SileroVad.CHUNK)
    private var micVadTailLen = 0
    private var micSpeech = false // Silero's verdict on the latest microphone chunk

    /**
     * Set by [stop]: voice isolation lost the voice ([IsolationWatch.lostVoice]), so the take it
     * returned is the microphone's audio (through the speaker gate when that is on), not the
     * isolated audio. Pieces decoded from the isolated audio while recording are not this take.
     */
    @Volatile var isolationFellBack = false
        private set
    private var rawCaptureOnlyMode = false

    // VoxLock 스트림처럼 가변 길이 출력을 내는 경로에서 512샘플 단위 VAD 잔여분 누적용
    private var vadTail = FloatArray(SileroVad.CHUNK)
    private var vadTailLen = 0

    val isRecording: Boolean get() = recording

    /** The speech of a take the speaker gate refused as a whole, and how close it came. */
    class GateRejection(val samples: ShortArray, val bestSimilarity: Float, val windows: Int)

    /**
     * Set by [stop] when it returned null because the speaker gate matched none of the speech to
     * the enrolled voice. Callers must say so and keep the audio: a silent return to idle looked
     * like the app had simply failed, and the words were gone.
     */
    @Volatile var gateRejection: GateRejection? = null
        private set

    /**
     * How close auto-stop is to ending the take, 0..1: the share of the silence wait already
     * spent since the last speech. 0 while talking, before any speech, or with auto-stop off.
     */
    fun silenceProgress(): Float {
        if (!vadActive || !recording) return 0f
        quietDetector?.let { return it.silenceFraction(totalSamples) }
        speakerGateSession?.let { return it.silenceProgress() }
        if (speechSamples < MIN_SPEECH_SAMPLES) return 0f
        return ((totalSamples - lastSpeechSample).toFloat() / hangoverSamples).coerceIn(0f, 1f)
    }

    /**
     * Whether this take will actually auto-stop. False when the setting is off *and* when the
     * Silero model failed to load, which is silent otherwise - the UI used to promise "I'll
     * stop when you pause" either way and then never stop.
     */
    @Volatile var vadActive: Boolean = false
        private set

    /** Samples captured so far in this take, untrimmed. */
    fun rawLength(): Int = pcmLen

    /** A copy of raw samples [from, to) of this take (clamped to what exists). */
    fun rawCopy(from: Int, to: Int): ShortArray = synchronized(pcmLock) {
        val a = from.coerceIn(0, pcmLen)
        val b = to.coerceIn(a, pcmLen)
        pcm.copyOfRange(a, b)
    }

    /** A copy of the microphone's samples [from, to) of a voice-isolated take. */
    private fun micCopy(from: Int, to: Int): ShortArray = synchronized(pcmLock) {
        val a = from.coerceIn(0, micPcmLen)
        val b = to.coerceIn(a, micPcmLen)
        micPcm.copyOfRange(a, b)
    }

    /** The speaker gate over [audio], or null without an enrolled voice. */
    private fun openGate(vadAutoStop: Boolean, audio: (Int, Int) -> ShortArray): SpeakerGateSession? {
        val embedder = VoiceLockManager.acquireEmbedder(context) ?: return null
        val enrolled = VoiceLockManager.getEnrolledEmbedding(context) ?: return null
        return SpeakerGateSession(
            scorer = { start, len -> embedder.scoreSimilarity(enrolled, WavIo.toFloats(audio(start, start + len))) },
            vadAutoStop = vadAutoStop,
            hangoverSamples = hangoverSamples,
        )
    }

    /** Recorder thread, voice isolation: keep the microphone's audio, run Silero on it, feed its speaker gate. */
    private fun keepMic(buf: ShortArray, n: Int) {
        synchronized(pcmLock) {
            var newSize = max(micPcm.size, SAMPLE_RATE * 30)
            while (micPcmLen + n > newSize) newSize *= 2
            if (newSize != micPcm.size) micPcm = micPcm.copyOf(newSize)
            buf.copyInto(micPcm, micPcmLen, 0, n)
            micPcmLen += n
        }
        val v = micVad ?: return
        val gate = micGate
        var off = 0
        while (off < n) {
            val take = min(SileroVad.CHUNK - micVadTailLen, n - off)
            for (i in 0 until take) micVadTail[micVadTailLen + i] = buf[off + i] / 32768f
            micVadTailLen += take
            off += take
            if (micVadTailLen == SileroVad.CHUNK) {
                micVadTailLen = 0
                val prob = try { v.process(micVadTail) } catch (_: Exception) { 0f }
                micSpeech = prob >= SPEECH_START_PROB
                gate?.onVadFrame(prob, micPcmLen - (n - off))
            }
        }
    }

    /** [StreamingWhisper]용: 화자 게이트가 켜져 있으면 확정된 비타겟 세그먼트를 0으로 마스킹해 반환한다. */
    fun gatedCopy(from: Int, to: Int): ShortArray {
        val raw = rawCopy(from, to)
        return speakerGateSession?.maskStreamingChunk(from, to, raw) ?: raw
    }

    /**
     * Silero's verdict on each 512-sample chunk of a spoken take, in order from sample 0 (of the
     * isolated audio when voice isolation is on), or null in quiet mode, enrollment or without
     * the VAD model. [StreamingWhisper] falls back on these where loudness finds no pause: in a
     * moving car a take was never cut (100s in one piece, phone log 2026-11-24).
     */
    fun speechFlags(): BooleanArray? = synchronized(pcmLock) {
        if (!keepSpeechFlags) null else speechFlagBuf.copyOf(speechFlagLen)
    }

    private fun noteSpeechFlag(prob: Float) {
        if (!keepSpeechFlags) return
        synchronized(pcmLock) {
            if (speechFlagLen == speechFlagBuf.size) speechFlagBuf = speechFlagBuf.copyOf(speechFlagLen * 2)
            speechFlagBuf[speechFlagLen++] = prob >= SPEECH_END_PROB
        }
    }

    /**
     * Voice isolation is on and has passed no speech for a while although the microphone has
     * sound ([IsolationWatch]). The words spoken meanwhile are not in the take.
     */
    val voiceUnheard: Boolean get() = recording && isolationWatch?.unheard == true

    /** Current peak amplitude (0..32767) for waveform visualization. */
    fun amplitude(): Int = lastPeak

    /**
     * Start capturing. If [vadAutoStop] and the Silero model loads, [onAutoStop] is
     * invoked (on the main thread) once the speaker has paused, and silence is
     * trimmed from the result.
     *
     * [quiet] (Quiet mode, for whispering): Silero is not used at all. Auto-stop goes by
     * loudness over the room ([QuietSpeechDetector]) and nothing is trimmed, so whispered
     * words at either end are kept.
     */
    @SuppressLint("MissingPermission")
    fun start(
        vadAutoStop: Boolean = false,
        hangoverSeconds: Double = DEFAULT_HANGOVER_S,
        quiet: Boolean = false,
        speakerGate: Boolean = false,
        voiceIsolation: Boolean = false,
        rawCaptureOnly: Boolean = false,
        onAutoStop: (() -> Unit)? = null,
    ) {
        if (recording) return
        hangoverSamples = (hangoverSeconds.coerceIn(0.5, 10.0) * SAMPLE_RATE).toInt()
        chunks.clear()
        synchronized(pcmLock) { pcmLen = 0; speechFlagLen = 0 }
        keepSpeechFlags = false
        lastPeak = 0
        micDeliveredNonZero = false
        totalSamples = 0
        speechStarted = false
        firstSpeechSample = 0
        lastSpeechSample = 0
        autoStopFired = false
        micSq = 0.0; micPeak = 0; micN = 0; isoSq = 0.0; isoPeak = 0; isoN = 0
        voxMaxMs = 0
        speechSamples = 0
        vadTailLen = 0
        rawCaptureOnlyMode = rawCaptureOnly
        gateRejection = null
        isolationFellBack = false
        synchronized(pcmLock) { micPcm = ShortArray(0); micPcmLen = 0 }
        micVadTailLen = 0
        micSpeech = false

        // rawCaptureOnly (voice enrollment) may still ask for the silence auto-stop; the audio is
        // returned untrimmed either way.
        val useVad = vadAutoStop && !quiet
        val isolating = voiceIsolation && !quiet && !rawCaptureOnly
        // A spoken take runs Silero even with auto-stop off, for [speechFlags].
        vad = if (useVad || (!quiet && !rawCaptureOnly)) {
            SileroVad.shared(context)?.also { it.reset() }
        } else null
        quietDetector = if (vadAutoStop && quiet && !rawCaptureOnly) {
            QuietSpeechDetector(hangoverSamples, MIN_SPEECH_SAMPLES)
        } else null

        voxLockStream = if (isolating) {
            VoiceLockManager.openVoxLockStream(context)
        } else null
        keepSpeechFlags = vad != null && !quiet && !rawCaptureOnly
        val watching = voxLockStream != null && vad != null
        isolationWatch = if (watching) IsolationWatch() else null
        micSound = if (watching) QuietSpeechDetector(Int.MAX_VALUE, Int.MAX_VALUE) else null
        levelSteps.clear()
        stepMicSq = 0.0; stepMicN = 0; stepIsoSq = 0.0; stepIsoN = 0

        speakerGateSession = if (speakerGate && voxLockStream == null && !quiet && !rawCaptureOnly && vad != null) {
            openGate(vadAutoStop) { start, end -> rawCopy(start, end) }
        } else null
        // Isolating: the same gate over the microphone, so falling back on it keeps the TV out.
        // Its own Silero: the shared one is reading the isolated audio.
        micVad = if (watching) secondVad(context)?.also { it.reset() } else null
        micGate = if (speakerGate && micVad != null) openGate(vadAutoStop = false) { start, end -> micCopy(start, end) } else null

        vadActive = (vad != null && vadAutoStop) || quietDetector != null

        val frame = SileroVad.CHUNK // 512 samples; small reads → responsive VAD
        // 2초(64KB) 링 버퍼를 확보해 ONNX 추론 중 커널 오디오 버퍼 오버런을 막는다.
        val minBuf = max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            max(frame * 8, SAMPLE_RATE * 2 * 2),
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            voxLockStream?.close()
            voxLockStream = null
            throw IllegalStateException("Couldn't initialize the microphone.")
        }

        // Android 10+: 사용자 정면 방향 지향성 힌트를 best-effort 적용 (속삭임 모드 제외).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !quiet && !rawCaptureOnly) {
            runCatching {
                rec.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
                rec.setPreferredMicrophoneFieldDimension(0.7f)
            }.onFailure {
                Log.d("AudioRecorder", "microphone direction not applied: ${it.message}")
            }
        }

        record = rec
        recording = true
        rec.startRecording()
        startedAtMs = android.os.SystemClock.elapsedRealtime()
        worker = Thread {
            val buf = ShortArray(frame)
            val vadInst = vad
            val quietInst = quietDetector
            val vox = voxLockStream
            val gate = speakerGateSession
            while (recording) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue

                if (!micDeliveredNonZero) {
                    for (i in 0 until n) {
                        if (buf[i].toInt() != 0) {
                            micDeliveredNonZero = true
                            break
                        }
                    }
                }

                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    micSq += v.toDouble() * v
                    if (abs(v) > micPeak) micPeak = abs(v)
                }
                micN += n

                if (vox != null) {
                    if (isolationWatch != null) keepMic(buf, n)
                    micSound?.let { d ->
                        d.feedPcm(buf, n, micN.toInt())
                        // Loudness over the room, or Silero on the microphone: in road noise the
                        // voice may not clear the room's floor by the detector's margin.
                        isolationWatch?.mic(d.lastFrameSpeech || micSpeech, n)
                    }
                    for (i in 0 until n) { val v = buf[i].toDouble(); stepMicSq += v * v }
                    stepMicN += n
                    val floats = FloatArray(n) { buf[it] / 32768f }
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val isolated = vox.push(floats)
                    voxMaxMs = max(voxMaxMs, android.os.SystemClock.elapsedRealtime() - t0)
                    if (isolated.isNotEmpty()) {
                        val isoPcm = floatsToPcm16(isolated)
                        for (v in isoPcm) {
                            isoSq += v.toDouble() * v
                            if (abs(v.toInt()) > isoPeak) isoPeak = abs(v.toInt())
                        }
                        isoN += isoPcm.size
                        for (v in isoPcm) stepIsoSq += v.toDouble() * v
                        stepIsoN += isoPcm.size
                        if (stepMicN >= 5 * SAMPLE_RATE && levelSteps.size < 60) {
                            fun db(sq: Double, n: Int) = if (n == 0) -99 else (10 * kotlin.math.log10(maxOf(sq / n, 1e-3) / (32768.0 * 32768.0))).toInt()
                            levelSteps.add("${db(stepMicSq, stepMicN)}/${db(stepIsoSq, stepIsoN)}")
                            stepMicSq = 0.0; stepMicN = 0; stepIsoSq = 0.0; stepIsoN = 0
                        }
                        emitProcessedChunk(isoPcm, isoPcm.size, vadInst, quietInst = null, gate = null, vadAutoStop = vadAutoStop, onAutoStop = onAutoStop)
                    }
                } else {
                    emitProcessedChunk(buf, n, vadInst, quietInst, gate, vadAutoStop, onAutoStop)
                }
            }
        }.also { it.start() }
    }

    private fun emitProcessedChunk(
        buf: ShortArray,
        n: Int,
        vadInst: SileroVad?,
        quietInst: QuietSpeechDetector?,
        gate: SpeakerGateSession?,
        vadAutoStop: Boolean,
        onAutoStop: (() -> Unit)?,
    ) {
        var peak = 0
        for (i in 0 until n) {
            val a = abs(buf[i].toInt())
            if (a > peak) peak = a
        }
        lastPeak = peak
        synchronized(chunks) { chunks.add(buf.copyOf(n)) }
        synchronized(pcmLock) {
            var newSize = pcm.size
            while (pcmLen + n > newSize) newSize *= 2
            if (newSize != pcm.size) pcm = pcm.copyOf(newSize)
            buf.copyInto(pcm, pcmLen, 0, n)
            pcmLen += n
        }
        totalSamples += n

        if (quietInst != null && !autoStopFired) {
            if (quietInst.feedPcm(buf, n, totalSamples)) {
                autoStopFired = true
                onAutoStop?.let { cb -> mainHandler.post { cb() } }
            }
        }
        if (vadInst != null) {
            var off = 0
            if (vadTailLen == 0 && n == SileroVad.CHUNK) {
                val f = FloatArray(SileroVad.CHUNK) { buf[it] / 32768f }
                val prob = try { vadInst.process(f) } catch (_: Exception) { 0f }
                handleVadProb(prob, totalSamples, gate, vadAutoStop, onAutoStop)
            } else {
                while (off < n) {
                    val take = min(SileroVad.CHUNK - vadTailLen, n - off)
                    for (i in 0 until take) {
                        vadTail[vadTailLen + i] = buf[off + i] / 32768f
                    }
                    vadTailLen += take
                    off += take
                    if (vadTailLen == SileroVad.CHUNK) {
                        vadTailLen = 0
                        val chunkEnd = totalSamples - (n - off)
                        val prob = try { vadInst.process(vadTail) } catch (_: Exception) { 0f }
                        handleVadProb(prob, chunkEnd, gate, vadAutoStop, onAutoStop)
                    }
                }
            }
        }
    }

    private fun handleVadProb(
        prob: Float,
        endSample: Int,
        gate: SpeakerGateSession?,
        vadAutoStop: Boolean,
        onAutoStop: (() -> Unit)?,
    ) {
        noteSpeechFlag(prob)
        isolationWatch?.isolated(prob >= SPEECH_END_PROB)
        if (gate != null) {
            if (gate.onVadFrame(prob, endSample) && !autoStopFired && vadAutoStop) {
                autoStopFired = true
                onAutoStop?.let { cb -> mainHandler.post { cb() } }
            }
            return
        }
        if (vadAutoStop) updateVad(prob, endSample, onAutoStop)
    }

    /** Speech-end detection: fire [onAutoStop] once the speaker pauses. */
    private fun updateVad(prob: Float, endSample: Int, onAutoStop: (() -> Unit)?) {
        if (prob >= SPEECH_START_PROB) {
            if (!speechStarted) {
                speechStarted = true
                firstSpeechSample = max(0, endSample - SileroVad.CHUNK - PRE_PAD_SAMPLES)
            }
            lastSpeechSample = endSample
            speechSamples += SileroVad.CHUNK
        }
        // speechSamples, not speechStarted: a single stray frame over the threshold must not be
        // enough to arm the auto-stop. Trimming still keys off speechStarted, so a short blip
        // that never arms this can't lose the audio around it either.
        if (speechSamples >= MIN_SPEECH_SAMPLES && !autoStopFired && prob < SPEECH_END_PROB) {
            if (endSample - lastSpeechSample >= hangoverSamples) {
                autoStopFired = true
                onAutoStop?.let { cb -> mainHandler.post { cb() } }
            }
        }
    }

    /** Stop and return all captured samples (null if nothing usable was recorded). */
    fun stop(): ShortArray? {
        if (!recording && record == null) return null
        recording = false
        try { worker?.join(500) } catch (_: InterruptedException) {}
        worker = null
        record?.let { r ->
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        record = null
        // Why a whispered take did or did not end by itself, readable from a copied debug log.
        quietDetector?.let {
            Log.i("AudioRecorder", "quiet take: %.1fs ended=%s wait=%.1fs %s".format(
                totalSamples / SAMPLE_RATE.toDouble(), if (autoStopFired) "auto" else "tap",
                hangoverSamples / SAMPLE_RATE.toDouble(), it.summary(totalSamples)))
        }

        // A spoken take that ended by itself mid-sentence: which rule ended it (2026-11-25). The
        // plain rule counts from the last frame Silero was sure of; the gate from the last window
        // it took for the enrolled voice.
        if (quietDetector == null && vadActive) {
            val gate = speakerGateSession
            Log.i("AudioRecorder", "spoken take: %.1fs ended=%s wait=%.1fs %s".format(
                totalSamples / SAMPLE_RATE.toDouble(), if (autoStopFired) "auto" else "tap",
                hangoverSamples / SAMPLE_RATE.toDouble(),
                gate?.summary() ?: "no gate; %.1fs since sure speech".format((totalSamples - lastSpeechSample) / SAMPLE_RATE.toDouble())))
        }

        // Was the microphone itself quiet, or did voice isolation take the voice out? Speaking
        // with a flat waveform and nothing recognized looks the same from outside (2026-11-23).
        if (micN > 0) {
            fun db(sq: Double, n: Long) = if (n == 0L) -99 else (10 * kotlin.math.log10(maxOf(sq / n, 1e-3) / (32768.0 * 32768.0))).toInt()
            fun peakDb(p: Int) = (20 * kotlin.math.log10(maxOf(p, 1) / 32768.0)).toInt()
            Log.i("AudioRecorder", "take level: %.1fs mic rms=%ddB peak=%ddB".format(micN / SAMPLE_RATE.toDouble(), db(micSq, micN), peakDb(micPeak)) +
                if (voxLockStream != null) " isolated rms=${db(isoSq, isoN)}dB peak=${peakDb(isoPeak)}dB, slowest isolation run ${voxMaxMs}ms, clock %.1fs".format((android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000.0) else "")
        }

        isolationWatch?.let {
            Log.i("AudioRecorder", "isolation: %s; mic/isolated dB per 5s: %s".format(it.summary(), levelSteps.joinToString(" ")))
        }
        val vox = voxLockStream
        voxLockStream = null
        if (vox != null) {
            val flushed = runCatching { vox.flush() }.getOrDefault(FloatArray(0))
            if (flushed.isNotEmpty()) {
                val tailPcm = floatsToPcm16(flushed)
                synchronized(chunks) { chunks.add(tailPcm) }
                synchronized(pcmLock) {
                    var newSize = pcm.size
                    while (pcmLen + tailPcm.size > newSize) newSize *= 2
                    if (newSize != pcm.size) pcm = pcm.copyOf(newSize)
                    tailPcm.copyInto(pcm, pcmLen)
                    pcmLen += tailPcm.size
                }
                totalSamples += tailPcm.size
                // 남은 tail에 대해서도 VAD 끝점 보정
                vad?.let { vadInst ->
                    var off = 0
                    while (off < tailPcm.size) {
                        val take = min(SileroVad.CHUNK - vadTailLen, tailPcm.size - off)
                        for (i in 0 until take) vadTail[vadTailLen + i] = tailPcm[off + i] / 32768f
                        vadTailLen += take
                        off += take
                        if (vadTailLen == SileroVad.CHUNK) {
                            vadTailLen = 0
                            val prob = try { vadInst.process(vadTail) } catch (_: Exception) { 0f }
                            noteSpeechFlag(prob)
                            // Only a take that trims by auto-stop: with it off, speech first
                            // seen here would trim the take down to this tail.
                            if (vadActive && prob >= SPEECH_START_PROB) {
                                if (!speechStarted) {
                                    speechStarted = true
                                    firstSpeechSample = max(0, totalSamples - tailPcm.size + off - SileroVad.CHUNK - PRE_PAD_SAMPLES)
                                }
                                lastSpeechSample = totalSamples - (tailPcm.size - off)
                                speechSamples += SileroVad.CHUNK
                            }
                        }
                    }
                }
            } else {
                vox.close()
            }
        }

        val all = synchronized(chunks) { chunks.toList() }
        chunks.clear()
        val total = all.sumOf { it.size }
        val gate = speakerGateSession
        speakerGateSession = null
        val mGate = micGate
        micGate = null

        // Voice isolation lost the voice: the microphone's audio rather than words gone for good.
        // In a moving car isolation took a normal voice out from the start (2026-11-25). Only
        // through the speaker gate: the raw microphone would type the TV or a passenger, and the
        // whole take falls back even when only a pause went unheard.
        if (isolationWatch?.lostVoice() == true) {
            val mic = synchronized(pcmLock) { micPcm.copyOf(micPcmLen) }
            if (mGate == null) {
                Log.i("AudioRecorder", "isolation lost the voice; no speaker gate, so the isolated audio stays")
            } else if (mic.size >= SAMPLE_RATE / 4) {
                isolationFellBack = true
                Log.i("AudioRecorder", "isolation lost the voice: transcribing the microphone through the speaker gate")
                return gated(mGate, mic)
            }
        }
        mGate?.finishAndApply(ShortArray(0))

        if (total < SAMPLE_RATE / 4) {
            gate?.finishAndApply(ShortArray(0))
            return null // < ~0.25s: nothing meaningful
        }
        val full = ShortArray(total)
        var off = 0
        for (c in all) { c.copyInto(full, off); off += c.size }

        if (rawCaptureOnlyMode) return full

        if (gate != null) return gated(gate, full)

        // VAD trim: keep just the speech region (with a little padding) — avoids
        // whisper hallucinating on trailing silence.
        if (speechStarted) {
            val start = firstSpeechSample.coerceIn(0, total)
            val end = min(total, lastSpeechSample + POST_PAD_SAMPLES)
            if (end - start >= SAMPLE_RATE / 4) return full.copyOfRange(start, end)
        }
        return full
    }

    /** [full] through the speaker gate; null with [gateRejection] set when none of it was the enrolled voice. */
    private fun gated(gate: SpeakerGateSession, full: ShortArray): ShortArray? {
        val res = gate.finishAndApply(full)
        if (res.samples.size >= SAMPLE_RATE / 4) return res.samples
        res.rejected?.takeIf { it.size >= SAMPLE_RATE / 4 }?.let { speech ->
            gateRejection = GateRejection(speech, res.bestSimilarity, res.windows)
            Log.i(
                "SpeakerGate",
                "no speech matched the enrolled voice: best similarity %.2f (keep >= %.2f, or %.2f alone) over %d windows, %.1fs of speech"
                    .format(res.bestSimilarity, SpeakerGate.SIM_THRESHOLD, SpeakerGate.FALLBACK_SIM, res.windows, speech.size / SAMPLE_RATE.toFloat()),
            )
        }
        return null
    }

    fun cancel() {
        recording = false
        try { worker?.join(500) } catch (_: InterruptedException) {}
        worker = null
        record?.let { r ->
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        record = null
        voxLockStream?.close()
        voxLockStream = null
        speakerGateSession?.finishAndApply(ShortArray(0))
        speakerGateSession = null
        micGate?.finishAndApply(ShortArray(0))
        micGate = null
        synchronized(chunks) { chunks.clear() }
    }

    private fun floatsToPcm16(floats: FloatArray): ShortArray = ShortArray(floats.size) { i ->
        (floats[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
    }
}
