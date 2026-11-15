package com.voicerewriter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * 3D-Speaker CAM++ 화자 임베딩 추출기 (ONNX Runtime 기반).
 *
 * 16kHz 음성 조각에서 [KaldiFbank]로 80차원 특징을 뽑아 `"x": [1, T, 80]`으로 넣고
 * 192차원 L2 정규화 화자 벡터를 반환한다.
 */
class CampPlusEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) {
    companion object {
        private const val TAG = "CampPlusEmbedder"
        const val EMBEDDING_DIM = 192
        const val MIN_SAMPLES = 4000 // 0.25s 미만은 유효한 화자 임베딩 불가
        private const val MIN_RMS = 1e-4f
        private const val INTRA_THREADS = 2

        /** [modelFile]을 열어 ONNX 세션을 준비한다. 실패 시 null을 반환한다. */
        fun open(modelFile: File): CampPlusEmbedder? {
            if (!modelFile.isFile || modelFile.length() == 0L) return null
            return try {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(INTRA_THREADS)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                }
                val session = env.createSession(modelFile.absolutePath, opts)
                Log.i(TAG, "loaded CAM++; inputs=${session.inputNames} outputs=${session.outputNames}")
                CampPlusEmbedder(env, session)
            } catch (e: Exception) {
                Log.e(TAG, "failed to load CAM++ model", e)
                null
            }
        }

        /** L2 정규화된 두 화자 벡터의 코사인 유사도를 반환한다. */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return -1f
            var dot = 0.0
            for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
            return dot.toFloat().coerceIn(-1f, 1f)
        }

        /** 정규화하지 않은 두 벡터에도 안전하게 쓸 수 있는 코사인 유사도. */
        fun normalize(v: FloatArray): FloatArray? {
            var sumSq = 0.0
            for (x in v) sumSq += x.toDouble() * x.toDouble()
            val norm = sqrt(sumSq)
            if (norm < 1e-8) return null
            val inv = (1.0 / norm).toFloat()
            return FloatArray(v.size) { v[it] * inv }
        }
    }

    private val inferLock = Any()

    /**
     * 정규화 16kHz PCM `[offset, offset + length)`에서 192차원 L2 정규화 임베딩을 추출한다.
     * 무음·극단적 저음량·너무 짧은 구간이거나 추론이 실패하면 null을 반환한다.
     */
    fun embed(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): FloatArray? {
        val safeOff = offset.coerceIn(0, samples.size)
        val safeLen = length.coerceIn(0, samples.size - safeOff)
        if (safeLen < MIN_SAMPLES) return null

        var sumSq = 0.0
        for (i in safeOff until safeOff + safeLen) {
            val v = samples[i].toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / safeLen).toFloat()
        if (rms < MIN_RMS) return null

        val fbank = KaldiFbank.compute(samples, safeOff, safeLen)
        val frames = fbank.size / KaldiFbank.NUM_BINS
        if (frames <= 0) return null

        return synchronized(inferLock) {
            val tensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(fbank),
                longArrayOf(1L, frames.toLong(), KaldiFbank.NUM_BINS.toLong()),
            )
            try {
                session.run(mapOf("x" to tensor)).use { out ->
                    val outTensor = out[0] as OnnxTensor
                    val fb = outTensor.floatBuffer
                    val raw = FloatArray(EMBEDDING_DIM)
                    if (fb.remaining() < EMBEDDING_DIM) return@synchronized null
                    fb.get(raw)
                    normalize(raw)
                }
            } catch (e: Exception) {
                Log.w(TAG, "CAM++ inference failed", e)
                null
            } finally {
                tensor.close()
            }
        }
    }

    /** 등록 벡터 [enrolled]와 구간 `[offset, offset + length)`의 코사인 유사도를 반환한다. */
    fun scoreSimilarity(
        enrolled: FloatArray,
        samples: FloatArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
    ): Float {
        val emb = embed(samples, offset, length) ?: return -1f
        return cosine(enrolled, emb)
    }

    fun close() {
        synchronized(inferLock) {
            runCatching { session.close() }
        }
    }
}
