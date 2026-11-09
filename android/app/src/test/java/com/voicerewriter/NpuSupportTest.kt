package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuSupportTest {
    @Test fun snapdragon8EliteGen5IsSupported() {
        assertTrue(NpuSupport.supports("QTI", "SM8850"))
        assertTrue(NpuSupport.supports("QTI", "sm8850-ac"))
    }

    @Test fun otherChipsAreNot() {
        assertFalse(NpuSupport.supports("QTI", "SM8750")) // needs the v79 skel, not bundled
        assertFalse(NpuSupport.supports("Samsung", "s5e9965")) // Exynos
        assertFalse(NpuSupport.supports("QTI", null))
    }
}

class QnnWindowsTest {
    private val sr = 16_000

    @Test fun shortAudioIsOneWindow() {
        val a = FloatArray(10 * sr) { 0.1f }
        assertEquals(listOf(0 until a.size), LocalSherpaStt.windows(a))
    }

    @Test fun longAudioIsCutAtTheQuietestSpotAndCoversEverything() {
        // 60s of "speech" with silent gaps at 22s and 47s.
        val a = FloatArray(60 * sr) { 0.1f }
        for (s in listOf(22, 47)) for (i in s * sr until s * sr + sr / 2) a[i] = 0f
        val w = LocalSherpaStt.windows(a)
        assertEquals(3, w.size)
        assertEquals(0, w.first().first)
        assertEquals(a.size - 1, w.last().last)
        for (i in 1 until w.size) assertEquals(w[i - 1].last + 1, w[i].first)
        assertTrue(w.all { it.count() <= (LocalSherpaStt.QNN_WINDOW_S * sr).toInt() })
        // The first cut lands inside the 22s gap.
        assertTrue(w[0].last + 1 in 22 * sr..22 * sr + sr / 2)
    }

    @Test fun npuModelsOnlyListedWhenSupported() {
        // JVM tests: Build.SOC_MODEL is null, so the NPU model is hidden.
        assertFalse(WhisperModelManager.available().any { it.engine == SpeechEngine.QNN_WHISPER })
        assertTrue(WhisperModelManager.MODELS.any { it.id == WhisperModelManager.MEDIUM_NPU_MODEL })
    }
}
