package com.voicerewriter

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscribeProgressTest {
    private val model = "progress-test-model" // default rate 0.3 s per audio s

    @After fun tearDown() = TranscribeProgress.end()

    @Test fun nothingWhenNotWaiting() {
        TranscribeProgress.end()
        assertNull(TranscribeProgress.fraction(1000))
    }

    @Test fun followsTheTimeEstimate() {
        TranscribeProgress.begin(model, remainingSec = 10f, nowMs = 0) // expect 3s of decoding
        assertEquals(0f, TranscribeProgress.fraction(0)!!, 1e-4f)
        assertEquals(0.5f, TranscribeProgress.fraction(1500)!!, 1e-3f)
    }

    @Test fun neverClosesOnItsOwnAndNeverGoesBack() {
        TranscribeProgress.begin(model, remainingSec = 10f, nowMs = 0)
        var last = 0f
        for (ms in 0L..60_000L step 500) {
            val f = TranscribeProgress.fraction(ms)!!
            assertTrue("went back at $ms", f >= last)
            assertTrue("past the cap at $ms", f <= TranscribeProgress.CAP)
            last = f
        }
        assertTrue(last > 0.9f)
    }

    @Test fun finishedPiecesMoveItAhead() {
        TranscribeProgress.begin(model, remainingSec = 10f, nowMs = 0)
        TranscribeProgress.pieceDone(6f)
        assertEquals(0.6f, TranscribeProgress.fraction(100)!!, 1e-3f)
        // A slow piece afterwards doesn't pull it back.
        assertEquals(0.6f, TranscribeProgress.fraction(200)!!, 1e-3f)
    }

    @Test fun learnsTheModelsRate() {
        val m = "progress-rate-model"
        TranscribeProgress.record(m, audioSec = 4f, computeMs = 4000) // 1.0
        assertEquals(1f, TranscribeProgress.rate(m), 1e-4f)
        TranscribeProgress.record(m, audioSec = 4f, computeMs = 2000) // 0.5 -> blended 0.75
        assertEquals(0.75f, TranscribeProgress.rate(m), 1e-4f)
        TranscribeProgress.record(m, audioSec = 0.2f, computeMs = 9000) // too short to count
        assertEquals(0.75f, TranscribeProgress.rate(m), 1e-4f)
    }
}
