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
    /**
     * 녹음 도중 VoxLock이 목소리를 잃어 원음+화자 게이트로 전환되면 증가한다.
     * [StreamingWhisper]가 이 값의 변화를 감지해 기존 분리 출력 조각을 비우고 원음 0번 샘플부터 재스캔한다.
     */
    @Volatile var streamGeneration = 0
        private set
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

    /**
     * How long the microphone has been open. Not since [start] was called: start loads the
     * models first, and on a cold voice-isolation take that took 0.6s of a 0.7s silent-mic probe.
     */
    val openForMs: Long
        get() = if (recording && startedAtMs > 0) android.os.SystemClock.elapsedRealtime() - startedAtMs else 0L
    private var speechSamples = 0
    private var quietDetector: QuietSpeechDetector? = null
    private var speakerGateSession: SpeakerGateSession? = null
    private var voxLockStream: VoxLockEngine.Stream? = null
    // Voice isolation only: 원음과 원음 기준 CAM++ 화자 게이트.
    // 분리 출력은 음색 변형으로 CAM++ 점수가 떨어질 수 있어(2026-11-28 로그: 원음 0.54 → 분리 0.15),
    // 2차 마스킹·자동 중지·원음 폴백을 모두 원음 기준 [micGate] 하나로 처리한다. Guarded by pcmLock.
    private var micPcm = ShortArray(0)
    private var micPcmLen = 0
    private var micSpeechFlagBuf = BooleanArray(2048)
    private var micSpeechFlagLen = 0
    private var micGate: SpeakerGateSession? = null
    private var micVad: SileroVad? = null
    private val micVadTail = FloatArray(SileroVad.CHUNK)
    private var micVadTailLen = 0
    private var micSpeech = false // Silero's verdict on the latest microphone chunk
    @Volatile private var switchedToMic = false

    /**
     * Set by [stop]: 녹음 종료 시점에 뒤늦게 원음 폴백([IsolationWatch.lostVoice])이 발동해
     * 말하는 동안 분리 출력으로 변환해 둔 [StreamingWhisper] 조각을 버리고 원음을 통째로 변환해야 함을 뜻한다.
     * 녹음 도중 원음 스트리밍으로 이미 전환([switchedToMic])된 경우는 false(스트리밍 결과 그대로 사용).
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
        micGate?.let { if (isolationWatch?.unheard == true || speechSamples < MIN_SPEECH_SAMPLES) return it.silenceProgress() }
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
    private fun openGate(vadAutoStop: Boolean, label: String, audio: (Int, Int) -> ShortArray): SpeakerGateSession? {
        val embedder = VoiceLockManager.acquireEmbedder(context) ?: return null
        val enrolled = VoiceLockManager.getEnrolledEmbedding(context) ?: return null
        return SpeakerGateSession(
            scorer = { start, len -> embedder.scoreSimilarity(enrolled, WavIo.toFloats(audio(start, start + len))) },
            vadAutoStop = vadAutoStop,
            hangoverSamples = hangoverSamples,
            label = label,
        )
    }

    /** Recorder thread, voice isolation: keep the microphone's audio, run Silero on it, feed its speaker gate. */
    private fun keepMic(buf: ShortArray, n: Int, vadAutoStop: Boolean, onAutoStop: (() -> Unit)?) {
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
                synchronized(pcmLock) {
                    if (micSpeechFlagLen == micSpeechFlagBuf.size) micSpeechFlagBuf = micSpeechFlagBuf.copyOf(micSpeechFlagLen * 2)
                    micSpeechFlagBuf[micSpeechFlagLen++] = prob >= SPEECH_END_PROB
                }
                if (gate != null && gate.onVadFrame(prob, micPcmLen - (n - off)) && !autoStopFired && vadAutoStop) {
                    autoStopFired = true
                    onAutoStop?.let { cb -> mainHandler.post { cb() } }
                }
            }
        }
    }

    /** [StreamingWhisper]용: 화자 게이트가 켜져 있으면 확정된 비타겟 세그먼트를 0으로 마스킹해 반환한다. */
    fun gatedCopy(from: Int, to: Int): ShortArray {
        val raw = rawCopy(from, to)
        val gate = speakerGateSession ?: micGate
        return gate?.maskStreamingChunk(from, to, raw) ?: raw
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
     * 화자 게이트 폴백 없이 음성 분리만 켜진 상태에서 목소리가 넘어오지 않을 때 true.
     * 화자 게이트([micGate])가 있으면 원음+게이트로 자동 전환되므로 불안한 "No voice" 경고를 띄우지 않는다.
     */
    val voiceUnheard: Boolean get() = recording && !switchedToMic && micGate == null && isolationWatch?.unheard == true

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
        startedAtMs = 0L
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
        switchedToMic = false
        streamGeneration = 0
        synchronized(pcmLock) { micPcm = ShortArray(0); micPcmLen = 0; micSpeechFlagLen = 0 }
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

        // 일반 모드에서는 원음에 직접 speakerGateSession을 돌린다.
        // 내 목소리만(VoxLock) 모드에서는 분리 출력의 음색 변형으로 CAM++ 점수가 흔들리지 않도록
        // 원음 쪽 micGate 하나만 열어 2차 마스킹·자동 중지·원음 자동 전환을 모두 담당한다.
        speakerGateSession = if (speakerGate && voxLockStream == null && !quiet && !rawCaptureOnly && vad != null) {
            openGate(vadAutoStop, "mic") { start, end -> rawCopy(start, end) }
        } else null
        micVad = if (watching) secondVad(context)?.also { it.reset() } else null
        micGate = if (speakerGate && micVad != null) {
            openGate(vadAutoStop = vadAutoStop, label = "mic-gate") { start, end ->
                if (switchedToMic) rawCopy(start, end) else micCopy(start, end)
            }
        } else null

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
            var vadInst = vad
            val quietInst = quietDetector
            var vox = voxLockStream
            var gate = speakerGateSession
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
                    val watch = isolationWatch
                    if (watch != null) keepMic(buf, n, vadAutoStop, onAutoStop)
                    micSound?.let { d ->
                        d.feedPcm(buf, n, micN.toInt())
                        // Loudness over the room, or Silero on the microphone: in road noise the
                        // voice may not clear the room's floor by the detector's margin.
                        watch?.mic(d.lastFrameSpeech || micSpeech, n)
                    }
                    // VoxLock이 내 목소리를 지워 unheard가 누적됐거나, 말하는 도중 분리 출력만 2초 이상
                    // 무음이 됐는데 원음 CAM++는 내 목소리가 계속 이어지고 있음을 확인한 경우
                    // (2026-11-28 15:58 로그: -26/-41dB로 급감하며 2.2s 자동 중지가 먼저 끊어 끝 2.3s 유실),
                    // 종료를 기다리지 않고 즉시 원음 + 화자 게이트 스트리밍으로 전환한다.
                    val mGate = micGate
                    val isoDroppedMidSpeech = speechStarted &&
                        totalSamples - lastSpeechSample >= 2 * SAMPLE_RATE &&
                        mGate?.isTargetActiveNow(micPcmLen) == true
                    if (watch != null && mGate != null &&
                        (isoDroppedMidSpeech || (watch.unheardSeconds >= IsolationWatch.LOST_UNHEARD_S && mGate.hasTargetVoice()))
                    ) {
                        vox.close()
                        voxLockStream = null
                        vox = null
                        switchedToMic = true
                        synchronized(pcmLock) {
                            pcm = micPcm.copyOf(max(micPcmLen, SAMPLE_RATE * 30))
                            pcmLen = micPcmLen
                            speechFlagBuf = micSpeechFlagBuf.copyOf(max(micSpeechFlagLen, 2048))
                            speechFlagLen = micSpeechFlagLen
                        }
                        synchronized(chunks) {
                            chunks.clear()
                            if (micPcmLen > 0) chunks.add(micPcm.copyOf(micPcmLen))
                        }
                        totalSamples = micPcmLen
                        vadInst = micVad
                        vad = micVad
                        micVad = null
                        micVadTail.copyInto(vadTail)
                        vadTailLen = micVadTailLen
                        gate = mGate
                        speakerGateSession = mGate
                        micGate = null
                        streamGeneration++
                        var peak = 0
                        for (i in 0 until n) { val a = abs(buf[i].toInt()); if (a > peak) peak = a }
                        lastPeak = peak
                        Log.i("AudioRecorder", "isolation switched to microphone + speaker gate at %.1fs (%s)".format(
                            micPcmLen / SAMPLE_RATE.toDouble(), watch.summary()))
                        continue
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
                // 내 목소리만 모드에서 VoxLock이 말 도중 내 목소리를 지워 분리 출력만 무음이 됐는데
                // 원음 게이트에서는 본인 목소리가 계속 이어지고 있다면 여기서 녹음을 끊지 않는다.
                if (micGate?.isTargetActiveNow(micPcmLen) == true) return
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
            val gate = speakerGateSession ?: micGate
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
            val isoNote = when {
                voxLockStream != null -> " isolated rms=${db(isoSq, isoN)}dB peak=${peakDb(isoPeak)}dB, slowest isolation run ${voxMaxMs}ms, clock %.1fs".format((android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000.0)
                switchedToMic -> " switched-to-mic (before switch: isolated rms=${db(isoSq, isoN)}dB peak=${peakDb(isoPeak)}dB, slowest ${voxMaxMs}ms), clock %.1fs".format((android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000.0)
                else -> ""
            }
            Log.i("AudioRecorder", "take level: %.1fs mic rms=%ddB peak=%ddB".format(micN / SAMPLE_RATE.toDouble(), db(micSq, micN), peakDb(micPeak)) + isoNote)
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
                            val chunkEnd = totalSamples - (tailPcm.size - off)
                            speakerGateSession?.onVadFrame(prob, chunkEnd)
                            // Only a take that trims by auto-stop: with it off, speech first
                            // seen here would trim the take down to this tail.
                            if (vadActive && prob >= SPEECH_START_PROB) {
                                if (!speechStarted) {
                                    speechStarted = true
                                    firstSpeechSample = max(0, chunkEnd - SileroVad.CHUNK - PRE_PAD_SAMPLES)
                                }
                                lastSpeechSample = chunkEnd
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

        // 녹음 중 원음 전환이 안 일어났더라도 종료 시점에 목소리를 잃은 것으로 판정되면 원음+게이트로 폴백한다.
        if (!switchedToMic && isolationWatch?.lostVoice() == true) {
            val mic = synchronized(pcmLock) { micPcm.copyOf(micPcmLen) }
            if (mGate == null) {
                Log.i("AudioRecorder", "isolation lost the voice; no speaker gate, so the isolated audio stays")
            } else if (mic.size >= SAMPLE_RATE / 4) {
                gate?.discard()
                isolationFellBack = true
                Log.i("AudioRecorder", "isolation lost the voice: transcribing the microphone through the speaker gate")
                return gated(mGate, mic, updateStreamingPcm = false)
            }
        }

        if (total < SAMPLE_RATE / 4) {
            gate?.discard()
            mGate?.discard()
            return null // < ~0.25s: nothing meaningful
        }
        val full = ShortArray(total)
        var off = 0
        for (c in all) { c.copyInto(full, off); off += c.size }

        if (rawCaptureOnlyMode) {
            gate?.discard()
            mGate?.discard()
            return full
        }

        // 일반 모드(또는 녹음 중 원음 전환됨): speakerGateSession으로 마스킹.
        // 내 목소리만 정상 통과 시: 원음 기준 mGate로 분리 출력의 타인 단독 구간·꼬리만 2차 마스킹.
        val activeGate = gate ?: mGate
        if (activeGate != null) return gated(activeGate, full, updateStreamingPcm = true)

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
    private fun gated(gate: SpeakerGateSession, full: ShortArray, updateStreamingPcm: Boolean): ShortArray? {
        val res = gate.finishAndApply(full)
        if (res.samples.size >= SAMPLE_RATE / 4) {
            if (updateStreamingPcm) {
                // stop() 직후 StreamingWhisper.finish()가 남은 조각을 gatedCopy()로 읽을 때
                // 최종 확정된 비타겟 마스킹 결과를 보게 한다(speakerGateSession은 이미 null).
                synchronized(pcmLock) {
                    val n = min(res.maskedFull.size, pcmLen)
                    if (n > 0) res.maskedFull.copyInto(pcm, 0, 0, n)
                }
            }
            return res.samples
        }
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
        speakerGateSession?.discard()
        speakerGateSession = null
        micGate?.discard()
        micGate = null
        synchronized(chunks) { chunks.clear() }
    }

    private fun floatsToPcm16(floats: FloatArray): ShortArray = ShortArray(floats.size) { i ->
        (floats[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
    }
}
