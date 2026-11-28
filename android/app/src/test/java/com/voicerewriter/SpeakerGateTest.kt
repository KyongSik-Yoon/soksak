package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SpeakerGate] 규칙 A·B 마스킹, 저유사도 폴백 및 [SpeakerGateSession] Auto-stop 보호 검증 테스트. */
class SpeakerGateTest {
    private val sr = SpeakerGate.SR
    private val chunk = SpeakerGate.VAD_CHUNK

    private fun makeConstantPcm(samples: Int, amp: Short = 2000): ShortArray = ShortArray(samples) { amp }

    private fun probsForIntervals(totalSamples: Int, vararg intervals: Pair<Int, Int>): FloatArray {
        val frames = totalSamples / chunk
        return FloatArray(frames) { idx ->
            val pos = idx * chunk
            if (intervals.any { pos in it.first until it.second }) 0.85f else 0.05f
        }
    }

    @Test fun `rule A zeroes standalone non-target segment and keeps target segment`() {
        val total = 5 * sr // 5초
        val pcm = makeConstantPcm(total, amp = 3000)
        // 0.3s..1.5s: 내 목소리, 2.5s..4.0s: TV/타인 목소리 (간격 1.0s > 0.3s 이므로 별도 세그먼트)
        val seg1 = (0.3 * sr).toInt() to (1.5 * sr).toInt()
        val seg2 = (2.5 * sr).toInt() to (4.0 * sr).toInt()
        val probs = probsForIntervals(total, seg1, seg2)

        val segs = SpeakerGate.activeSegments(probs, total)
        assertEquals(2, segs.size)

        val scored = ArrayList<SpeakerGate.ScoredWindow>()
        for (seg in segs) {
            scored += SpeakerGate.scoreSegment(seg.start, seg.end) { start, _ ->
                if (start < 2 * sr) 0.72f else 0.10f
            }
        }

        val res = SpeakerGate.applyGateMask(pcm, probs, scored)
        assertEquals(1, res.keepRanges.size)
        // 내 목소리 세그먼트 중심부는 원본 진폭 유지
        assertEquals(3000.toShort(), res.samples[(0.9 * sr).toInt()])
        // 타인 세그먼트 중심부는 0으로 마스킹
        assertEquals(0.toShort(), res.samples[(3.2 * sr).toInt()])
    }

    @Test fun `rule B trims only non-target tails of mixed segment and preserves interior`() {
        val total = 5 * sr
        val pcm = makeConstantPcm(total, amp = 2500)
        // 연속된 단일 세그먼트 0.2s..4.5s
        val seg = (0.2 * sr).toInt() to (4.5 * sr).toInt()
        val probs = probsForIntervals(total, seg)
        val segs = SpeakerGate.activeSegments(probs, total)
        assertEquals(1, segs.size)

        // 앞(0..1.2s): 타인(0.10), 중간(1.2s..2.8s): 1.6s~2.0s 순간 비타겟 창(0.15)이 섞인 내 목소리(0.65), 뒤(2.8s..4.5s): 타인(0.10)
        val scored = SpeakerGate.scoreSegment(segs[0].start, segs[0].end) { start, _ ->
            val sec = start.toFloat() / sr
            when {
                sec < 1.1f -> 0.10f
                sec in 1.55f..1.95f -> 0.15f // 동시 발화/잠깐 끼어든 구간
                sec < 2.6f -> 0.68f
                else -> 0.10f
            }
        }

        val res = SpeakerGate.applyGateMask(pcm, probs, scored)
        assertEquals(1, res.keepRanges.size)
        // 앞뒤 비타겟 꼬리는 마스킹됨
        assertEquals(0.toShort(), res.samples[(0.4 * sr).toInt()])
        assertEquals(0.toShort(), res.samples[(4.0 * sr).toInt()])
        // 순간 저점(0.15f)이 있던 내부 구간도 통째로 보존됨 (overlap0 보호)
        assertEquals(2500.toShort(), res.samples[(1.8 * sr).toInt()])
    }

