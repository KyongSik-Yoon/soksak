package com.voicerewriter

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import java.io.File

/**
 * VoxLock v2 고정 청크(K=25, 200ms) 모델을 Qualcomm QNN HTP (`libQnnHtp.so`, QAIRT 2.40)에서
 * 직접 실행하는 JNI 래퍼. `onnxruntime-android-qnn` 대신 앱에 이미 포함된 `qnn-runtime:2.40.0`과
 * `libvoxlock_qnn.so`를 사용해 QAIRT SDK 버전을 그대로 정렬한다.
 */
object VoxLockQnn {
    private const val TAG = "VoxLockQnn"
    const val FIXED_FRAMES = 25
    const val FIXED_SEG_LEN = (FIXED_FRAMES - 1) * VoxLockEngine.HOP + VoxLockEngine.NFFT // 3584

    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("voxlock_qnn")
            true
        } catch (t: Throwable) {
            Log.i(TAG, "libvoxlock_qnn unavailable, using CPU fallback: ${t.message}")
            false
        }
    }

    /** 프로세스 크래시 등으로 `.npu-loading`/`.npu-failed`가 남았을 때 사용자 수동 토글 시 초기화한다. */
    fun resetGuard(modelsDir: File) {
        val guardDir = File(modelsDir, "qnn-guard")
        File(guardDir, ".npu-loading").delete()
        File(guardDir, ".npu-failed").delete()
    }

    fun open(context: Context, binFile: File): Model? {
        if (!isAvailable || !binFile.isFile || binFile.length() == 0L) return null
        val parent = binFile.parentFile ?: return null
        val guardDir = File(parent, "qnn-guard").apply { mkdirs() }
        val guard = NpuLoadGuard(guardDir, AppUpdater.installedVersionCode(context))
        try {
            guard.begin()
        } catch (e: IllegalStateException) {
            Log.w(TAG, e.message ?: "VoxLock NPU blocked by load guard")
            return null
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        runCatching { OfflineRecognizer.prependAdspLibraryPath(nativeLibDir) }
        runCatching { nativePrependAdspPath(nativeLibDir) }

        val handle = try {
            nativeOpen(binFile.absolutePath)
        } finally {
            guard.end()
        }
        if (handle == 0L) {
            Log.w(TAG, "nativeOpen returned 0 for ${binFile.name}")
            return null
        }
        return Model(handle)
    }

    class Model internal constructor(handle: Long) {
        private val lock = Any()
        @Volatile private var nativeHandle = handle

        fun createRunner(voice: VoxLockEngine.VoiceTensors): VoxLockEngine.ChunkRunner? {
            val bound = synchronized(lock) {
                val hdl = nativeHandle
                if (hdl == 0L) false
                else nativeBindStream(hdl, voice.enrollN, voice.k, voice.v, true)
            }
            if (!bound) return null

            return object : VoxLockEngine.ChunkRunner() {
                override val fixedFrames: Int get() = FIXED_FRAMES
                private var ended = false
                private var loggedFailure = false

                override fun run(
                    seg: FloatArray,
                    kFrames: Int,
                    h: FloatArray,
                    c: FloatArray,
                    outFrames: FloatArray,
                ) {
                    if (ended || kFrames != FIXED_FRAMES || seg.size != FIXED_SEG_LEN) {
                        passthrough(seg, kFrames, outFrames)
                        return
                    }
                    val rc = synchronized(lock) {
                        val hdl = nativeHandle
                        if (hdl == 0L) -1 else nativeStep(hdl, seg, outFrames)
                    }
                    if (rc != 0) {
                        if (!loggedFailure) {
                            loggedFailure = true
                            Log.e(TAG, "nativeStep failed (rc=$rc); passing audio through untouched")
                        }
                        passthrough(seg, kFrames, outFrames)
                    }
                }

                override fun close() {
                    if (ended) return
                    ended = true
                    synchronized(lock) {
                        val hdl = nativeHandle
                        if (hdl != 0L) nativeEndStream(hdl)
                    }
                }
            }
        }

        fun close() {
            synchronized(lock) {
                val hdl = nativeHandle
                if (hdl != 0L) {
                    nativeHandle = 0L
                    nativeClose(hdl)
                }
            }
        }
    }

    private fun passthrough(seg: FloatArray, kFrames: Int, outFrames: FloatArray) {
        val maxFrames = if (seg.size >= VoxLockEngine.NFFT) 1 + (seg.size - VoxLockEngine.NFFT) / VoxLockEngine.HOP else 0
        val usable = minOf(kFrames, maxFrames)
        for (j in 0 until usable) {
            val baseSeg = j * VoxLockEngine.HOP
            val baseOut = j * VoxLockEngine.NFFT
            for (i in 0 until VoxLockEngine.NFFT) {
                outFrames[baseOut + i] = seg[baseSeg + i] * (VoxLockEngine.WIN2[i] / VoxLockEngine.GAIN)
            }
        }
    }

    @JvmStatic private external fun nativePrependAdspPath(nativeLibDir: String)
    @JvmStatic private external fun nativeOpen(binPath: String): Long
    @JvmStatic private external fun nativeBindStream(
        handle: Long,
        enrollN: FloatArray,
        k: FloatArray,
        v: FloatArray,
        burstPerf: Boolean,
    ): Boolean
    @JvmStatic private external fun nativeStep(handle: Long, seg: FloatArray, outFrames: FloatArray): Int
    @JvmStatic private external fun nativeEndStream(handle: Long)
    @JvmStatic private external fun nativeClose(handle: Long)
}
