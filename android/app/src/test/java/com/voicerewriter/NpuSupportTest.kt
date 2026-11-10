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
        // 36s of "speech" with silent gaps at 12s and 24s.
        val a = FloatArray(36 * sr) { 0.1f }
        for (s in listOf(12, 24)) for (i in s * sr until s * sr + sr / 2) a[i] = 0f
        val w = LocalSherpaStt.windows(a)
        assertEquals(3, w.size)
        assertEquals(0, w.first().first)
        assertEquals(a.size - 1, w.last().last)
        for (i in 1 until w.size) assertEquals(w[i - 1].last + 1, w[i].first)
        assertTrue(w.all { it.count() <= (LocalSherpaStt.QNN_WINDOW_S * sr).toInt() })
        // The first cut lands inside the 12s gap.
        assertTrue(w[0].last + 1 in 12 * sr..12 * sr + sr / 2)
    }

    /** What sherpa's QNN decoder allows, two frames short to allow for the feature count. */
    private fun textTokenLimit(samples: Int): Int = ((samples / 160 - 2) / 100.0 * 6).toInt() - 4

    @Test fun everyWindowGetsRoomForFastSpeech() {
        // The reported case: without padding a 3s clip allows 14 tokens and loses its last syllables.
        assertTrue(textTokenLimit(3 * sr) <= 14)
        for (s in listOf(0.5, 1.0, 3.0, 5.0, LocalSherpaStt.QNN_WINDOW_S)) {
            val n = (s * sr).toInt()
            val padded = LocalSherpaStt.paddedForTokenBudget(FloatArray(n) { 0.1f })
            assertTrue("$s s", textTokenLimit(padded.size) >= (s * 12).toInt())
            assertTrue("$s s fits the 30s input", padded.size <= 30 * sr)
        }
    }

    @Test fun hintTokensDoNotEatIntoTheSpeechBudget() {
        val hint = LocalSherpaStt.qnnHint(WhisperModelManager.MEDIUM_NPU_MODEL, "ko").size
        assertTrue(hint > 0)
        for (s in listOf(0.5, 3.0, LocalSherpaStt.QNN_WINDOW_S)) {
            val n = (s * sr).toInt()
            val padded = LocalSherpaStt.paddedForTokenBudget(FloatArray(n) { 0.1f }, hintTokens = hint)
            assertTrue("$s s", textTokenLimit(padded.size) - hint >= (s * 12).toInt())
            assertTrue("$s s fits the 30s input", padded.size <= 30 * sr)
        }
    }

    @Test fun hintIsForKoreanOnMediumOnly() {
        assertTrue(LocalSherpaStt.qnnHint(WhisperModelManager.SMALL_ACFT_NPU_MODEL, "ko").isEmpty())
        assertTrue(LocalSherpaStt.qnnHint(WhisperModelManager.MEDIUM_NPU_MODEL, "en").isEmpty())
    }

    @Test fun onlyTheHintsSilenceOutputCountsAsEcho() {
        assertTrue(LocalSherpaStt.isHintEcho("네."))
        assertTrue(LocalSherpaStt.isHintEcho("네, 알겠습니다."))
        assertFalse(LocalSherpaStt.isHintEcho("네, 정말 감사합니다."))
    }

    @Test fun strayVadFramesAreNotSpeech() {
        assertFalse(SileroVad.enoughSpeech(listOf(0.9f, 0.1f, 0.9f, 0.1f)))
        assertFalse(SileroVad.enoughSpeech(listOf(0.9f, 0.1f, 0.9f, 0.1f, 0.9f, 0.2f, 0.8f)))
        assertTrue(SileroVad.enoughSpeech(listOf(0.1f, 0.9f, 1f, 0.6f)))
    }

    @Test fun paddingOnlyAppendsSilence() {
        val a = FloatArray(3 * sr) { 0.1f }
        val padded = LocalSherpaStt.paddedForTokenBudget(a)
        assertTrue(padded.size > a.size)
        assertTrue(a.indices.all { padded[it] == a[it] })
        assertTrue((a.size until padded.size).all { padded[it] == 0f })
    }

    @Test fun npuModelsOnlyListedWhenSupported() {
        // JVM tests: Build.SOC_MODEL is null, so the NPU model is hidden.
        assertFalse(WhisperModelManager.available().any { it.engine == SpeechEngine.QNN_WHISPER })
        assertTrue(WhisperModelManager.MODELS.any { it.id == WhisperModelManager.MEDIUM_NPU_MODEL })
        assertTrue(WhisperModelManager.MODELS.any { it.id == WhisperModelManager.SMALL_ACFT_NPU_MODEL })
    }
}
