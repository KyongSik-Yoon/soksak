package com.voicerewriter

import com.voicerewriter.DictationStatus.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockHandleTest {
    private fun show(locked: Boolean = true, unlocked: Boolean = false, wanted: Boolean = true, phase: Phase = Phase.IDLE) =
        LockHandle.shouldShow(locked, unlocked, wanted, phase)

    @Test fun showsOnTheLockScreenWhenWanted() {
        assertTrue(show())
        // A result on screen is the end of a take: the tab comes back for the next one.
        assertTrue(show(phase = Phase.TO_PC))
    }

    @Test fun neverWhenUnlockedOrNotWanted() {
        assertFalse(show(locked = false))
        assertFalse(show(wanted = false))
    }

    @Test fun anUnlockWinsOverAKeyguardStillReadingLocked() {
        assertFalse(show(locked = true, unlocked = true))
    }

    @Test fun hiddenDuringATake() {
        assertFalse(show(phase = Phase.LISTENING))
        assertFalse(show(phase = Phase.WORKING))
    }

    @Test fun startsAtTheBubblesHeightAndVisitsEverySlotEvenly() {
        assertEquals(0, LockHandle.shiftSlot(0))
        val seen = (0 until LockHandle.SHIFT_SLOTS * 3).map { LockHandle.shiftSlot(it) }
        assertEquals((-4..4).toList(), seen.distinct().sorted())
        assertTrue(seen.groupingBy { it }.eachCount().values.all { it == 3 })
    }

    @Test fun movesFarOnEveryScreenOff() {
        for (n in 0 until 40) {
            val d = kotlin.math.abs(LockHandle.shiftSlot(n + 1) - LockHandle.shiftSlot(n))
            assertTrue("step $n moved $d", d >= 4)
        }
    }

    @Test fun shiftStaysOnTheScreen() {
        assertEquals(500 + 3 * 40, LockHandle.shiftedY(500, 3, 40, 200, 2000))
        assertEquals(0, LockHandle.shiftedY(50, -4, 40, 200, 2000))
        assertEquals(1800, LockHandle.shiftedY(1750, 4, 40, 200, 2000))
    }
}
