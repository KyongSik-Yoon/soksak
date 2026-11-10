package com.voicerewriter

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token is optional, so the same status code means different things with and without one:
 * a 404 without a token is a private repo, with a token it's a token without access.
 */
class AppUpdaterTest {

    @Test fun `no token and 404 points at the private repo, not a broken token`() {
        val m = AppUpdater.failure(404, "", "the releases")
        assertTrue(m, m.contains("private") && m.contains("add a GitHub token"))
    }

    @Test fun `no token and 403 is the anonymous rate limit`() {
        val m = AppUpdater.failure(403, "", "the releases")
        assertTrue(m, m.contains("hourly limit"))
    }

    @Test fun `with a token, 403 and 404 mean the token lacks access`() {
        for (code in listOf(403, 404)) {
            val m = AppUpdater.failure(code, "github_pat_x", "the releases")
            assertTrue(m, m.contains("token can't read"))
        }
    }

    @Test fun `401 is a rejected token`() {
        assertTrue(AppUpdater.failure(401, "github_pat_x", "x").contains("rejected the token"))
    }
}
