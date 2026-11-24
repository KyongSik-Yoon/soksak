package com.voicerewriter

/**
 * Notices a voice-isolated take ("내 목소리만") that has stopped passing the speaker's voice.
 *
 * Voice isolation keeps the enrolled voice and silences the rest, and a voice it does not
 * recognise goes with the rest: a 31.6s take came back as its first 8s, the other 23s silent,
 * with nothing on screen but a flat waveform (phone log, 2026-11-24; a soft or whispered voice,
 * which quiet mode took whole a minute later). This cannot tell whose sound the microphone has —
 * that is the isolator's job — so it only says what is true: there is sound, and none of it is
 * coming through as speech. Someone else talking while the user is silent reads the same.
 *
 * Fed from the recorder thread; [unheard] may be read from any thread.
 */
class IsolationWatch {
    companion object {
        private const val SR = AudioRecorder.SAMPLE_RATE
        /** Isolated audio with no speech for this long, */
        const val SILENT_S = 2.5
        /** while the microphone had sound for at least this much of that time. Neither is measured. */
        const val SOUND_S = 1.2
    }

    // Microphone frames of the last SILENT_S: length and whether there was sound, oldest first.
    private val frames = ArrayDeque<Pair<Int, Boolean>>()
    private var windowSamples = 0
    private var soundSamples = 0
    private var micSamples = 0L
    private var lastSpeechAt = 0L
    private var unheardSamples = 0L

    @Volatile var unheard = false
        private set

    /** Seconds of the take spent [unheard], for the log. */
    val unheardSeconds: Double get() = unheardSamples / SR.toDouble()

    /** A microphone frame of [n] samples, before isolation: was there sound over the room's floor? */
    fun mic(sound: Boolean, n: Int) {
        micSamples += n
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

    /** The VAD's verdict on a chunk of the isolated audio. */
    fun isolated(speech: Boolean) {
        if (!speech) return
        lastSpeechAt = micSamples
        unheard = false
    }
}
