package com.voicerewriter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KaldiFbank] 프레임 수 경계, CMN 평균, 주파수 대역 에너지 분리 검증 테스트. */
class KaldiFbankTest {
    @Test fun `frame count obeys snipEdges boundaries`() {
        assertEquals(0, KaldiFbank.frameCount(0))
        assertEquals(0, KaldiFbank.frameCount(399))
        assertEquals(1, KaldiFbank.frameCount(400))
        assertEquals(1, KaldiFbank.frameCount(559))
        assertEquals(2, KaldiFbank.frameCount(560))
        assertEquals(98, KaldiFbank.frameCount(16_000))
    }

    @Test fun `too short input returns empty features`() {
        val out = KaldiFbank.compute(FloatArray(399))
        assertEquals(0, out.size)
    }

    @Test fun `sine wave produces zero-mean CMN features and distinguishes frequency bands`() {
        // 전반 0.5초는 200Hz 저음, 후반 0.5초는 2500Hz 고음으로 구성해 CMN 적용 후 대역 반전을 검증한다.
        val sr = KaldiFbank.SAMPLE_RATE
        val n = sr
        val half = n / 2
        val wav = FloatArray(n) { i ->
            val hz = if (i < half) 200.0 else 2500.0
            (0.5 * sin(2.0 * PI * hz * i / sr)).toFloat()
        }

        val feats = KaldiFbank.compute(wav)
        val numFrames = KaldiFbank.frameCount(n)
        assertEquals(numFrames * KaldiFbank.NUM_BINS, feats.size)

        // 유한값 및 채널별 전역 평균(CMN) ≈ 0 검증
        for (b in 0 until KaldiFbank.NUM_BINS) {
            var sum = 0.0
            for (t in 0 until numFrames) {
                val v = feats[t * KaldiFbank.NUM_BINS + b]
                assertTrue("NaN/Inf detected at frame=$t bin=$b", v.isFinite())
                sum += v
            }
            val mean = sum / numFrames
            assertTrue("CMN mean for bin $b should be ~0, got $mean", abs(mean) < 1e-4)
        }

        // 전반부 프레임(0..30)에서는 저역(bin 4..10)이 중고역(bin 40..50)보다 크고, 후반부(60..90)에서는 반대여야 함
        var earlyLow = 0.0
        var earlyHigh = 0.0
        for (t in 5 until 35) {
            for (b in 4..10) earlyLow += feats[t * KaldiFbank.NUM_BINS + b]
            for (b in 40..50) earlyHigh += feats[t * KaldiFbank.NUM_BINS + b]
        }
        var lateLow = 0.0
        var lateHigh = 0.0
        for (t in 60 until 90) {
            for (b in 4..10) lateLow += feats[t * KaldiFbank.NUM_BINS + b]
            for (b in 40..50) lateHigh += feats[t * KaldiFbank.NUM_BINS + b]
        }
        assertTrue("200Hz 구간에서 저역 에너지가 더 커야 함: $earlyLow vs $earlyHigh", earlyLow > earlyHigh)
        assertTrue("2500Hz 구간에서 중고역 에너지가 더 커야 함: $lateHigh vs $lateLow", lateHigh > lateLow)
    }
}
