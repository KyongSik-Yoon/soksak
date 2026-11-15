package com.voicerewriter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * 내 목소리 학습 프로필과 온디바이스 화자 게이트/음성 분리 모델을 관리한다.
 *
 * 모델:
 * - `campplus.onnx` (~27MB, Apache-2.0): 평상시 초경량 CPU 화자 게이팅
 * - `voxlock_enroll.onnx` (~21MB, Apache-2.0): 내 목소리 등록 텐서 생성 (등록 시 1회 실행)
 * - Snapdragon QNN 지원 기기([NpuSupport]): `voxlock_chunk_qnn_<soc>.bin` (~45MB, Apache-2.0) — NPU 수동 분리
 * - 기타 기기(CPU 폴백): `voxlock_chunk_int8.onnx` (~57MB, Apache-2.0) — CPU 수동 분리
 */
object VoiceLockManager {
    private const val TAG = "VoiceLockManager"

    const val CAMPPLUS_FILE = "campplus.onnx"
    const val ENROLL_FILE = "voxlock_enroll.onnx"
    const val CHUNK_FILE = "voxlock_chunk_int8.onnx"

    private const val PROFILE_EMB_FILE = "speaker_emb.bin"
    private const val PROFILE_VOX_FILE = "voxlock_voice.bin"
    private const val PROFILE_RAW_FILE = "enroll_raw.wav"

    private const val MIN_MODEL_BYTES = 1_000_000L
    private const val MAGIC_EMB = 0x534B4531 // "SKE1"

    fun qnnChunkFileName(soc: String): String = "voxlock_chunk_qnn_${soc.lowercase()}.bin"

    val MODEL_FILE_NAMES = listOf(
        CAMPPLUS_FILE,
        ENROLL_FILE,
        CHUNK_FILE,
        qnnChunkFileName(NpuSupport.SM8850),
        qnnChunkFileName(NpuSupport.SM8750),
    )

    private data class RemoteAsset(val fileName: String, val url: String, val approxBytes: Long)

    private val BASE_ASSETS = listOf(
        RemoteAsset(
            CAMPPLUS_FILE,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
            27_000_000L,
        ),
        RemoteAsset(
            ENROLL_FILE,
            "https://huggingface.co/arbazsiddiqui/voxlock/resolve/2413b292bc2c337bfc19a9802aaf3e011e68e9c4/onnx/v2/enroll.onnx",
            21_000_000L,
        ),
    )

    private val CPU_CHUNK_ASSET = RemoteAsset(
        CHUNK_FILE,
        "https://huggingface.co/arbazsiddiqui/voxlock/resolve/2413b292bc2c337bfc19a9802aaf3e011e68e9c4/onnx/v2/chunk_int8.onnx",
        57_000_000L,
    )

    private fun qnnChunkAsset(soc: String): RemoteAsset {
        val name = qnnChunkFileName(soc)
        return RemoteAsset(
            name,
            "https://github.com/${AppUpdater.REPO}/releases/download/model-qnn-voxlock-${soc.lowercase()}/$name",
            45_000_000L,
        )
    }

    private fun activeAssets(soc: String? = NpuSupport.soc): List<RemoteAsset> =
        BASE_ASSETS + if (soc != null) qnnChunkAsset(soc) else CPU_CHUNK_ASSET

    internal fun activeFileNamesFor(soc: String?): List<String> =
        activeAssets(soc).map { it.fileName }

    private val modelLock = Any()
    @Volatile private var cachedEmbedder: CampPlusEmbedder? = null
    @Volatile private var cachedChunkModel: VoxLockEngine.ChunkModel? = null
    @Volatile private var cachedQnnModel: VoxLockQnn.Model? = null
    @Volatile private var cachedEmbedding: FloatArray? = null
    @Volatile private var cachedVoice: VoxLockEngine.VoiceTensors? = null

    fun modelsDir(context: Context): File =
        File(File(context.filesDir, "models"), "voicelock").apply { mkdirs() }

    fun profileDir(context: Context): File =
        File(context.filesDir, "voicelock").apply { mkdirs() }

    fun modelFile(context: Context, name: String): File = File(modelsDir(context), name)

    fun isModelsReady(context: Context): Boolean =
        activeAssets().all { asset ->
            val f = modelFile(context, asset.fileName)
            f.isFile && f.length() > MIN_MODEL_BYTES
        }

