package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls are anonymous, so a 403 is the hourly limit and a 404 is a missing thing. */
class AppUpdaterTest {

    @Test fun `403 is the anonymous hourly limit`() {
        val m = AppUpdater.failure(403, "the releases")
        assertTrue(m, m.contains("hourly limit") && m.contains("Try again later"))
    }

    @Test fun `404 names what was not found`() {
        assertEquals("Couldn't find release build-9.", AppUpdater.failure(404, "release build-9"))
    }

    @Test fun `other codes keep the generic message`() {
        assertEquals("GitHub returned 500.", AppUpdater.failure(500, "x"))
    }
}
