package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietSpeechDetectorTest {
    private val n = 320 // 20ms reads
    private fun frames(sec: Double, rms: Float) = List((sec * 16000 / n).toInt()) { rms }

    /** Feeds [levels] and returns the sample index each auto-stop fired at. */
    private fun run(d: QuietSpeechDetector, levels: List<Float>): List<Int> {
        val fired = mutableListOf<Int>()
        var end = 0
        for (rms in levels) {
            end += n
            if (d.feed(rms, end, n)) fired += end
        }
        return fired
    }

    private fun detector() = QuietSpeechDetector(hangoverSamples = (1.6 * 16000).toInt(), minSpeechSamples = (0.4 * 16000).toInt())

    @Test fun whisperWithShortDipsDoesNotStopThenSilenceStopsOnce() {
        val whisper = (frames(0.8, 0.006f) + frames(0.25, 0.0015f)).let { it + it + it + it }
        val levels = frames(1.0, 0.001f) + whisper + frames(3.0, 0.001f)
        val fired = run(detector(), levels)
        assertEquals(1, fired.size)
        // Fires about 1.6s after the last whisper frame, not during the dips.
        val lastSpeechEnd = (frames(1.0, 0f).size + whisper.size - frames(0.25, 0f).size) * n
        assertTrue(fired[0] - lastSpeechEnd in (1.6 * 16000).toInt()..(1.7 * 16000).toInt())
    }

    @Test fun roomNoiseAloneNeverStops() {
        val levels = frames(8.0, 0.001f).mapIndexed { i, v -> if (i % 7 == 0) v * 1.8f else v }
        assertTrue(run(detector(), levels).isEmpty())
    }

    @Test fun digitalSilenceIsNotSpeech() {
        // Below the absolute floor even though it's "louder" than the zero floor.
        val levels = frames(1.0, 0f) + frames(1.0, 2e-4f) + frames(3.0, 0f)
        assertTrue(run(detector(), levels).isEmpty())
    }

    @Test fun floorFollowsQuieterRoomQuickly() {
        val d = detector()
        run(d, frames(0.5, 0.004f) + frames(0.5, 0.001f))
        assertTrue(d.floor < 0.0015f)
    }

    @Test fun forDictationSwapsTheModelOnlyInQuietMode() {
        val s = Settings(sttModel = "turbo-acft-q4", quietModel = "small-acft")
        assertEquals("turbo-acft-q4", s.forDictation().sttModel)
        assertEquals("small-acft", s.copy(quietMode = true).forDictation().sttModel)
        assertEquals("turbo-acft-q4", s.copy(quietMode = true, quietModel = "").forDictation().sttModel)
        assertFalse(s.copy(quietMode = true).forDictation().sttModel == s.sttModel)
    }
}