    fun hasProfile(context: Context): Boolean {
        val dir = profileDir(context)
        val emb = File(dir, PROFILE_EMB_FILE)
        val vox = File(dir, PROFILE_VOX_FILE)
        return emb.isFile && emb.length() >= 8L + CampPlusEmbedder.EMBEDDING_DIM * 4L &&
            vox.isFile && vox.length() > 1_000_000L
    }

    fun canGate(context: Context): Boolean = isModelsReady(context) && hasProfile(context)

    fun canIsolate(context: Context): Boolean = isModelsReady(context) && hasProfile(context)

    /** 기기에 맞는 Voice Lock 모델(3종)을 순차 다운로드하고 총합 진행률(0..1)을 보고한다. */
    suspend fun downloadModels(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (isModelsReady(context)) {
            onProgress(1f)
            return@withContext
        }
        val assets = activeAssets()
        val totalBytes = assets.sumOf { it.approxBytes }.toDouble()
        var doneBytes = 0.0
        for (asset in assets) {
            val target = modelFile(context, asset.fileName)
            if (target.isFile && target.length() > MIN_MODEL_BYTES) {
                doneBytes += asset.approxBytes
                onProgress((doneBytes / totalBytes).toFloat().coerceIn(0f, 1f))
                continue
            }
            ModelDownloader.fetch(asset.url, target) { p ->
                val cur = (doneBytes + p * asset.approxBytes) / totalBytes
                onProgress(cur.toFloat().coerceIn(0f, 1f))
            }
            doneBytes += asset.approxBytes
            onProgress((doneBytes / totalBytes).toFloat().coerceIn(0f, 1f))
        }
    }

    /** 모델 및 다운로드 잔여 사이드카를 삭제하고 캐시 세션을 닫는다. */
    suspend fun deleteModels(context: Context): Long = withContext(Dispatchers.IO) {
        closeSessions()
        var freed = 0L
        for (name in MODEL_FILE_NAMES) {
            freed += ModelDownloader.deleteWithSidecars(modelFile(context, name))
        }
        runCatching { File(modelsDir(context), "qnn-guard").deleteRecursively() }
        freed
    }

    /** 등록된 내 목소리 데이터(임베딩, VoxLock 텐서, 원본 녹음)를 지운다. */
    fun clearProfile(context: Context) {
        synchronized(modelLock) {
            cachedEmbedding = null
            cachedVoice = null
        }
        val dir = profileDir(context)
        listOf(PROFILE_EMB_FILE, PROFILE_VOX_FILE, PROFILE_RAW_FILE).forEach { name ->
            runCatching { File(dir, name).delete() }
        }
    }

    /**
     * 사용자가 읽은 내 목소리 PCM16 녹음으로 CAM++ 임베딩과 VoxLock 조건 텐서를 함께 등록한다.
     * 유성 구간이 3.0초 미만이면 [IllegalArgumentException]을 던진다.
     */
    suspend fun enrollFromSamples(context: Context, samples: ShortArray) = withContext(Dispatchers.IO) {
        check(isModelsReady(context)) { "Voice lock models are not downloaded yet." }
        require(samples.size >= VoxLockEngine.SR * 3 + VoxLockEngine.SR / 5) {
            "Recording is too short. Please speak for at least 4 seconds."
        }
        val floats = WavIo.toFloats(samples)

        // Silero VAD 별도 인스턴스로 순수 말소리만 모아 3초 이상 발화했는지 확인한다.
        val vad = SileroVad.create(context)
        val voicedList = ArrayList<Float>(floats.size)
        if (vad != null) {
            try {
                var off = 0
                while (off + SileroVad.CHUNK <= floats.size) {
                    val frame = floats.copyOfRange(off, off + SileroVad.CHUNK)
                    val prob = runCatching { vad.process(frame) }.getOrDefault(0f)
                    if (prob >= SpeakerGate.SPEECH_END_PROB) {
                        for (x in frame) voicedList.add(x)
                    }
                    off += SileroVad.CHUNK
                }
            } finally {
                vad.close()
            }
        } else {
            // VAD 로드 실패 폴백: 20ms 에너지 상위 구간만 남김
            var off = 0
            while (off + 320 <= floats.size) {
                var s = 0.0
                for (i in 0 until 320) {
                    val v = floats[off + i].toDouble()
                    s += v * v
                }
                if (sqrt(s / 320.0) >= 0.005) {
                    for (i in 0 until 320) voicedList.add(floats[off + i])
                }
                off += 320
            }
        }

        val voiced = voicedList.toFloatArray()
        require(voiced.size >= VoxLockEngine.SAMPLE) {
            "Not enough clear speech captured (need at least 3 seconds of your voice). Please try again in a quiet spot."
        }

        val embedder = acquireEmbedder(context)
            ?: throw IllegalStateException("Could not initialize speaker embedding model.")
        val emb = embedder.embed(voiced)
            ?: throw IllegalStateException("Could not extract your voice signature. Please speak a little louder.")

        val fitted = VoxLockEngine.fitSample(voiced)
        val voiceTensors = VoxLockEngine.computeEnrollment(modelFile(context, ENROLL_FILE), fitted)

        val dir = profileDir(context)
        writeEmbedding(File(dir, PROFILE_EMB_FILE), emb)
        voiceTensors.writeTo(File(dir, PROFILE_VOX_FILE))
        WavIo.write(File(dir, PROFILE_RAW_FILE), samples)

        synchronized(modelLock) {
            cachedEmbedding = emb
            cachedVoice = voiceTensors
        }
        Log.i(TAG, "enrolled voice profile (${voiced.size / VoxLockEngine.SR.toFloat()}s speech)")
    }

