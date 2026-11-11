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
 *
 * Three things real takes needed (20 whispered takes from the phone, 2026-11-11, on which the
 * plain-loudness version never fired once):
 * - Only 500-2500 Hz is measured ([feedPcm]). Room rumble under 500 Hz was 15-20 dB louder
 *   than the whisper and moved by more than the whisper added, so full-band loudness could
 *   not tell them apart; in this band the room sat steady and whispers stood 6-16 dB over it.
 * - The first [STARTUP_SAMPLES] are ignored. The microphone opens with a zero frame and a
 *   quarter second of near-silence; seeding the floor from that put it ~40 dB under the room,
 *   and with the slow creep every later frame counted as speech.
 * - Speech needs [MIN_RUN_SAMPLES] in a row, so a click does not restart the wait.
 */
class QuietSpeechDetector(private val hangoverSamples: Int, private val minSpeechSamples: Int) {
    companion object {
        const val SPEECH_OVER_FLOOR = 2.5f
        /** Below this RMS (~-70 dBFS) nothing is speech, however quiet the room is. */
        const val ABS_MIN_SPEECH = 3e-4f
        private const val FLOOR_MIN = 1e-5f
        private const val FRAME = 320
        /** Microphone start-up: not room, not speech. */
        const val STARTUP_SAMPLES = (0.3 * 16_000).toInt()
        /** A sound shorter than this (~0.1s) is a click, not a word. */
        const val MIN_RUN_SAMPLES = 1500

        // 2nd-order Butterworth sections at 16 kHz: high-pass 500 Hz, low-pass 2500 Hz.
        private val HP = biquad(highPass = true, hz = 500.0)
        private val LP = biquad(highPass = false, hz = 2500.0)

        /** RBJ cookbook biquad, Q = 1/sqrt(2); returns b0, b1, b2, a1, a2 (a0 = 1). */
        private fun biquad(highPass: Boolean, hz: Double): DoubleArray {
            val w = 2 * Math.PI * hz / 16_000
            val alpha = kotlin.math.sin(w) / (2 * 0.70710678)
            val c = kotlin.math.cos(w)
            val b = if (highPass) doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2) else doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2)
            val a0 = 1 + alpha
            return doubleArrayOf(b[0] / a0, b[1] / a0, b[2] / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        /**
         * Whether [samples] hold at least [minRunSamples] of voice in a row by this detector's
         * loudness rule. Quiet mode's stand-in for Silero when deciding if a window was silent,
         * since Silero does not hear whispers.
         */
        fun hasSpeech(samples: FloatArray, minRunSamples: Int = 16_000 / 10): Boolean {
            val d = QuietSpeechDetector(Int.MAX_VALUE, Int.MAX_VALUE)
            var run = 0
            var i = 0
            while (i + FRAME <= samples.size) {
                var sum = 0.0
                for (j in i until i + FRAME) { val v = d.band(samples[j].toDouble()); sum += v * v }
                d.feed(kotlin.math.sqrt(sum / FRAME).toFloat(), i + FRAME, FRAME)
                run = if (d.lastFrameSpeech) run + FRAME else 0
                if (run >= minRunSamples) return true
                i += FRAME
            }
            return false
        }
    }

    var floor = -1f
        private set
    private var speechSamples = 0
    private var lastSpeech = 0
    private var runSamples = 0
    private var fired = false
    // Filter state: x[n-1], x[n-2], y[n-1], y[n-2] for each section.
    private val hp = DoubleArray(4)
    private val lp = DoubleArray(4)

    private fun section(c: DoubleArray, z: DoubleArray, x: Double): Double {
        val y = c[0] * x + c[1] * z[0] + c[2] * z[1] - c[3] * z[2] - c[4] * z[3]
        z[1] = z[0]; z[0] = x; z[3] = z[2]; z[2] = y
        return y
    }

    /** One sample (-1..1) through the 500-2500 Hz band. */
    private fun band(x: Double): Double = section(LP, lp, section(HP, hp, x))

    /** [feed] for raw microphone samples: the level is taken in the 500-2500 Hz band. */
    fun feedPcm(buf: ShortArray, n: Int, end: Int): Boolean {
        var sum = 0.0
        for (i in 0 until n) { val v = band(buf[i] / 32768.0); sum += v * v }
        return feed(kotlin.math.sqrt(sum / n).toFloat(), end, n)
    }
    /** Whether the frame last given to [feed] counted as speech. */
    var lastFrameSpeech = false
        private set

    /**
     * Feed one frame's RMS (0..1) whose last sample is [end] (sample index from take start)
     * and that is [n] samples long. True exactly once: when the speaker has gone quiet for the
     * hangover after enough speech.
     */
    fun feed(rms: Float, end: Int, n: Int): Boolean {
        if (end <= STARTUP_SAMPLES) { lastFrameSpeech = false; return false }
        floor = when {
            floor < 0f -> maxOf(rms, FLOOR_MIN)
            rms < floor -> maxOf(floor * 0.7f + rms * 0.3f, FLOOR_MIN)
            else -> floor * 0.999f + rms * 0.001f
        }
        lastFrameSpeech = rms > floor * SPEECH_OVER_FLOOR && rms > ABS_MIN_SPEECH
        runSamples = if (lastFrameSpeech) runSamples + n else 0
        if (runSamples >= MIN_RUN_SAMPLES) {
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
