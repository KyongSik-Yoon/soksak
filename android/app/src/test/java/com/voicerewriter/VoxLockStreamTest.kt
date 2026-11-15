package com.voicerewriter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [VoxLockEngine.fitSample]과 [VoxLockEngine.Stream]의 OLA·청크 분할 불변성을 검증한다. */
class VoxLockStreamTest {
    @Test fun `fitSample rejects shorter than 3 seconds and picks loudest 3 second window`() {
        var threw = false
        try {
            VoxLockEngine.fitSample(FloatArray(VoxLockEngine.SAMPLE - 1))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("3초 미만 입력은 예외가 발생해야 함", threw)

        // 총 5초(80,000 샘플): 앞 1.5초는 진폭 0.05, 뒤 3.5초는 진폭 0.6 -> 추출 결과 평균 진폭이 높아야 함
        val n = 5 * VoxLockEngine.SR
        val wav = FloatArray(n) { i ->
            val amp = if (i < (1.5 * VoxLockEngine.SR).toInt()) 0.05f else 0.6f
            (amp * sin(2.0 * PI * 440.0 * i / VoxLockEngine.SR)).toFloat()
        }
        val fitted = VoxLockEngine.fitSample(wav)
        assertEquals(VoxLockEngine.SAMPLE, fitted.size)
        val meanAbs = fitted.map { abs(it) }.average()
        assertTrue("가장 음량이 큰 후반부 3초가 선택되어야 함: meanAbs=$meanAbs", meanAbs > 0.3)
    }

    @Test fun `stream produces exact input length and matches reference regardless of chunk sizes`() {
        val total = 12_345
        val input = FloatArray(total) { i ->
            (0.4 * sin(2.0 * PI * 330.0 * i / VoxLockEngine.SR)).toFloat()
        }

        // 이상적인 통과 더미 러너: 각 프레임 f에 WIN2 / GAIN 가중치를 얹어 반환하면 Stream OLA 후 입력 파형이 그대로 복원됨
        fun passthroughRunner() = VoxLockEngine.chunkRunner { seg, kFrames, _, _, outFrames ->
            for (j in 0 until kFrames) {
                val baseSeg = j * VoxLockEngine.HOP
                val baseOut = j * VoxLockEngine.NFFT
                for (i in 0 until VoxLockEngine.NFFT) {
                    outFrames[baseOut + i] = seg[baseSeg + i] * (VoxLockEngine.WIN2[i] / VoxLockEngine.GAIN)
                }
            }
        }

        val chunkPattern = intArrayOf(17, 256, 512, 640, 2000, 128, 333)
        val stream = VoxLockEngine.Stream(passthroughRunner())
        val out = ArrayList<Float>(total)
        var off = 0
        var p = 0
        while (off < total) {
            val take = minOf(chunkPattern[p % chunkPattern.size], total - off)
            p++
            val piece = stream.push(input.copyOfRange(off, off + take))
            for (v in piece) out.add(v)
            off += take
        }
        val tail = stream.flush()
        for (v in tail) out.add(v)

        assertEquals("총 출력 샘플 수가 입력과 정확히 일치해야 함", total, out.size)

        var maxErr = 0f
        for (i in 16 until total - 16) {
            maxErr = max(maxErr, abs(input[i] - out[i]))
        }
        assertTrue("내부 파형 오차가 1e-4 이하여야 함: maxErr=$maxErr", maxErr < 1e-4f)
    }

    @Test fun `stream flush handles tiny input under PAD`() {
        val tiny = FloatArray(120) { i -> i * 0.001f }
        val stream = VoxLockEngine.Stream(
            VoxLockEngine.chunkRunner { _, _, _, _, _ ->
                throw AssertionError("PAD 미만 입력에서는 모델이 호출되지 않아야 함")
            },
        )
        assertEquals(0, stream.push(tiny).size)
        val flushed = stream.flush()
        assertEquals(tiny.size, flushed.size)
        for (i in tiny.indices) {
            assertEquals(tiny[i], flushed[i], 1e-6f)
        }
    }

    @Test fun `stream matches variable runner when using fixedFrames chunk runner`() {
        fun passthroughRunner(fixed: Int?) = VoxLockEngine.chunkRunner(fixedFrames = fixed) { seg, kFrames, _, _, outFrames ->
            if (fixed != null) {
                assertEquals(fixed, kFrames)
                assertEquals(VoxLockQnn.FIXED_SEG_LEN, seg.size)
            }
            for (j in 0 until kFrames) {
                val baseSeg = j * VoxLockEngine.HOP
                val baseOut = j * VoxLockEngine.NFFT
                for (i in 0 until VoxLockEngine.NFFT) {
                    outFrames[baseOut + i] = seg[baseSeg + i] * (VoxLockEngine.WIN2[i] / VoxLockEngine.GAIN)
                }
            }
        }

        val chunkPattern = intArrayOf(64, 512, 1000, 333, 2500)
        for (total in intArrayOf(1200, 3584, 3700, 12_345)) {
            val input = FloatArray(total) { i ->
                (0.4 * sin(2.0 * PI * 330.0 * i / VoxLockEngine.SR)).toFloat()
            }
            fun runWith(fixed: Int?): FloatArray {
                val stream = VoxLockEngine.Stream(passthroughRunner(fixed))
                val out = ArrayList<Float>(total)
                var off = 0
                var p = 0
                while (off < total) {
                    val take = minOf(chunkPattern[p % chunkPattern.size], total - off)
                    p++
                    for (v in stream.push(input.copyOfRange(off, off + take))) out.add(v)
                    off += take
                }
                for (v in stream.flush()) out.add(v)
                return FloatArray(out.size) { out[it] }
            }

            val ref = runWith(null)
            val fixedOut = runWith(VoxLockQnn.FIXED_FRAMES)
            assertEquals("길이 $total: 가변 러너 출력 길이 일치", total, ref.size)
            assertEquals("길이 $total: 고정 러너 출력 길이 일치", total, fixedOut.size)

            var maxDiff = 0f
            for (i in 0 until total) maxDiff = max(maxDiff, abs(ref[i] - fixedOut[i]))
            assertTrue("길이 $total: 가변·고정 러너 출력 차이가 1e-4 이하여야 함 (maxDiff=$maxDiff)", maxDiff < 1e-4f)

            var maxInputErr = 0f
            for (i in 16 until total - 16) maxInputErr = max(maxInputErr, abs(input[i] - fixedOut[i]))
            assertTrue("길이 $total: 내부 복원 오차가 1e-4 이하여야 함 (maxErr=$maxInputErr)", maxInputErr < 1e-4f)
        }
    }

    @Test fun `voice lock active files reflect npu support and remain covered by deletion allowlist`() {
        val sm8850 = VoiceLockManager.activeFileNamesFor(NpuSupport.SM8850)
        val sm8750 = VoiceLockManager.activeFileNamesFor(NpuSupport.SM8750)
        val cpu = VoiceLockManager.activeFileNamesFor(null)

        assertEquals(
            listOf(VoiceLockManager.CAMPPLUS_FILE, VoiceLockManager.ENROLL_FILE, "voxlock_chunk_qnn_sm8850.bin"),
            sm8850,
        )
        assertEquals(
            listOf(VoiceLockManager.CAMPPLUS_FILE, VoiceLockManager.ENROLL_FILE, "voxlock_chunk_qnn_sm8750.bin"),
            sm8750,
        )
        assertEquals(
            listOf(VoiceLockManager.CAMPPLUS_FILE, VoiceLockManager.ENROLL_FILE, VoiceLockManager.CHUNK_FILE),
            cpu,
        )
        for (name in sm8850 + sm8750 + cpu) {
            assertTrue("$name 은(는) MODEL_FILE_NAMES 에 포함되어야 함", name in VoiceLockManager.MODEL_FILE_NAMES)
        }
    }
}
