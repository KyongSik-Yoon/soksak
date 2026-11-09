package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatWordCountTest {
    @Test fun `exact below ten thousand`() {
        assertEquals("0", formatWordCount(0))
        assertEquals("%,d".format(1582), formatWordCount(1582))
    }

    @Test fun `compact from ten thousand`() {
        assertEquals("%.1fK".format(10.0), formatWordCount(10_000))
        assertEquals("%.1fM".format(1.0), formatWordCount(999_950))
    }

    @Test fun `never wider than six characters up to a billion`() {
        for (n in listOf(0L, 9_999, 10_000, 99_949, 99_950, 999_949, 999_950, 9_949_999, 999_949_999)) {
            assertTrue("$n -> ${formatWordCount(n)}", formatWordCount(n).length <= 6)
        }
    }
}
