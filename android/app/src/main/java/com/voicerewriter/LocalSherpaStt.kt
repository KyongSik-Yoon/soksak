package com.voicerewriter

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.whispercpp.whisper.WhisperCpuConfig
import java.io.BufferedInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * On-device STT through sherpa-onnx for SenseVoice-Small: much lighter than Whisper on a phone,
 * at the cost of no prompt/glossary biasing (the dictionary still applies after decoding).
 * (Moonshine Korean ran here too until 2026-11-09; removed for being weak in noise.)
 *
 * Models ship from sherpa-onnx's public `asr-models` release as tar.bz2 bundles: downloaded
 * with [ModelDownloader] (resume, size check), unpacked into `models/<bundle>/`, archive deleted.
 */
object LocalSherpaStt {

    private const val TAG = "LocalSherpaStt"

    /** Files a bundle must contain to be usable. */
    fun requiredFiles(m: WhisperModelManager.WhisperModel): List<String> = when (m.engine) {
        SpeechEngine.SENSE_VOICE -> listOf(m.onnxFile, "tokens.txt")
        SpeechEngine.WHISPER -> emptyList()
    }

    fun isReady(dir: File, m: WhisperModelManager.WhisperModel): Boolean =
        dir.isDirectory && requiredFiles(m).all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }

    fun archiveFile(dir: File): File = File(dir.parentFile, "${dir.name}.tar.bz2")

    /** Download the bundle tarball and unpack the files [m] needs. Progress: 0..0.9 fetch, 0.9..1 unpack. */
    suspend fun download(url: String, dir: File, m: WhisperModelManager.WhisperModel, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val archive = archiveFile(dir)
            ModelDownloader.fetch(url, archive) { p -> onProgress(p * 0.9f) }
            val tmp = File(dir.parentFile, "${dir.name}.unpack").apply { deleteRecursively(); mkdirs() }
            val wanted = requiredFiles(m).toSet()
            try {
                TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/')
                        if (entry.isDirectory || name !in wanted) continue // skip test_wavs, docs
                        File(tmp, name).outputStream().use { tar.copyTo(it, 1 shl 16) }
                    }
                }
                check(isReady(tmp, m)) { "The model archive is missing files." }
                dir.deleteRecursively()
                check(tmp.renameTo(dir)) { "Couldn't save the model." }
                archive.delete()
                onProgress(1f)
            } finally {
                if (tmp.exists()) tmp.deleteRecursively()
            }
        }

    fun delete(dir: File): Long {
        val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        dir.deleteRecursively()
        return size + ModelDownloader.deleteWithSidecars(archiveFile(dir))
    }

    // --- recognition ---

    private val lock = Mutex()
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedId: String? = null

    private fun build(dir: File, m: WhisperModelManager.WhisperModel, language: String): OfflineRecognizer {
        val model = when (m.engine) {
            SpeechEngine.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(dir, m.onnxFile).absolutePath,
                    // SenseVoice knows zh/en/ja/ko/yue; anything else lets it detect.
                    language = language.takeIf { it in setOf("zh", "en", "ja", "ko", "yue") } ?: "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = WhisperCpuConfig.preferredThreadCount,
            )
            SpeechEngine.WHISPER -> error("Whisper runs in LocalWhisperStt")
        }
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80), modelConfig = model),
        )
    }

    private fun ensureLoaded(context: Context, id: String, language: String): OfflineRecognizer {
        val key = "$id|$language"
        recognizer?.takeIf { loadedId == key }?.let { return it }
        recognizer?.release()
        recognizer = null
        val m = WhisperModelManager.model(id)
        val dir = WhisperModelManager.modelFile(context, id)
        check(isReady(dir, m)) { "${m.label} isn't downloaded yet." }
        val t0 = System.currentTimeMillis()
        return build(dir, m, language).also {
            recognizer = it
            loadedId = key
            Log.i(TAG, "loaded $id in ${System.currentTimeMillis() - t0}ms")
        }
    }

    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray): String =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val rec = ensureLoaded(context, settings.sttModel, DictationLanguage.normalize(settings.sttLanguage))
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, 16_000)
                    rec.decode(stream)
                    clean(rec.getResult(stream).text)
                } finally {
                    stream.release()
                }
            }
        }

    suspend fun warm(context: Context, settings: Settings) = withContext(Dispatchers.Default) {
        runCatching { lock.withLock { ensureLoaded(context, settings.sttModel, DictationLanguage.normalize(settings.sttLanguage)) } }
            .onFailure { Log.w(TAG, "warm failed", it) }
    }

    /**
     * SentencePiece word markers ("▁조국이 …") and SenseVoice's stray language/emotion tags
     * don't belong in a text field.
     */
    internal fun clean(raw: String): String =
        raw.replace('▁', ' ')
            .replace(Regex("<\\|[^|>]*\\|>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
