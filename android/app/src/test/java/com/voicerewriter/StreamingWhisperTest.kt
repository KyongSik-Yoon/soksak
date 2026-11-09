package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingWhisperTest {
    private val frame = 320
    private fun frames(sec: Double, v: Float) = List((sec * 16000 / frame).toInt()) { v }

    @Test fun whisperStreamsOnCpuAndNpuButSenseVoiceDoesNot() {
        assertEquals(true, StreamingWhisper.streams(SpeechEngine.WHISPER))
        assertEquals(true, StreamingWhisper.streams(SpeechEngine.QNN_WHISPER))
        assertEquals(false, StreamingWhisper.streams(SpeechEngine.SENSE_VOICE))
    }

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

    @Test fun tailDropsTheAutoStopSilence() {
        // Piece starts at 6s: 2s speech then 3s of auto-stop silence.
        val e = frames(6.0, 0.1f) + frames(2.0, 0.1f) + frames(3.0, 0.001f)
        val start = 6 * 16000
        val end = StreamingWhisper.tailEnd(e, start, e.size * frame, 0.1f)
        assertEquals(8 * 16000 + (StreamingWhisper.TAIL_MARGIN_S * 16000).toInt(), end)
    }

    @Test fun tailWithoutVoiceKeepsEverything() {
        val e = frames(6.0, 0.1f) + frames(2.0, 0.001f)
        assertEquals(e.size * frame, StreamingWhisper.tailEnd(e, 6 * 16000, e.size * frame, 0.1f))
    }

    @Test fun tailNeverRunsPastTheRecording() {
        val e = frames(3.0, 0.1f)
        assertEquals(e.size * frame, StreamingWhisper.tailEnd(e, 0, e.size * frame, 0.1f))
    }
}
