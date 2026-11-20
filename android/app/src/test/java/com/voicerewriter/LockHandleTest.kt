package com.voicerewriter

import com.voicerewriter.DictationStatus.Phase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockHandleTest {
    @Test fun showsOnTheLockScreenWhenWanted() {
        assertTrue(LockHandle.shouldShow(locked = true, wanted = true, phase = Phase.IDLE))
        // A result on screen is the end of a take: the tab comes back for the next one.
        assertTrue(LockHandle.shouldShow(locked = true, wanted = true, phase = Phase.TO_PC))
    }

    @Test fun neverWhenUnlockedOrNotWanted() {
        assertFalse(LockHandle.shouldShow(locked = false, wanted = true, phase = Phase.IDLE))
        assertFalse(LockHandle.shouldShow(locked = true, wanted = false, phase = Phase.IDLE))
    }

    @Test fun hiddenDuringATake() {
        assertFalse(LockHandle.shouldShow(locked = true, wanted = true, phase = Phase.LISTENING))
        assertFalse(LockHandle.shouldShow(locked = true, wanted = true, phase = Phase.WORKING))
    }
}
