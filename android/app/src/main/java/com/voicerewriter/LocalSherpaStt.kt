package com.voicerewriter

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import com.voicerewriter.textproc.HallucinationFilter
import com.whispercpp.whisper.WhisperCpuConfig
import java.io.BufferedInputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * On-device STT through sherpa-onnx: Whisper on the Qualcomm NPU (QNN). No glossary biasing, since
 * every prompt token costs a decoder step per window (the dictionary still applies after decoding).
 *
 * Models ship as tar.bz2 bundles on this repo's private releases: downloaded with
 * [ModelDownloader] (resume, size check), unpacked into `models/<bundle>/`, archive deleted.
 */
object LocalSherpaStt {

    private const val TAG = "LocalSherpaStt"

    // QNN Whisper bundles (npu-model.yml): SM8850 context binaries plus the tokenizer.
    private const val QNN_ENCODER = "encoder.bin"
    private const val QNN_DECODER = "decoder.bin"

    /**
     * sherpa's QNN Whisper stops decoding at (input seconds × 6) tokens, 4 of them the start
     * sequence, even mid-word. Korean runs about a token per syllable, so a quickly spoken 3s
     * clip lost its last syllables. [paddedForTokenBudget] and the window size below give every
     * window at least [QNN_TOKENS_PER_S]. Doubling the speech rate rather than always padding to
     * 30s keeps a bound on how much a decoder stuck repeating itself can produce.
     */
    private const val QNN_TOKENS_PER_S = 12
    private const val QNN_START_TOKENS = 4
    private const val QNN_LIMIT_TOKENS_PER_INPUT_S = 6.0

    /** The input is fixed at 30s, which at 6 tokens/s allows 180 tokens: the start and hint tokens, then 14s at [QNN_TOKENS_PER_S]. */
    internal const val QNN_WINDOW_S = 14.0
    private const val QNN_MIN_WINDOW_S = 9.0

    /**
     * `<|startofprev|> 네, 알겠어.` decoded before the start sequence. Without it Medium writes whole
     * windows with no punctuation; with it 3 minutes of Korean went from 30 to 52 periods and 11 to
     * 23 commas with no sentence lost (PC, same weights, 2026-11-10). Medium only: the same hint made
     * Small ACFT drop sentences and once repeat a character until the token limit.
     */
    private val MEDIUM_KO_HINT = listOf(50361, 8808, 11, 9457, 36711, 13)

    /** What Medium writes under the hint when a window has no voice in it, as [HallucinationFilter.norm] leaves it. */
    private val HINT_ECHOES = setOf("네", "네알겠어", "네알겠습니다")

    internal fun qnnHint(modelId: String, language: String): List<Int> =
        if (modelId == WhisperModelManager.MEDIUM_NPU_MODEL && language == "ko") MEDIUM_KO_HINT else emptyList()

