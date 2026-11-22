package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckTest {
    private fun build(run: Int) = AppUpdater.Release(run, 1000L + run, 1, "soksak-dev-$run.apk", 1, "")

    @Before fun reset() {
        UpdateCheck.available = null; UpdateCheck.promptOpen = false; UpdateCheck.dismissedRun = -1
    }

    @Test fun newerBuildShowsBannerAndDialog() {
        UpdateCheck.offer(build(170), 1169, prompt = true)
        assertEquals(170, UpdateCheck.available?.run)
        assertTrue(UpdateCheck.promptOpen)
    }

    /** "Later" keeps the banner but the dialog does not come back for that build. */
    @Test fun declinedBuildKeepsBannerOnly() {
        UpdateCheck.dismissedRun = 170
        UpdateCheck.offer(build(170), 1169, prompt = true)
        assertEquals(170, UpdateCheck.available?.run)
        assertFalse(UpdateCheck.promptOpen)
        UpdateCheck.offer(build(171), 1169, prompt = true)
        assertTrue(UpdateCheck.promptOpen)
    }

    /** The check Settings runs feeds the banner without raising the dialog. */
    @Test fun settingsCheckDoesNotPrompt() {
        UpdateCheck.offer(build(170), 1169, prompt = false)
        assertEquals(170, UpdateCheck.available?.run)
        assertFalse(UpdateCheck.promptOpen)
    }

    @Test fun currentBuildClearsBanner() {
        UpdateCheck.offer(build(170), 1169, prompt = true)
        UpdateCheck.offer(build(170), 1170, prompt = true)
        assertNull(UpdateCheck.available)
        assertFalse(UpdateCheck.promptOpen)
    }
}
