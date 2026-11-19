package com.voicerewriter.ui

import androidx.compose.ui.unit.em
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HangulTrackingTest {
    @Test fun detectsHangul() {
        assertTrue(hasHangul("~93MB · 화자 게이트 + 음성 분리"))
        assertTrue(hasHangul("사용 중"))
        assertFalse(hasHangul("~93MB · speaker gate + voice isolation"))
        assertFalse(hasHangul("1.6.0 · build 158"))
    }

    @Test fun dropsTrackingOnlyForHangul() {
        assertEquals(0.em, MonoEyebrow.trackedFor("버블").letterSpacing)
        assertEquals(0.18.em, MonoEyebrow.trackedFor("BUBBLE").letterSpacing)
    }
}
