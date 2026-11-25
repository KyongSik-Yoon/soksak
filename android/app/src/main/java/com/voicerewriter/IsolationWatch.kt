package com.voicerewriter

/**
 * Notices a voice-isolated take ("내 목소리만") that has stopped passing the speaker's voice.
 *
 * Voice isolation keeps the enrolled voice and silences the rest, and a voice it does not
 * recognise goes with the rest: a 31.6s take came back as its first 8s, the other 23s silent,
 * with nothing on screen but a flat waveform (phone log, 2026-11-24; a soft or whispered voice,
 * which quiet mode took whole a minute later). In a moving car it took a normal voice from the
 * start: 36.6s came back as 7 characters unless the phone was right at the mouth (2026-11-25).
 * This cannot tell whose sound the microphone has — that is the isolator's job — so it only says
 * what is true: there is sound, and none of it is coming through as speech. Someone else talking
 * while the user is silent reads the same.
 *
 * Fed from the recorder thread; [unheard] may be read from any thread.
 */
class IsolationWatch {
    companion object {
        private const val SR = AudioRecorder.SAMPLE_RATE
        private const val CHUNK = SileroVad.CHUNK
        /** Isolated audio with no speech for this long, */
        const val SILENT_S = 2.5
        /** while the microphone had sound for at least this much of that time. Neither is measured. */
        const val SOUND_S = 1.2
        /**
         * Speech in the isolated audio counts only as a run this long (8 Silero chunks). Scraps of
         * the voice leak through isolation, and one 32ms chunk over the threshold used to count as
         * the voice coming back: at home two takes lost 94% and 97% of the voice and "No voice"
         * never came up (2026-11-25, Silero heard 6% and 3% of them).
         */
        const val HEARD_RUN_S = 0.25
        private val HEARD_RUN_CHUNKS = kotlin.math.ceil(HEARD_RUN_S * SR / CHUNK).toInt()

        /** [lostVoice]: this much time spent [unheard], */
        const val LOST_UNHEARD_S = 0.5
        /** or, over a take with at least this much microphone sound, */
        const val LOST_MIN_SOUND_S = 1.0
        /** isolated speech under this share of that sound. Not measured; a take that came through sat near 1. */
        const val LOST_HEARD_SHARE = 0.3
    }

    // Microphone frames of the last SILENT_S: length and whether there was sound, oldest first.
    private val frames = ArrayDeque<Pair<Int, Boolean>>()
    private var windowSamples = 0
    private var soundSamples = 0
    private var micSamples = 0L
    private var lastSpeechAt = 0L
    private var unheardSamples = 0L
    private var micSoundSamples = 0L
    private var heardSamples = 0L
    private var speechRun = 0
    private var longestRun = 0

    @Volatile var unheard = false
        private set

    /** Seconds of the take spent [unheard], for the log. */
    val unheardSeconds: Double get() = unheardSamples / SR.toDouble()

    /** A microphone frame of [n] samples, before isolation: was there sound (over the room's floor, or speech)? */
    fun mic(sound: Boolean, n: Int) {
        micSamples += n
        if (sound) micSoundSamples += n
        frames.addLast(n to sound)
        windowSamples += n
        if (sound) soundSamples += n
        while (windowSamples - frames.first().first >= SILENT_S * SR) {
            val (len, s) = frames.removeFirst()
            windowSamples -= len
            if (s) soundSamples -= len
        }
        unheard = micSamples - lastSpeechAt >= SILENT_S * SR && soundSamples >= SOUND_S * SR
        if (unheard) unheardSamples += n
    }

    /** The VAD's verdict on one Silero chunk of the isolated audio. */
    fun isolated(speech: Boolean) {
        if (!speech) { speechRun = 0; return }
        speechRun++
        if (speechRun > longestRun) longestRun = speechRun
        if (speechRun < HEARD_RUN_CHUNKS) return
        // The run's earlier chunks count from the moment it qualifies.
        heardSamples += if (speechRun == HEARD_RUN_CHUNKS) HEARD_RUN_CHUNKS.toLong() * CHUNK else CHUNK.toLong()
        lastSpeechAt = micSamples
        unheard = false
    }

    /**
     * Whether isolation lost the voice in this take, so the microphone's audio should be
     * transcribed instead: it was [unheard] for a while, or almost none of the sound came through.
     */
    fun lostVoice(): Boolean =
        unheardSamples >= LOST_UNHEARD_S * SR ||
            micSoundSamples >= LOST_MIN_SOUND_S * SR && heardSamples < LOST_HEARD_SHARE * micSoundSamples

    /** For the log line a take leaves. */
    fun summary(): String = "voice unheard for %.1fs, mic sound %.1fs, isolated speech %.1fs (longest run %.2fs)".format(
        unheardSeconds, micSoundSamples / SR.toDouble(), heardSamples / SR.toDouble(), longestRun * CHUNK / SR.toDouble())
}
