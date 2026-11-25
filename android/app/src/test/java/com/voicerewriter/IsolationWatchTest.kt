package com.voicerewriter

import org.junit.Assert.assertEquals
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
        w.run(0.3, micSound = true, isoSpeech = true)
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

    @Test fun scrapsOfTheVoiceLeakingThroughAreNotTheVoiceComingBack() {
        // At home 94% and 97% of two takes were gone and "No voice" never came up: Silero heard
        // a chunk here and there of what leaked through.
        val w = IsolationWatch()
        repeat(10) {
            w.run(0.9, micSound = true, isoSpeech = false)
            w.run(0.1, micSound = true, isoSpeech = true)
        }
        assertTrue(w.unheard)
        assertTrue(w.lostVoice())
    }

    @Test fun aTakeThatCameThroughIsNotLost() {
        val w = IsolationWatch()
        repeat(8) {
            w.run(1.5, micSound = true, isoSpeech = true)
            w.run(0.5, micSound = false, isoSpeech = false)
        }
        assertFalse(w.lostVoice())
        assertEquals(0.0, w.unheardSeconds, 0.0)
    }

    @Test fun aShortTakeIsolationSilencedIsLostBeforeItCouldShowNoVoice() {
        // Too short for the 2.5s "No voice" window, but nothing came through.
        val w = IsolationWatch()
        w.run(2.0, micSound = true, isoSpeech = false)
        assertFalse(w.unheard)
        assertTrue(w.lostVoice())
    }

    @Test fun aWhisperDroppedAfterTenSecondsIsLost() {
        val w = IsolationWatch()
        w.run(10.0, micSound = true, isoSpeech = true)
        w.run(4.0, micSound = true, isoSpeech = false)
        assertTrue(w.lostVoice())
    }

    @Test fun aBlipOfSoundIsNotALostTake() {
        val w = IsolationWatch()
        w.run(0.5, micSound = true, isoSpeech = false)
        w.run(3.0, micSound = false, isoSpeech = false)
        assertFalse(w.lostVoice())
    }
}
