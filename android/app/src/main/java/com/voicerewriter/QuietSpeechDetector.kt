package com.voicerewriter

/**
 * End-of-speech detection for Quiet mode, by loudness relative to the room instead of by the
 * Silero voice detector.
 *
 * Silero is trained on voiced speech. Whispering (no vocal-fold buzz) can score under its
 * thresholds, which made the normal take auto-stop while the user was still whispering and
 * trim whispered words off the ends. Here a frame counts as speech when it is clearly louder
 * than the room's noise floor (> [SPEECH_OVER_FLOOR]x, ~8 dB), whatever it sounds like.
 *
 * The floor follows the quietest frames quickly and creeps up only slowly (time constant
 * ~30s), so a pause between words pulls it down to the room while continuous whispering
 * can't drag it up to its own level. Pure and per-frame, so it is unit-tested directly.
 */
class QuietSpeechDetector(private val hangoverSamples: Int, private val minSpeechSamples: Int) {
    companion object {
        const val SPEECH_OVER_FLOOR = 2.5f
        /** Below this RMS (~-70 dBFS) nothing is speech, however quiet the room is. */
        const val ABS_MIN_SPEECH = 3e-4f
        private const val FLOOR_MIN = 1e-5f
    }

    var floor = -1f
        private set
    private var speechSamples = 0
    private var lastSpeech = 0
    private var fired = false

    /**
     * Feed one frame's RMS (0..1) whose last sample is [end] (sample index from take start)
     * and that is [n] samples long. True exactly once: when the speaker has gone quiet for the
     * hangover after enough speech.
     */
    fun feed(rms: Float, end: Int, n: Int): Boolean {
        floor = when {
            floor < 0f -> maxOf(rms, FLOOR_MIN)
            rms < floor -> maxOf(floor * 0.7f + rms * 0.3f, FLOOR_MIN)
            else -> floor * 0.999f + rms * 0.001f
        }
        if (rms > floor * SPEECH_OVER_FLOOR && rms > ABS_MIN_SPEECH) {
            speechSamples += n
            lastSpeech = end
        }
        if (!fired && speechSamples >= minSpeechSamples && end - lastSpeech >= hangoverSamples) {
            fired = true
            return true
        }
        return false
    }
}