    /** Files a bundle must contain to be usable. */
    fun requiredFiles(m: WhisperModelManager.WhisperModel): List<String> = when (m.engine) {
        SpeechEngine.QNN_WHISPER -> listOf(QNN_ENCODER, QNN_DECODER, "tokens.txt")
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

    @Volatile private var adspPathSet = false

    private fun build(context: Context, dir: File, m: WhisperModelManager.WhisperModel, language: String, diagnostics: Boolean): OfflineRecognizer {
        val model = when (m.engine) {
            SpeechEngine.QNN_WHISPER -> {
                // The HTP skel (libQnnHtpV81Skel.so) is loaded by the DSP from ADSP_LIBRARY_PATH;
                // without the app's lib dir there, device creation fails with error 1008.
                if (!adspPathSet) {
                    OfflineRecognizer.prependAdspLibraryPath(context.applicationInfo.nativeLibraryDir)
                    adspPathSet = true
                }
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        // Context binaries only: no model libraries, so nothing compiles on the phone.
                        encoder = "",
                        decoder = "",
                        language = language.takeUnless { it == "auto" } ?: "",
                        task = "transcribe",
                        promptTokens = qnnHint(m.id, language).joinToString(","),
                        qnnConfig = QnnConfig(
                            backendLib = "libQnnHtp.so",
                            systemLib = "libQnnSystem.so",
                            contextBinary = "${File(dir, QNN_ENCODER).absolutePath},${File(dir, QNN_DECODER).absolutePath}",
                            htpPerfMode = true, // burst clocks: dictation is short and someone is waiting
                        ),
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "qnn",
                    debug = diagnostics, // every tensor at load, QNN profiling on each NPU execution
                )
            }
            SpeechEngine.WHISPER -> error("Whisper runs in LocalWhisperStt")
        }
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80), modelConfig = model),
        )
    }

    private fun ensureLoaded(context: Context, settings: Settings): OfflineRecognizer {
        val id = settings.sttModel
        val language = DictationLanguage.normalize(settings.sttLanguage)
        val key = "$id|$language|${settings.npuDiagnostics}"
        recognizer?.takeIf { loadedId == key }?.let { return it }
        recognizer?.release()
        recognizer = null
        val m = WhisperModelManager.model(id)
        val dir = WhisperModelManager.modelFile(context, id)
        check(isReady(dir, m)) { "${m.label} isn't downloaded yet." }
        val t0 = System.currentTimeMillis()
        val guard = if (m.engine == SpeechEngine.QNN_WHISPER) NpuLoadGuard(dir, AppUpdater.installedVersionCode(context)) else null
        guard?.begin()
        Log.i(TAG, "loading $id from $dir (${dir.list()?.joinToString()})")
        return try {
            build(context, dir, m, language, settings.npuDiagnostics).also {
                recognizer = it
                loadedId = key
                Log.i(TAG, "loaded $id in ${System.currentTimeMillis() - t0}ms")
            }
        } finally {
            guard?.end()
        }
    }

    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray): String =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val t0 = System.nanoTime()
                val rec = ensureLoaded(context, settings)
                val loadMs = (System.nanoTime() - t0) / 1_000_000
                val qnn = WhisperModelManager.model(settings.sttModel).engine == SpeechEngine.QNN_WHISPER
                val parts = if (qnn) windows(samples) else listOf(0 until samples.size)
                val hint = qnnHint(settings.sttModel, DictationLanguage.normalize(settings.sttLanguage))
                val partLog = ArrayList<String>(parts.size)
                val text = parts.map { r ->
                    val stream = rec.createStream()
                    try {
                        val part = if (r.first == 0 && r.last == samples.size - 1) samples else samples.copyOfRange(r.first, r.last + 1)
                        stream.acceptWaveform(if (qnn) paddedForTokenBudget(part, hintTokens = hint.size) else part, 16_000)
                        val d0 = System.nanoTime()
                        rec.decode(stream)
                        val result = rec.getResult(stream)
                        partLog += "${(System.nanoTime() - d0) / 1_000_000}ms/${result.tokens.size}tok"
                        clean(result.text).takeUnless { hint.isNotEmpty() && isHintEcho(it, part) }.orEmpty()
                    } finally {
                        stream.release()
                    }
                }.filter { it.isNotEmpty() }.joinToString(" ").trim()
                Log.i(TAG, "${settings.sttModel}: ${samples.size / 16_000.0}s, load ${loadMs}ms, decode $partLog -> ${text.length} chars")
                // Whisper on silence recites stock TV/YouTube lines.
                HallucinationFilter.clean(text)
            }
        }

    suspend fun warm(context: Context, settings: Settings) = withContext(Dispatchers.Default) {
        runCatching { lock.withLock { ensureLoaded(context, settings) } }
            .onFailure { Log.w(TAG, "warm failed", it) }
    }

    /** [chunk] followed by enough silence that sherpa's token limit allows [QNN_TOKENS_PER_S]. */
    internal fun paddedForTokenBudget(chunk: FloatArray, sr: Int = 16_000, hintTokens: Int = 0): FloatArray {
        // +1: the limit is rounded down, and the feature count can come up a frame or two short.
        val tokens = QNN_START_TOKENS + hintTokens + 1 + chunk.size.toDouble() / sr * QNN_TOKENS_PER_S
        val target = ceil(tokens / QNN_LIMIT_TOKENS_PER_INPUT_S * sr).toInt()
        return if (target > chunk.size) chunk.copyOf(target) else chunk
    }

    /**
     * Split [samples] into windows of at most [QNN_WINDOW_S], cutting each at its quietest 0.2s
     * between [QNN_MIN_WINDOW_S] and [QNN_WINDOW_S] so a word is rarely split.
     */
    internal fun windows(samples: FloatArray, sr: Int = 16_000): List<IntRange> {
        val max = (QNN_WINDOW_S * sr).toInt()
        val min = (QNN_MIN_WINDOW_S * sr).toInt()
        val win = sr / 5
        val hop = sr / 50
        val out = mutableListOf<IntRange>()
        var start = 0
        while (samples.size - start > max) {
            var best = start + max
            var bestE = Double.MAX_VALUE
            var p = start + min
            while (p + win <= start + max) {
                var e = 0.0
                for (i in p until p + win) e += samples[i] * samples[i]
                if (e < bestE) { bestE = e; best = p + win / 2 }
                p += hop
            }
            out += start until best
            start = best
        }
        out += start until samples.size
        return out
    }

    /** [text] is only what the hint produces on silence, and [chunk] has no voice to have said it. */
    internal fun isHintEcho(text: String, chunk: FloatArray, sr: Int = 16_000): Boolean =
        HallucinationFilter.norm(text) in HINT_ECHOES && !hasVoice(chunk, sr)

    /** At least 0.1s rises clearly above the window's own quiet frames ([QuietSpeechDetector]'s rule). */
    internal fun hasVoice(chunk: FloatArray, sr: Int = 16_000): Boolean {
        val frame = sr / 50
        val rms = List(chunk.size / frame) { f ->
            var e = 0.0
            for (i in f * frame until (f + 1) * frame) e += chunk[i] * chunk[i]
            sqrt(e / frame).toFloat()
        }
        if (rms.isEmpty()) return false
        val floor = rms.sorted()[rms.size / 10]
        val voiced = rms.count { it > floor * QuietSpeechDetector.SPEECH_OVER_FLOOR && it > QuietSpeechDetector.ABS_MIN_SPEECH }
        return voiced * frame >= sr / 10
    }

    /**
     * SentencePiece word markers ("▁조국이 …") and stray special tokens don't belong in a text field.
     */
    internal fun clean(raw: String): String =
        raw.replace('▁', ' ')
            .replace(Regex("<\\|[^|>]*\\|>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
