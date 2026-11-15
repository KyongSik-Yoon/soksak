package com.voicerewriter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * VoxLock v2 온디바이스 실시간 타겟 화자 분리 엔진 (`arbazsiddiqui/voxlock`, Apache-2.0).
 *
 * 파이썬 레퍼런스 `voxlock/core.py` (v1.3.0)의 `Stream`·`fit_sample`·`loud_sample` 수치와
 * STFT 중심 반사 패딩, `WIN2` Overlap-Add, `GAIN = -0.5f` 복원을 1:1로 맞춘 구현이다.
 */
object VoxLockEngine {
    const val SR = 16_000
    const val HOP = 128
    const val NFFT = 512
    const val PAD = 256
    const val GAIN = -0.5f
    const val SAMPLE = 3 * SR // 48,000 samples (3.0s)

    private const val ENROLL_N_SIZE = 257 * 376 // 96,632 floats
    private const val KV_SIZE = 32 * 2 * 64 * 298 // 1,220,608 floats each
    private const val STATE_SIZE = 6 * 32 * 256 // 49,152 floats each
    private const val VOICE_MAGIC = 0x564C5632 // "VLV2"
    private const val TAG = "VoxLockEngine"

    /** Periodic Hann^2 합성 창 (voxlock `WIN2`). */
    val WIN2: FloatArray = FloatArray(NFFT) { i ->
        val w = 0.5 - 0.5 * cos(2.0 * PI * i / NFFT.toDouble())
        (w * w).toFloat()
    }

