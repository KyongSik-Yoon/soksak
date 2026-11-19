package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEnrollmentTest {
    private fun unit(vararg v: Float): FloatArray = VoiceEnrollment.mean(listOf(v))

    @Test fun meanIsNormalised() {
        val m = VoiceEnrollment.mean(listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
        assertEquals(0.7071f, m[0], 1e-3f)
        assertEquals(0.7071f, m[1], 1e-3f)
    }

    @Test fun outlierIsTheOneThatDiffers() {
        val me1 = unit(1f, 0.1f, 0f)
        val me2 = unit(1f, -0.1f, 0.05f)
        val tv = unit(0f, 0.2f, 1f)
        val worst = VoiceEnrollment.leastConsistent(listOf(me1, tv, me2))!!
        assertEquals(1, worst.index)
        assertTrue(worst.similarity < VoiceEnrollment.CONSISTENCY_MIN)
    }

    @Test fun consistentClipsPass() {
        val worst = VoiceEnrollment.leastConsistent(
            listOf(unit(1f, 0.1f, 0f), unit(1f, -0.1f, 0.05f), unit(0.9f, 0f, 0.1f)),
        )!!
        assertTrue(worst.similarity >= VoiceEnrollment.CONSISTENCY_MIN)
    }

    @Test fun oneClipHasNothingToCompare() {
        assertNull(VoiceEnrollment.leastConsistent(listOf(unit(1f, 0f))))
    }

    @Test fun picksTheBestScoringWindow() {
        // Best window starts at 30; hop 10 covers it.
        val st = VoiceEnrollment.bestWindowStart(100, 20, 10, 50) { s -> -kotlin.math.abs(s - 30).toFloat() }
        assertEquals(30, st)
    }

    @Test fun lastWindowIsAlwaysTried() {
        val seen = ArrayList<Int>()
        VoiceEnrollment.bestWindowStart(95, 20, 10, 50) { s -> seen.add(s); 0f }
        assertEquals(75, seen.last())
        assertEquals(0, seen.first())
    }

    @Test fun capsTheNumberOfWindows() {
        val seen = ArrayList<Int>()
        VoiceEnrollment.bestWindowStart(10_000, 100, 1, 10) { s -> seen.add(s); 0f }
        assertTrue("tried ${seen.size}", seen.size <= 10)
        assertEquals(9_900, seen.last())
    }

    @Test fun skipsWindowsWithoutAScore() {
        val st = VoiceEnrollment.bestWindowStart(100, 20, 10, 50) { s -> if (s == 40) 1f else null }
        assertEquals(40, st)
        assertNull(VoiceEnrollment.bestWindowStart(100, 20, 10, 50) { null })
        assertNull(VoiceEnrollment.bestWindowStart(10, 20, 10, 50) { 1f })
    }
}
