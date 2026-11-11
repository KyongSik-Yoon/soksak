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

    @Test fun silenceFractionCountsUpOnlyAfterSpeech() {
        val d = detector() // 1.6s hangover
        run(d, frames(1.0, 0.001f))
        assertEquals(0f, d.silenceFraction(16000), 0f) // no speech yet: nothing to wait out
        val spoken = frames(1.0, 0.001f) + frames(1.0, 0.006f)
        val d2 = detector()
        run(d2, spoken)
        val end = spoken.size * n
        assertEquals(0f, d2.silenceFraction(end), 0.02f)
        assertEquals(0.5f, d2.silenceFraction(end + (0.8 * 16000).toInt()), 0.02f)
        assertEquals(1f, d2.silenceFraction(end + 5 * 16000), 0f)
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

    /** A 1 kHz tone (inside the measured band) at each part's amplitude. */
    private fun audio(vararg parts: Pair<Double, Float>): FloatArray {
        var k = 0
        return parts.flatMap { (sec, amp) -> List((sec * 16000).toInt()) { amp * kotlin.math.sin(2 * Math.PI * 1000 * k++ / 16000).toFloat() } }.toFloatArray()
    }

    /** Feeds [x] as the recorder does (512-sample reads); the time auto-stop fired, or null. */
    private fun fireSec(x: FloatArray, hangoverSec: Double = 3.0): Double? {
        val d = QuietSpeechDetector((hangoverSec * 16000).toInt(), (0.4 * 16000).toInt())
        var pos = 0
        while (pos + 512 <= x.size) {
            val buf = ShortArray(512) { (x[pos + it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }
            pos += 512
            if (d.feedPcm(buf, 512, pos)) return pos / 16000.0
        }
        return null
    }

    /**
     * What the phone's microphone gave in the real takes: a zero frame, a faint start, then a
     * room whose low rumble (full-band about -47 dBFS, wandering by several dB) is far louder
     * than the whisper, which only shows in the 500-2500 Hz band (room -71, whisper -60 dBFS).
     */
    private fun realTake(whisperSec: Double, tailSec: Double): FloatArray {
        val room = audio(0.03 to 0f, 0.25 to 0.0001f, 1.0 to 0.0004f, whisperSec to 0.0015f, tailSec to 0.0004f)
        return FloatArray(room.size) { i ->
            val wander = 1.0 + 0.5 * kotlin.math.sin(2 * Math.PI * 0.7 * i / 16000)
            val hum = if (i < 480) 0.0 else 0.006 * wander * kotlin.math.sin(2 * Math.PI * 120 * i / 16000)
            (room[i] + hum).toFloat()
        }
    }

    @Test fun whisperUnderLoudRumbleStopsAfterTheHangover() {
        // 1.28s of start-up and room, 4s whispered, then quiet: stops ~3s after the whisper.
        val fired = fireSec(realTake(whisperSec = 4.0, tailSec = 5.0))
        assertTrue("fired at $fired", fired != null && fired in 8.2..8.6)
    }

    @Test fun whisperUnderLoudRumbleIsNotCutShort() {
        assertEquals(null, fireSec(realTake(whisperSec = 6.0, tailSec = 2.0)))
    }

    @Test fun rumbleAloneNeverStopsAndIsNotSpeech() {
        val x = realTake(whisperSec = 0.0, tailSec = 8.0)
        assertEquals(null, fireSec(x))
        assertFalse(QuietSpeechDetector.hasSpeech(x))
    }

    @Test fun clickDoesNotRestartTheWait() {
        val base = realTake(whisperSec = 3.0, tailSec = 6.0)
        // A 30ms click two seconds into the tail.
        val at = ((1.28 + 3.0 + 2.0) * 16000).toInt()
        for (i in at until at + 480) base[i] += 0.01f * kotlin.math.sin(2 * Math.PI * 1500 * i / 16000).toFloat()
        val fired = fireSec(base)
        assertTrue("fired at $fired", fired != null && fired in 7.2..7.6)
    }

    @Test fun silentTakeHasNoSpeech() {
        // Room hiss with a short click: the "네." echo from such a take should be dropped.
        val room = audio(2.0 to 0.001f, 0.04 to 0.01f, 2.0 to 0.001f)
        assertFalse(QuietSpeechDetector.hasSpeech(room))
        assertFalse(QuietSpeechDetector.hasSpeech(FloatArray(32000)))
    }

    @Test fun shortWhisperCountsAsSpeech() {
        // A whispered "네" (~0.3s, 6x the room) must survive, which Silero would miss.
        assertTrue(QuietSpeechDetector.hasSpeech(audio(1.0 to 0.001f, 0.3 to 0.006f, 1.0 to 0.001f)))
    }
}