    /** `enroll.onnx` 실행 결과로 캐시되는 화자 조건 텐서. */
    class VoiceTensors(
        val enrollN: FloatArray,
        val k: FloatArray,
        val v: FloatArray,
    ) {
        init {
            require(enrollN.size == ENROLL_N_SIZE) { "invalid enroll_n size: ${enrollN.size}" }
            require(k.size == KV_SIZE) { "invalid k size: ${k.size}" }
            require(v.size == KV_SIZE) { "invalid v size: ${v.size}" }
        }

        fun writeTo(file: File) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 65536)).use { out ->
                out.writeInt(VOICE_MAGIC)
                out.writeInt(enrollN.size)
                out.writeInt(k.size)
                out.writeInt(v.size)
                for (f in enrollN) out.writeInt(java.lang.Float.floatToRawIntBits(f))
                for (f in k) out.writeInt(java.lang.Float.floatToRawIntBits(f))
                for (f in v) out.writeInt(java.lang.Float.floatToRawIntBits(f))
                out.flush()
            }
            if (file.exists()) file.delete()
            check(tmp.renameTo(file)) { "failed to finalize ${file.name}" }
        }

        companion object {
            fun readFrom(file: File): VoiceTensors? {
                if (!file.isFile || file.length() < 16L + (ENROLL_N_SIZE + KV_SIZE * 2L) * 4L) return null
                return runCatching {
                    DataInputStream(BufferedInputStream(FileInputStream(file), 65536)).use { inp ->
                        if (inp.readInt() != VOICE_MAGIC) return@runCatching null
                        val nLen = inp.readInt()
                        val kLen = inp.readInt()
                        val vLen = inp.readInt()
                        if (nLen != ENROLL_N_SIZE || kLen != KV_SIZE || vLen != KV_SIZE) return@runCatching null
                        val en = FloatArray(nLen) { java.lang.Float.intBitsToFloat(inp.readInt()) }
                        val kk = FloatArray(kLen) { java.lang.Float.intBitsToFloat(inp.readInt()) }
                        val vv = FloatArray(vLen) { java.lang.Float.intBitsToFloat(inp.readInt()) }
                        VoiceTensors(en, kk, vv)
                    }
                }.getOrNull()
            }
        }
    }

    /** 프레임 합성 러너 (ORT/QNN 모델 또는 단위 테스트용 더미 러너 주입). */
    abstract class ChunkRunner {
        /** QNN 컨텍스트 바이너리처럼 입력 청크 프레임 수(K)가 고정된 러너면 해당 값을 반환한다. */
        open val fixedFrames: Int? get() = null

        abstract fun run(
            seg: FloatArray,
            kFrames: Int,
            h: FloatArray,
            c: FloatArray,
            outFrames: FloatArray,
        )

        open fun close() {}
    }

    /** 함수형 빌더 헬퍼 (테스트 및 간단 래핑용). */
    inline fun chunkRunner(
        fixedFrames: Int? = null,
        crossinline block: (seg: FloatArray, kFrames: Int, h: FloatArray, c: FloatArray, outFrames: FloatArray) -> Unit,
    ): ChunkRunner = object : ChunkRunner() {
        override val fixedFrames: Int? get() = fixedFrames
        override fun run(seg: FloatArray, kFrames: Int, h: FloatArray, c: FloatArray, outFrames: FloatArray) =
            block(seg, kFrames, h, c, outFrames)
    }

    /**
     * VoxLock 3초 등록 샘플 추출 (`fit_sample` / `loud_sample`).
     * 최소 [SAMPLE] (3.0s) 이상이어야 하며, 초과 시 첫 30초 내에서 가장 음량이 큰 3초 구간을 선택한다.
     */
    fun fitSample(sample: FloatArray): FloatArray {
        require(sample.size >= SAMPLE) {
            "the voice sample must be at least 3 s long, got ${"%.2f".format(sample.size / SR.toFloat())} s"
        }
        if (sample.size == SAMPLE) return sample
        val within = min(sample.size, 30 * SR)
        val n = within / 160
        val w = (3.0 * SR / 160).toInt() // 300
        if (n <= w) return sample.copyOfRange(0, SAMPLE)

        val e = DoubleArray(n) { blk ->
            val base = blk * 160
            var s = 0.0
            for (i in 0 until 160) {
                val x = sample[base + i].toDouble()
                s += x * x
            }
            s / 160.0
        }
        var winSum = 0.0
        for (i in 0 until w) winSum += e[i]
        var bestSum = winSum
        var bestIdx = 0
        for (i in 1..(n - w)) {
            winSum += e[i + w - 1] - e[i - 1]
            if (winSum > bestSum) {
                bestSum = winSum
                bestIdx = i
            }
        }
        val st = bestIdx * 160
        return sample.copyOfRange(st, st + SAMPLE)
    }

    /**
     * `enroll.onnx`에 3초(48,000) 샘플을 한 번 통과시켜 [VoiceTensors]를 생성한다.
     * 등록 완료 직후 세션은 닫고 결과 텐서만 디스크/메모리에 유지한다.
     */
    fun computeEnrollment(enrollOnnx: File, sample48k: FloatArray): VoiceTensors {
        val fitted = fitSample(sample48k)
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }
        env.createSession(enrollOnnx.absolutePath, opts).use { session ->
            val inp = OnnxTensor.createTensor(env, FloatBuffer.wrap(fitted), longArrayOf(1L, SAMPLE.toLong()))
            try {
                session.run(mapOf("enroll" to inp)).use { out ->
                    val en = FloatArray(ENROLL_N_SIZE)
                    val kk = FloatArray(KV_SIZE)
                    val vv = FloatArray(KV_SIZE)
                    (out[0] as OnnxTensor).floatBuffer.get(en)
                    (out[1] as OnnxTensor).floatBuffer.get(kk)
                    (out[2] as OnnxTensor).floatBuffer.get(vv)
                    return VoiceTensors(en, kk, vv)
                }
            } finally {
                inp.close()
            }
        }
    }

    /** `chunk_int8.onnx`를 감싸는 스트리밍 세션 핸들. */
    class ChunkModel private constructor(
        private val env: OrtEnvironment,
        private val session: OrtSession,
    ) {
        private val lock = Any()

        fun createRunner(voice: VoiceTensors): ChunkRunner {
            // 등록 텐서는 스트림 생명주기 동안 불변이므로 ORT 텐서 3개를 한 번만 만들어 재사용한다.
            val enTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.enrollN), longArrayOf(257L, 376L))
            val kTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.k), longArrayOf(32L, 2L, 64L, 298L))
            val vTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.v), longArrayOf(32L, 2L, 298L, 64L))

            return object : ChunkRunner() {
                override fun run(
                    seg: FloatArray,
                    kFrames: Int,
                    h: FloatArray,
                    c: FloatArray,
                    outFrames: FloatArray,
                ) {
                    synchronized(lock) {
                        val wavT = OnnxTensor.createTensor(env, FloatBuffer.wrap(seg), longArrayOf(1L, seg.size.toLong()))
                        val hT = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(6L, 32L, 256L))
                        val cT = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(6L, 32L, 256L))
                        try {
                            val feeds = mapOf(
                                "wav" to wavT,
                                "enroll_n" to enTensor,
                                "k" to kTensor,
                                "v" to vTensor,
                                "h" to hT,
                                "c" to cT,
                            )
                            session.run(feeds).use { out ->
                                (out[0] as OnnxTensor).floatBuffer.get(outFrames)
                                (out[1] as OnnxTensor).floatBuffer.get(h)
                                (out[2] as OnnxTensor).floatBuffer.get(c)
                            }
                        } finally {
                            wavT.close()
                            hT.close()
                            cT.close()
                        }
                    }
                }

                override fun close() {
                    runCatching { enTensor.close() }
                    runCatching { kTensor.close() }
                    runCatching { vTensor.close() }
                }
            }
        }

        fun close() {
            synchronized(lock) {
                runCatching { session.close() }
            }
        }

        companion object {
            fun open(chunkOnnx: File): ChunkModel? {
                if (!chunkOnnx.isFile || chunkOnnx.length() == 0L) return null
                return try {
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(2)
                        addConfigEntry("session.intra_op.allow_spinning", "0")
                    }
                    val session = env.createSession(chunkOnnx.absolutePath, opts)
                    Log.i(TAG, "loaded VoxLock chunk model; inputs=${session.inputNames}")
                    ChunkModel(env, session)
                } catch (e: Exception) {
                    Log.e(TAG, "failed to load VoxLock chunk model", e)
                    null
                }
            }
        }
    }

    /**
     * `voxlock.core.Stream` 스트리밍 래퍼.
     *
     * 임의 길이의 16kHz 청크를 [push]하면 24ms 지연 뒤 분리된 오디오가 반환되고,
     * [flush]를 호출하면 남은 샘플까지 반환해 총 출력 샘플 수 == 총 입력 샘플 수를 보장한다.
     */
    class Stream(private val runner: ChunkRunner) {
        private val h = FloatArray(STATE_SIZE)
        private val c = FloatArray(STATE_SIZE)
        private var head = FloatArray(0)
        private var buf: FloatArray? = null
        private var ola = FloatArray(NFFT)
        private var env = FloatArray(NFFT)
        private var dropped = 0
        private var nIn = 0
        private var nOut = 0

        fun push(chunk: FloatArray, framesPerRun: Int = 100): FloatArray {
            if (chunk.isEmpty()) return FloatArray(0)
            nIn += chunk.size
            if (buf == null) {
                head = concat(head, chunk)
                if (head.size <= PAD) return FloatArray(0)
                // torch.stft 중심 반사 패딩: head[PAD downTo 1] + head
                val reflect = FloatArray(PAD) { i -> head[PAD - i] }
                buf = concat(reflect, head)
                head = FloatArray(0)
                dropped = PAD
            } else {
                buf = concat(buf!!, chunk)
            }
            val y = runReady(framesPerRun)
            nOut += y.size
            return y
        }

        fun flush(): FloatArray {
            val cur = buf
            if (cur == null) {
                // 전체 입력이 PAD(256 샘플 = 16ms) 이하일 때
                val remaining = head
                head = FloatArray(0)
                nOut += remaining.size
                runner.close()
                return remaining
            }
            val refLen = min(PAD, max(0, cur.size - 1))
            val endPad = FloatArray(refLen) { i -> cur[cur.size - 2 - i] }
            var x = concat(cur, endPad)
            if (x.size < NFFT) x = x.copyOf(NFFT)
            val nfr = 1 + (x.size - NFFT) / HOP
            val needLen = (nfr - 1) * HOP + NFFT
            if (x.size < needLen) {
                x = x.copyOf(needLen)
            }
            buf = x
            val yReady = runReady(100)
            val remBuf = buf
            val fixed = runner.fixedFrames
            val yRem = if (fixed != null && remBuf != null && remBuf.size >= NFFT) {
                val rem = 1 + (remBuf.size - NFFT) / HOP
                val segLen = (fixed - 1) * HOP + NFFT
                val paddedSeg = remBuf.copyOf(segLen)
                val framesOut = FloatArray(fixed * NFFT)
                runner.run(paddedSeg, fixed, h, c, framesOut)
                olaStep(framesOut, rem, remBuf)
            } else {
                FloatArray(0)
            }
            val y1 = if (yRem.isEmpty()) yReady else concat(yReady, yRem)
            val tail = FloatArray(NFFT) { i ->
                val e = if (env[i] > 1e-11f) env[i] else 1.0f
                (ola[i] / e) * GAIN
            }
            val combined = concat(y1, tail)
            val needOut = max(0, nIn - nOut)
            val cutTail = if (combined.size > needOut) combined.copyOf(needOut) else combined
            nOut += cutTail.size
            buf = null
            runner.close()
            return cutTail
        }

        fun close() {
            runner.close()
        }

        private fun runReady(framesPerRun: Int): FloatArray {
            val chunksOut = ArrayList<FloatArray>()
            val fixed = runner.fixedFrames
            while (true) {
                val cur = buf ?: break
                if (cur.size < NFFT) break
                val avail = 1 + (cur.size - NFFT) / HOP
                if (fixed != null && avail < fixed) break
                val k = fixed ?: min(framesPerRun, avail)
                val segLen = (k - 1) * HOP + NFFT
                val seg = cur.copyOfRange(0, segLen)
                val framesOut = FloatArray(k * NFFT)
                runner.run(seg, k, h, c, framesOut)
                val emit = olaStep(framesOut, k, cur)
                if (emit.isNotEmpty()) chunksOut.add(emit)
            }
            if (chunksOut.isEmpty()) return FloatArray(0)
            if (chunksOut.size == 1) return chunksOut[0]
            val total = chunksOut.sumOf { it.size }
            val merged = FloatArray(total)
            var off = 0
            for (chunkOut in chunksOut) {
                chunkOut.copyInto(merged, off)
                off += chunkOut.size
            }
            return merged
        }

        private fun olaStep(framesOut: FloatArray, emitFrames: Int, cur: FloatArray): FloatArray {
            val done = emitFrames * HOP
            val olaWork = ola.copyOf(ola.size + done)
            val envWork = env.copyOf(env.size + done)
            for (j in 0 until emitFrames) {
                val baseOut = j * HOP
                val baseFr = j * NFFT
                for (idx in 0 until NFFT) {
                    olaWork[baseOut + idx] += framesOut[baseFr + idx]
                    envWork[baseOut + idx] += WIN2[idx]
                }
            }
            val yDone = FloatArray(done) { i ->
                val e = if (envWork[i] > 1e-11f) envWork[i] else 1.0f
                olaWork[i] / e
            }
            ola = olaWork.copyOfRange(done, olaWork.size)
            env = envWork.copyOfRange(done, envWork.size)
            buf = cur.copyOfRange(done, cur.size)

            val cut = min(dropped, yDone.size)
            dropped -= cut
            return FloatArray(yDone.size - cut) { i -> yDone[cut + i] * GAIN }
        }

        private fun concat(a: FloatArray, b: FloatArray): FloatArray {
            if (a.isEmpty()) return b.copyOf()
            if (b.isEmpty()) return a.copyOf()
            val out = FloatArray(a.size + b.size)
            a.copyInto(out, 0)
            b.copyInto(out, a.size)
            return out
        }
    }
}