    @Test fun `fallback threshold preserves quiet solo segment below 0_35`() {
        val total = 3 * sr
        val pcm = makeConstantPcm(total, amp = 1800)
        val probs = probsForIntervals(total, (0.3 * sr).toInt() to (2.0 * sr).toInt())
        val segs = SpeakerGate.activeSegments(probs, total)
        val scored = SpeakerGate.scoreSegment(segs[0].start, segs[0].end) { _, _ -> 0.29f }

        val res = SpeakerGate.applyGateMask(pcm, probs, scored)
        assertEquals(1, res.keepRanges.size)
        assertEquals(1800.toShort(), res.samples[(1.0 * sr).toInt()])
    }

    @Test fun `take with no target window keeps its speech as rejected with the best score`() {
        val total = 4 * sr
        val pcm = makeConstantPcm(total, amp = 2200)
        val speech = (1.0 * sr).toInt() to (2.5 * sr).toInt()
        val probs = probsForIntervals(total, speech)
        val segs = SpeakerGate.activeSegments(probs, total)
        val scored = SpeakerGate.scoreSegment(segs[0].start, segs[0].end) { start, _ ->
            if (start < (1.2 * sr).toInt()) 0.12f else 0.21f
        }

        val res = SpeakerGate.applyGateMask(pcm, probs, scored)
        assertTrue("nothing is transcribed", res.samples.isEmpty())
        val rejected = res.rejected!!
        // the speech segment (with its pads) is kept, the silence around it is not
        assertTrue(rejected.size >= speech.second - speech.first)
        assertTrue(rejected.size < total)
        assertEquals(0.21f, res.bestSimilarity, 1e-6f)
        assertEquals(scored.size, res.windows)
    }

    @Test fun `silent take has nothing to reject`() {
        val total = 2 * sr
        val res = SpeakerGate.applyGateMask(makeConstantPcm(total), FloatArray(total / chunk) { 0.05f }, emptyList())
        assertTrue(res.samples.isEmpty())
        assertEquals(null, res.rejected)
    }

    @Test fun `session ignores TV voice for auto-stop and masks settled non-target streaming chunk`() {
        val hangover = 2 * sr // 2초 무음 시 Auto-stop
        val session = SpeakerGateSession(
            scorer = { start, _ ->
                // 0..2.0초는 내 목소리(0.75), 2.5초 이후는 TV 타인 말소리(0.08)
                if (start < 2 * sr) 0.75f else 0.08f
            },
            vadAutoStop = true,
            hangoverSamples = hangover,
        )

        val pcm = makeConstantPcm(7 * sr, amp = 1500)
        var end = 0
        // 1) 0..2.0s 타겟 발화 + 0.85s 쉼 (간격 > 0.60s 이므로 별도 세그먼트로 분리됨)
        while (end < (2.85 * sr).toInt()) {
            val prob = if (end <= 2 * sr) 0.85f else 0.05f
            end += chunk
            assertFalse(session.onVadFrame(prob, end))
        }
        // 백그라운드 스코러가 내 목소리 세그먼트를 채점할 시간 대기
        Thread.sleep(150)

        // 2) 2.85s..3.95s TV/타인 목소리 흘려줌 -> 3.95s~ 무음 전환 (실제 타인 창 채점 완료 대기 포함)
        while (end < (3.95 * sr).toInt()) {
            end += chunk
            assertFalse(session.onVadFrame(0.85f, end))
        }
        // 내 목소리 끝(2.15s) 기준 hangover(2.0s)가 지났으므로 TV 소리 직후 무음 시 Auto-stop 발동해야 함
        var fired = false
        while (end < (5.2 * sr).toInt()) {
            end += chunk
            if (session.onVadFrame(0.05f, end)) {
                fired = true
                break
            }
            Thread.sleep(5)
        }
        assertTrue("TV 소리 이후에도 타겟 무음 기준 Auto-stop이 발동해야 함", fired)

        // 3) 세그먼트 확정 마진(0.35s) 이후(5.6s까지) 침묵 진행 후 TV 구간 스트리밍 마스크 확인
        while (end < (5.6 * sr).toInt()) {
            end += chunk
            session.onVadFrame(0.05f, end)
        }
        Thread.sleep(150)
        val tvFrom = (2.9 * sr).toInt()
        val tvTo = (3.9 * sr).toInt()
        val maskedChunk = session.maskStreamingChunk(tvFrom, tvTo, pcm.copyOfRange(tvFrom, tvTo))
        assertTrue("확정된 비타겟 스트리밍 구간은 0으로 마스킹되어야 함", maskedChunk.all { it == 0.toShort() })

        val finalRes = session.finishAndApply(pcm)
        assertTrue(finalRes.keepRanges.isNotEmpty())
        assertEquals(1500.toShort(), finalRes.samples[minOf(finalRes.samples.lastIndex, sr)])
    }

