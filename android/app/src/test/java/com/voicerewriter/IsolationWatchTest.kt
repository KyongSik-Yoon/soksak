package com.voicerewriter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsolationWatchTest {
    private val chunk = 512
    private fun IsolationWatch.run(sec: Double, micSound: Boolean, isoSpeech: Boolean) {
        repeat((sec * 16000 / chunk).toInt()) { mic(micSound, chunk); isolated(isoSpeech) }
    }

    @Test fun aVoiceThatComesThroughIsHeard() {
        val w = IsolationWatch()
        w.run(20.0, micSound = true, isoSpeech = true)
        assertFalse(w.unheard)
    }

    @Test fun soundThatIsolationSilencesIsUnheardAfterAWhile() {
        val w = IsolationWatch()
        w.run(8.0, micSound = true, isoSpeech = true)
        w.run(2.0, micSound = true, isoSpeech = false)
        assertFalse(w.unheard) // not yet 2.5s
        w.run(1.0, micSound = true, isoSpeech = false)
        assertTrue(w.unheard)
        // The voice comes back.
        w.run(0.1, micSound = true, isoSpeech = true)
        assertFalse(w.unheard)
    }

    @Test fun aSilentPauseIsNotUnheard() {
        val w = IsolationWatch()
        w.run(8.0, micSound = true, isoSpeech = true)
        w.run(10.0, micSound = false, isoSpeech = false)
        assertFalse(w.unheard)
    }

    @Test fun aTakeThatNeverComesThroughIsUnheardFromTheStart() {
        val w = IsolationWatch()
        w.run(3.0, micSound = true, isoSpeech = false)
        assertTrue(w.unheard)
        assertTrue(w.unheardSeconds > 0.3)
    }

    @Test fun aFewClicksInAPauseAreNotEnoughSound() {
        val w = IsolationWatch()
        repeat(10) { w.run(0.9, micSound = false, isoSpeech = false); w.run(0.1, micSound = true, isoSpeech = false) }
        assertFalse(w.unheard)
    }
}
