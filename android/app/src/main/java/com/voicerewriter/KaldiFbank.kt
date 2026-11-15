package com.voicerewriter

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 3D-Speaker CAM++ / WeSpeaker용 Kaldi 호환 80채널 Fbank 특징 추출기 (순수 Kotlin).
 *
 * `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx` 모델 메타데이터
 * (`sample_rate=16000`, `feature_normalize_type=global-mean`) 및 `sherpa-onnx`의
 * `kaldi-native-fbank` 출력과 일치하도록 구성됐다 (`highFreq=7600`, Povey 창, CMN 포함).
 */
object KaldiFbank {
    const val SAMPLE_RATE = 16_000
    const val FRAME_LEN = 400 // 25 ms
    const val FRAME_SHIFT = 160 // 10 ms
    const val FFT_SIZE = 512
    const val NUM_BINS = 80
    private const val PREEMPH = 0.97f
    private const val LOW_FREQ = 20.0
    private const val HIGH_FREQ = 7600.0
    private const val ENERGY_FLOOR = 1.1920929e-7f // FLT_EPSILON

    private val poveyWindow = FloatArray(FRAME_LEN) { i ->
        val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (FRAME_LEN - 1).toDouble())
        hann.pow(0.85).toFloat()
    }

    // FFT용 비트 반전 및 회전 인자 테이블 (512-pt 고정 크기)
    private val bitRev = IntArray(FFT_SIZE).apply {
        var j = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            this[i] = j
        }
    }

    private val twiddleCos = FloatArray(FFT_SIZE / 2) { k -> cos(-2.0 * PI * k / FFT_SIZE).toFloat() }
    private val twiddleSin = FloatArray(FFT_SIZE / 2) { k -> sin(-2.0 * PI * k / FFT_SIZE).toFloat() }

    // 80 x 256 Mel 필터뱅크 가중치 (bin 0..255, 나이퀴스트 256은 0)
    private val melFilters: Array<FloatArray> = buildMelFilters()

    private fun hzToMel(hz: Double): Double = 1127.0 * ln(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (exp(mel / 1127.0) - 1.0)

    private fun buildMelFilters(): Array<FloatArray> {
        val melLow = hzToMel(LOW_FREQ)
        val melHigh = hzToMel(HIGH_FREQ)
        val binFreq = SAMPLE_RATE.toDouble() / FFT_SIZE
        val centerFreqs = DoubleArray(NUM_BINS + 2) { m ->
            val mel = melLow + (melHigh - melLow) * m / (NUM_BINS + 1)
            melToHz(mel)
        }
        val half = FFT_SIZE / 2 // 256
        return Array(NUM_BINS) { m ->
            val left = centerFreqs[m]
            val center = centerFreqs[m + 1]
            val right = centerFreqs[m + 2]
            FloatArray(half) { k ->
                val f = k * binFreq
                val up = (f - left) / (center - left)
                val down = (right - f) / (right - center)
                max(0.0, min(up, down)).toFloat()
            }
        }
    }

    /** 입력 샘플 길이 [numSamples]에서 추출되는 Fbank 프레임 수 (`snipEdges = true`). */
    fun frameCount(numSamples: Int): Int =
        if (numSamples < FRAME_LEN) 0 else 1 + (numSamples - FRAME_LEN) / FRAME_SHIFT

    /**
     * 정규화된 16kHz PCM `[offset, offset + length)`를 `[T, 80]` row-major Fbank 배열로 변환한다.
     * 전역 평균 차감(CMN)까지 적용하며, 길이가 400 샘플 미만이면 빈 배열을 반환한다.
     */
    fun compute(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): FloatArray {
        val safeOff = offset.coerceIn(0, samples.size)
        val safeLen = length.coerceIn(0, samples.size - safeOff)
        val numFrames = frameCount(safeLen)
        if (numFrames <= 0) return FloatArray(0)

        val out = FloatArray(numFrames * NUM_BINS)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val power = FloatArray(FFT_SIZE / 2)
        val mean = DoubleArray(NUM_BINS)

        for (t in 0 until numFrames) {
            val start = safeOff + t * FRAME_SHIFT
            var dcSum = 0.0
            for (i in 0 until FRAME_LEN) dcSum += samples[start + i]
            val dc = (dcSum / FRAME_LEN).toFloat()

            // DC 제거 + pre-emphasis + Povey 창 한 번에 준비 (FFT 버퍼 뒷부분 400..511은 0)
            re.fill(0f)
            im.fill(0f)
            var prev = samples[start] - dc
            re[0] = prev * (1.0f - PREEMPH) * poveyWindow[0]
            for (i in 1 until FRAME_LEN) {
                val cur = samples[start + i] - dc
                re[i] = (cur - PREEMPH * prev) * poveyWindow[i]
                prev = cur
            }

            fft512(re, im)

            val half = FFT_SIZE / 2
            for (k in 0 until half) {
                power[k] = re[k] * re[k] + im[k] * im[k]
            }

            val rowOff = t * NUM_BINS
            for (m in 0 until NUM_BINS) {
                val w = melFilters[m]
                var dot = 0f
                for (k in 0 until half) {
                    val wk = w[k]
                    if (wk != 0f) dot += power[k] * wk
                }
                val logE = ln(max(dot, ENERGY_FLOOR))
                out[rowOff + m] = logE
                mean[m] += logE.toDouble()
            }
        }

        // 채널별 평균 차감 (CMN)
        for (m in 0 until NUM_BINS) mean[m] /= numFrames.toDouble()
        for (t in 0 until numFrames) {
            val rowOff = t * NUM_BINS
            for (m in 0 until NUM_BINS) {
                out[rowOff + m] -= mean[m].toFloat()
            }
        }
        return out
    }

    /** 512-pt 제자리(in-place) 반복형 Cooley-Tukey FFT. */
    private fun fft512(re: FloatArray, im: FloatArray) {
        for (i in 0 until FFT_SIZE) {
            val j = bitRev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= FFT_SIZE) {
            val half = len shr 1
            val step = FFT_SIZE / len
            var i = 0
            while (i < FFT_SIZE) {
                var tw = 0
                for (j in 0 until half) {
                    val wr = twiddleCos[tw]
                    val wi = twiddleSin[tw]
                    val idx1 = i + j
                    val idx2 = idx1 + half
                    val tr = re[idx2] * wr - im[idx2] * wi
                    val ti = re[idx2] * wi + im[idx2] * wr
                    re[idx2] = re[idx1] - tr
                    im[idx2] = im[idx1] - ti
                    re[idx1] += tr
                    im[idx1] += ti
                    tw += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