    fun getEnrolledEmbedding(context: Context): FloatArray? {
        cachedEmbedding?.let { return it }
        val loaded = readEmbedding(File(profileDir(context), PROFILE_EMB_FILE)) ?: return null
        synchronized(modelLock) { cachedEmbedding = loaded }
        return loaded
    }

    fun getVoiceTensors(context: Context): VoxLockEngine.VoiceTensors? {
        cachedVoice?.let { return it }
        val loaded = VoxLockEngine.VoiceTensors.readFrom(File(profileDir(context), PROFILE_VOX_FILE)) ?: return null
        synchronized(modelLock) { cachedVoice = loaded }
        return loaded
    }

    fun acquireEmbedder(context: Context): CampPlusEmbedder? {
        cachedEmbedder?.let { return it }
        synchronized(modelLock) {
            cachedEmbedder?.let { return it }
            val loaded = CampPlusEmbedder.open(modelFile(context, CAMPPLUS_FILE))
            cachedEmbedder = loaded
            return loaded
        }
    }

    /** 수동 토글 시 일시적인 NPU 로드 가드를 초기화한다. */
    fun resetNpuGuard(context: Context) {
        VoxLockQnn.resetGuard(modelsDir(context))
    }

    fun openVoxLockStream(context: Context): VoxLockEngine.Stream? {
        val voice = getVoiceTensors(context) ?: return null
        val soc = NpuSupport.soc
        if (soc != null && VoxLockQnn.isAvailable) {
            val qnnFile = modelFile(context, qnnChunkFileName(soc))
            if (qnnFile.isFile && qnnFile.length() > MIN_MODEL_BYTES) {
                val qnnModel = cachedQnnModel ?: synchronized(modelLock) {
                    cachedQnnModel ?: VoxLockQnn.open(context, qnnFile)?.also { cachedQnnModel = it }
                }
                val runner = qnnModel?.createRunner(voice)
                if (runner != null) return VoxLockEngine.Stream(runner)
            }
        }
        val cpuFile = modelFile(context, CHUNK_FILE)
        if (!cpuFile.isFile || cpuFile.length() <= MIN_MODEL_BYTES) return null
        val model = cachedChunkModel ?: synchronized(modelLock) {
            cachedChunkModel ?: VoxLockEngine.ChunkModel.open(cpuFile)
                ?.also { cachedChunkModel = it }
        } ?: return null
        return VoxLockEngine.Stream(model.createRunner(voice))
    }

    fun closeSessions() {
        synchronized(modelLock) {
            cachedEmbedder?.close()
            cachedEmbedder = null
            cachedQnnModel?.close()
            cachedQnnModel = null
            cachedChunkModel?.close()
            cachedChunkModel = null
        }
    }

    private fun writeEmbedding(file: File, emb: FloatArray) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        java.io.DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC_EMB)
            out.writeInt(emb.size)
            for (f in emb) out.writeInt(java.lang.Float.floatToRawIntBits(f))
            out.flush()
        }
        if (file.exists()) file.delete()
        check(tmp.renameTo(file))
    }

    private fun readEmbedding(file: File): FloatArray? {
        if (!file.isFile || file.length() < 8L + CampPlusEmbedder.EMBEDDING_DIM * 4L) return null
        return runCatching {
            java.io.DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MAGIC_EMB) return@runCatching null
                val len = inp.readInt()
                if (len != CampPlusEmbedder.EMBEDDING_DIM) return@runCatching null
                FloatArray(len) { java.lang.Float.intBitsToFloat(inp.readInt()) }
            }
        }.getOrNull()
    }
}
