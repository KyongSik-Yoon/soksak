package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingWhisperTest {
    private val frame = 320
    private fun frames(sec: Double, v: Float) = List((sec * 16000 / frame).toInt()) { v }

    @Test fun cutsInTheMiddleOfTheFirstLongEnoughPause() {
        // 6s speech, 0.8s pause, 3s speech.
        val e = frames(6.0, 0.1f) + frames(0.8, 0.0f) + frames(3.0, 0.1f)
        val (cut, next) = StreamingWhisper.findCut(e, 0, 0, 0.1f)!!
        val pauseStart = 300 // frames
        // Cut when the pause reaches 0.5s (25 frames), in its middle so far.
        assertEquals((pauseStart + 24 - 25 / 2) * frame, cut)
        assertEquals(pauseStart + 25, next)
    }

    @Test fun noCutBeforeTheMinimumPieceLength() {
        val e = frames(3.0, 0.1f) + frames(0.8, 0.0f) + frames(3.0, 0.1f)
        assertNull(StreamingWhisper.findCut(e, 0, 0, 0.1f))
    }

    @Test fun shortPausesDontCut() {
        val e = frames(6.0, 0.1f) + frames(0.3, 0.0f) + frames(3.0, 0.1f)
        assertNull(StreamingWhisper.findCut(e, 0, 0, 0.1f))
    }

    @Test fun levelIsThe95thPercentile() {
        val e = List(100) { it / 100f }
        assertEquals(0.94f, StreamingWhisper.level(e), 1e-6f)
    }
}
