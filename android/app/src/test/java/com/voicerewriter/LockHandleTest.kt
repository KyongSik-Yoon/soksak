package com.voicerewriter

import com.voicerewriter.DictationStatus.Phase
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
}
