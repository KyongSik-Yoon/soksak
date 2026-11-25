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
import com.voicerewriter.textproc.RepetitionLoop
import com.whispercpp.whisper.WhisperCpuConfig
import java.io.BufferedInputStream
import java.io.File
import kotlin.math.ceil
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

    /** A window that ended in a [RepetitionLoop] is decoded again in two halves, unless shorter than this. */
    private const val LOOP_SPLIT_MIN_S = 2.0

    /**
     * `<|startofprev|> 네, 알겠어.` decoded before the start sequence. Without it Medium writes whole
     * windows with no punctuation; with it 3 minutes of Korean went from 30 to 52 periods and 11 to
     * 23 commas with no sentence lost (PC, same weights, 2026-11-10). Medium only: the same hint made
     * Small ACFT drop sentences and once repeat a character until the token limit.
     */
    private val MEDIUM_KO_HINT = listOf(50361, 8808, 11, 9457, 36711, 13)

    /**
     * The same hint for Turbo, which wrote long sentences with no commas or periods at all (run-all
     * on the phone, 2026-11-11). Same text tokens; `<|startofprev|>` is one id higher in the
     * large-v3 vocabulary. Whether it costs Turbo sentences, as it did Small ACFT, is checked by
     * running the same recordings again.
     */
    private val TURBO_KO_HINT = listOf(50362, 8808, 11, 9457, 36711, 13)

    /** What Medium (and Turbo, assumed the same until seen) writes under the hint when a window has no voice in it, as [HallucinationFilter.norm] leaves it. */
    private val HINT_ECHOES = setOf("네", "네알겠어", "네알겠습니다")

    /** The hint's own text, as [HallucinationFilter.norm] leaves it. */
    private const val HINT_TEXT = "네알겠어"

    internal fun qnnHint(modelId: String, language: String): List<Int> =
        when {
            language != "ko" -> emptyList()
            modelId == WhisperModelManager.MEDIUM_NPU_MODEL -> MEDIUM_KO_HINT
            modelId == WhisperModelManager.TURBO_NPU_MODEL -> TURBO_KO_HINT
            else -> emptyList()
        }

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

    private var echoVad: SileroVad? = null

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
                // Silero doesn't hear whispers, so Quiet mode judges silence by loudness instead.
                val dropEchoes = hint.isNotEmpty()
                val partLog = ArrayList<String>(parts.size)
                fun decode(part: FloatArray): String {
                    val stream = rec.createStream()
                    try {
                        stream.acceptWaveform(if (qnn) paddedForTokenBudget(part, hintTokens = hint.size) else part, 16_000)
                        val d0 = System.nanoTime()
                        rec.decode(stream)
                        val result = rec.getResult(stream)
                        partLog += "${(System.nanoTime() - d0) / 1_000_000}ms/${result.tokens.size}tok"
                        return clean(result.text)
                    } finally {
                        stream.release()
                    }
                }
                val text = parts.mapNotNull { r ->
                    val part = if (r.first == 0 && r.last == samples.size - 1) samples else samples.copyOfRange(r.first, r.last + 1)
                    var out = decode(part)
                    if (RepetitionLoop.start(out) >= 0) {
                        // The decoder is greedy and nothing stops it once it loops; what was said
                        // after that point is not in the text. Each half decoded on its own starts
                        // without the context that led into the loop.
                        Log.w(TAG, "window of ${part.size / 16_000.0}s ended in a repetition loop (${out.length} chars), decoding it in halves")
                        partLog += "loop"
                        val again = if (part.size >= LOOP_SPLIT_MIN_S * 16_000) {
                            val cut = quietest(part, part.size * 3 / 10, part.size * 7 / 10)
                            listOf(part.copyOfRange(0, cut), part.copyOfRange(cut, part.size))
                                .map { RepetitionLoop.cut(decode(it)) }.filter { it.isNotEmpty() }.joinToString(" ")
                        } else ""
                        out = again.ifEmpty { RepetitionLoop.cut(out) }
                    }
                    if (dropEchoes && retriesEcho(out, settings.quietMode) { hasSpeech(context, part, settings.quietMode) }) {
                        out = echoRetry(context, settings, part)
                        partLog += "hint echo, ${WhisperModelManager.SMALL_ACFT_MODEL} -> ${out.length} chars"
                    }
                    out.takeUnless { it.isEmpty() || dropEchoes && dropsEcho(it, settings.quietMode) { hasSpeech(context, part, settings.quietMode) } }
                }.joinToString(" ")
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
        val out = mutableListOf<IntRange>()
        var start = 0
        while (samples.size - start > max) {
            val best = quietest(samples, start + min, start + max, sr)
            out += start until best
            start = best
        }
        out += start until samples.size
        return out
    }

    /** The middle of the quietest 0.2s of [samples] between [from] and [to]; [to] when there is no room. */
    internal fun quietest(samples: FloatArray, from: Int, to: Int, sr: Int = 16_000): Int {
        val win = sr / 5
        val hop = sr / 50
        var best = to
        var bestE = Double.MAX_VALUE
        var p = from
        while (p + win <= to) {
            var e = 0.0
            for (i in p until p + win) e += samples[i] * samples[i]
            if (e < bestE) { bestE = e; best = p + win / 2 }
            p += hop
        }
        return best
    }

    /** [text] is only what the hint produces on a window with no voice ("네." is also a real answer, so check the audio). */
    internal fun isHintEcho(text: String): Boolean = HallucinationFilter.norm(text) in HINT_ECHOES

    /**
     * Whether a window's [text] is the hint coming back rather than something said.
     *
     * Quiet mode drops the hint's own words whatever the audio holds: its speech test is loudness
     * over the room, which any 0.1s sound passes, and a piece only reaches the decoder after
     * passing that same test ([StreamingWhisper]), so asking it again never dropped anything. A
     * take in which nothing was said, with one 0.4s sound in 2.4s, typed "네, 알겠어." (phone log,
     * 2026-11-23). The cost is a whispered answer of exactly those words. "네." and
     * "네, 알겠습니다." are likelier real answers and still go by the audio.
     */
    internal fun dropsEcho(text: String, quiet: Boolean, hasSpeech: () -> Boolean): Boolean {
        val n = HallucinationFilter.norm(text)
        return n in HINT_ECHOES && (quiet && n == HINT_TEXT || !hasSpeech())
    }

    /**
     * Whether a window's [text] is the hint's own words over audio Silero heard speech in, so the
     * decoder said the hint back instead of what was said: Turbo NPU wrote "네, 알겠어." for a
     * 2.6s isolated take (-46dB) that Small ACFT took as "링이 안 도는 이유를 알고 싶다"
     * (2026-11-25, Compare models). The hint is fixed when the recognizer loads, so the window is
     * decoded again on the CPU ([echoRetry]). Quiet mode keeps dropping it ([dropsEcho]): its
     * speech test passes any sound.
     */
    internal fun retriesEcho(text: String, quiet: Boolean, hasSpeech: () -> Boolean): Boolean =
        !quiet && HallucinationFilter.norm(text) == HINT_TEXT && hasSpeech()

    /** [part] through Small ACFT on the CPU; empty when it isn't downloaded or fails, which drops the window. */
    private suspend fun echoRetry(context: Context, settings: Settings, part: FloatArray): String {
        val id = WhisperModelManager.SMALL_ACFT_MODEL
        if (!WhisperModelManager.isReady(context, id)) {
            Log.w(TAG, "hint echo over speech, and $id isn't downloaded: window dropped")
            return ""
        }
        return runCatching { LocalWhisperStt.transcribe(context, settings.copy(sttModel = id), part).trim() }
            .onFailure { Log.w(TAG, "hint echo retry on $id failed", it) }
            .getOrDefault("")
    }

    /**
     * Quiet mode: loudness over the room ([QuietSpeechDetector]), because Silero misses whispers.
     * Otherwise own Silero: pieces decode while the recorder's shared one is still being fed.
     * Unavailable means keep the text.
     */
    private fun hasSpeech(context: Context, chunk: FloatArray, quiet: Boolean): Boolean = if (quiet) QuietSpeechDetector.hasSpeech(chunk) else runCatching {
        val vad = echoVad ?: SileroVad.create(context)?.also { echoVad = it }
        vad?.hasSpeech(chunk) ?: true
    }.getOrDefault(true)

    /**
     * SentencePiece word markers ("▁조국이 …") and stray special tokens don't belong in a text field.
     */
    internal fun clean(raw: String): String =
        raw.replace('▁', ' ')
            .replace(Regex("<\\|[^|>]*\\|>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