    /** 2026-11-25 폰 로그: 본인 목소리가 기준 아래로 채점되는 동안 숨 한 번(0.2초)에 문장 중간에서 끝났다. */
    @Test fun `own voice scoring low does not end the take at a short breath, only at a pause`() {
        val hangover = (2.2 * sr).toInt()
        val session = SpeakerGateSession(
            scorer = { start, _ -> if (start < 2 * sr) 0.45f else 0.15f },
            vadAutoStop = true,
            hangoverSamples = hangover,
        )
        var end = 0
        fun feed(untilS: Double, prob: Float): Boolean {
            var fired = false
            while (end < (untilS * sr).toInt()) {
                end += chunk
                if (session.onVadFrame(prob, end)) fired = true
                Thread.sleep(1)
            }
            return fired
        }
        // 2초까지 인식된 목소리, 그 뒤 4초는 같은 사람인데 낮게 채점됨. 중간에 0.2초·0.4초 숨.
        assertFalse(feed(5.0, 0.9f))
        assertFalse("0.2초 숨에 끝나면 안 됨", feed(5.2, 0.05f))
        assertFalse(feed(6.0, 0.9f))
        assertFalse("0.4초 숨에 끝나면 안 됨", feed(6.4, 0.05f))
        assertFalse(feed(7.0, 0.9f))
        // 말이 실제로 멈추면 0.7초쯤에서 끝난다(대기 2.2초를 다시 세지 않음).
        assertFalse(feed(7.5, 0.05f))
        assertTrue("말이 멈춘 뒤에는 끝나야 함", feed(8.0, 0.05f))
        session.finishAndApply(makeConstantPcm(end))
    }

    @Test fun `finishAndApply는 전체 길이의 마스킹 배열과 세그먼트 점수 요약을 유지한다`() {
        val total = 5 * sr
        val pcm = makeConstantPcm(total, amp = 2400)
        // 0.3..1.5s 내 목소리(0.65), 2.5..4.0s 타인 목소리(0.11)
        val probs = probsForIntervals(total, (0.3 * sr).toInt() to (1.5 * sr).toInt(), (2.5 * sr).toInt() to (4.0 * sr).toInt())
        val session = SpeakerGateSession(
            scorer = { start, _ -> if (start < 2 * sr) 0.65f else 0.11f },
            vadAutoStop = false,
            hangoverSamples = 2 * sr,
            label = "mic-gate",
        )
        assertFalse(session.hasTargetVoice())
        for (i in probs.indices) {
            session.onVadFrame(probs[i], (i + 1) * chunk)
        }
        // 녹음 중 백그라운드 채점으로 hasTargetVoice()가 켜지는지 확인
        val deadline = System.currentTimeMillis() + 500
        while (!session.hasTargetVoice() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(session.hasTargetVoice())
        val res = session.finishAndApply(pcm)
        assertEquals(total, res.maskedFull.size)
        assertEquals(2400.toShort(), res.maskedFull[(0.9 * sr).toInt()])
        assertEquals(0.toShort(), res.maskedFull[(3.2 * sr).toInt()])
        assertTrue(session.summary().startsWith("gate(mic-gate):"))

        val segs = SpeakerGate.activeSegments(probs, total)
        val scored = segs.flatMap { seg ->
            SpeakerGate.scoreSegment(seg.start, seg.end) { start, _ -> if (start < 2 * sr) 0.65f else 0.11f }
        }
        val fmt = SpeakerGate.formatSegmentScores(segs, scored)
        assertTrue(fmt, fmt.contains("0.65..0.65") && fmt.contains("0.11..0.11"))
    }
}
