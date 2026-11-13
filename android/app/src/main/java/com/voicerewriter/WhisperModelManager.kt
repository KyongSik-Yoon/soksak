package com.voicerewriter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages on-device Whisper models: a registry of multilingual sizes, one-time download with
 * progress, and readiness checks. Small ACFT is the default; the stock Tiny/Base/Small and
 * SenseVoice were removed on 2026-11-09 as never used.
 */
object WhisperModelManager {

    data class WhisperModel(
        val id: String,
        val label: String,
        val fileName: String,
        val url: String,
        val sizeLabel: String,
        /**
         * Fine-tuned for a shortened audio context (FUTO ACFT). These run with a much lower
         * audio_ctx floor than stock weights, which loop below 768 — see jni.c.
         */
        val acft: Boolean = false,
        /**
         * Hosted as an asset of this release in this repo (built by the convert-model or NPU
         * workflows). Fetched anonymously (the repo is public).
         */
        val releaseTag: String? = null,
        /** Which runtime decodes it. NPU models are sherpa-onnx bundles (a directory). */
        val engine: SpeechEngine = SpeechEngine.WHISPER,
        /** One or two letters drawn on the floating bubble so the active model is visible there. */
        val badge: String = label.take(1),
        /** NPU models: the chips ([NpuSupport]) a bundle has been built for. */
        val socs: List<String> = emptyList(),
    ) {
        val githubHosted: Boolean get() = releaseTag != null
        /** No download at all: the file can only be imported from the phone. */
        val importOnly: Boolean get() = url.isBlank() && !githubHosted
    }

    const val SMALL_ACFT_MODEL = "small-acft"
    const val TURBO_ACFT_Q4_MODEL = "turbo-acft-q4"
    const val TURBO_NPU_MODEL = "turbo-acft-npu"
    const val MEDIUM_NPU_MODEL = "medium-npu"

    val MODELS = listOf(
        // FUTO's audio-context fine-tune of multilingual whisper-small (Apache 2.0,
        // github.com/futo-org/whisper-acft) — the same weights FUTO Voice Input ships. jni.c
        // shrinks audio_ctx to the clip length (768 floor) for speed; stock Whisper was never
        // trained on a truncated context and loses accuracy there, ACFT was trained for it.
        // FUTO's own q8_0 file (voiceinput.futo.org), mirrored unchanged to this repo's release so
        // the app doesn't lean on FUTO's server; the release notes carry its SHA-256 and licence.
        // The plain download URL, not releaseTag: that goes through the anonymous GitHub API
        // (60 requests an hour per IP), too tight for the model every first run fetches.
        WhisperModel(
            id = SMALL_ACFT_MODEL,
            label = "Small ACFT (FUTO)",
            fileName = "small_acft_q8_0.bin",
            url = "https://github.com/${AppUpdater.REPO}/releases/download/model-acft-small-q8_0/small_acft_q8_0.bin",
            sizeLabel = "~264MB · tuned for short clips",
            acft = true,
            badge = "SA",
        ),
        // whisper-large-v3-turbo with FUTO's audio-context fine-tune, by Mahmoud Ashraf
        // (Apache 2.0, huggingface.co/MahmoudAshraf/acft-whisper-large-v3-turbo). Stock turbo
        // collapses under a shortened audio_ctx (WER 15.4 -> 62.9 in its card); this keeps
        // full-context accuracy. ~3x the parameters of small and much stronger on Korean, but
        // slower: try it before relying on it. No public ggml exists, so the
        // convert-model workflow builds one as a release asset and it is imported from a file.
        // q4_0 only: it is the type ggml repacks for ARM's dot-product / i8mm kernels, so the
        // fastest on a phone. The q5_0 build was dropped as too slow for what it added.
        WhisperModel(
            id = TURBO_ACFT_Q4_MODEL,
            label = "Large v3 Turbo ACFT",
            fileName = "ggml-acft-large-v3-turbo-q4_0.bin",
            url = "",
            sizeLabel = "~450MB · largest, slower",
            acft = true,
            releaseTag = "model-acft-large-v3-turbo-q4_0",
            badge = "T",
        ),
        // OpenAI whisper-medium (multilingual, float) on the Qualcomm NPU through sherpa-onnx's QNN
        // runtime, as context binaries for SM8850 compiled in CI (npu-model.yml) from sherpa's QNN
        // model libraries — compiling them on the phone died in the decoder (builds 67-70).
        // npu-bench (2026-11-09): CER on par with Turbo ACFT on clean and quiet speech, best on
        // code-switched dev requests, weaker in babble. No prompt support; fixed 30s input.
        // Only offered where [NpuSupport] says the binaries and HTP libraries fit.
        WhisperModel(
            id = MEDIUM_NPU_MODEL,
            label = "Medium (NPU)",
            fileName = "sherpa-onnx-qnn-SM8850-binary-whisper-medium",
            url = "",
            sizeLabel = "~1.5GB · Snapdragon NPU",
            releaseTag = "model-qnn-whisper-medium-sm8850",
            engine = SpeechEngine.QNN_WHISPER,
            badge = "MN",
            socs = listOf(NpuSupport.SM8850),
        ),
        // The Turbo ACFT weights above on the Qualcomm NPU, compiled in CI (npu-turbo.yml). sherpa-onnx ships no QNN build above Medium; this one needed float16
        // weights to link and the large-v3 token ids in the runtime (sherpa-qnn.patch). Its decoder
        // has 4 layers to Medium's 24, so a token should cost far less; the encoder is twice the size.
        // Runs at the full 30s audio context and takes no prompt, like every NPU model. On the phone
        // (2026-11-11) it was both faster and more accurate than Medium (NPU) by ear; not measured yet.
        WhisperModel(
            id = TURBO_NPU_MODEL,
            label = "Large v3 Turbo (NPU)",
            // One bundle per chip, so the names carry this phone's.
            fileName = "sherpa-onnx-qnn-${NpuSupport.bundleSoc}-binary-whisper-turbo-acft",
            url = "",
            sizeLabel = "~1.6GB · Snapdragon NPU" +
                if (NpuSupport.soc == NpuSupport.SM8750) " · not yet tried on this chip" else "",
            releaseTag = "model-qnn-whisper-turbo-acft-${NpuSupport.bundleSoc.lowercase()}",
            engine = SpeechEngine.QNN_WHISPER,
            badge = "TN",
            socs = listOf(NpuSupport.SM8850, NpuSupport.SM8750),
        ),
    )

    /** Models this phone can run: an NPU model only on a chip it has been built for. */
    fun available(): List<WhisperModel> = availableOn(NpuSupport.soc)

    internal fun availableOn(soc: String?): List<WhisperModel> =
        MODELS.filter { it.engine != SpeechEngine.QNN_WHISPER || soc in it.socs }

    const val DEFAULT_MODEL = SMALL_ACFT_MODEL
    private const val MIN_VALID_BYTES = 30L * 1024 * 1024


    fun model(id: String): WhisperModel =
        MODELS.firstOrNull { it.id == id } ?: MODELS.first { it.id == DEFAULT_MODEL }

    fun modelFile(context: Context, id: String): File =
        File(File(context.filesDir, "models").apply { mkdirs() }, model(id).fileName)

    fun isReady(context: Context, id: String): Boolean {
        val m = model(id)
        val f = modelFile(context, id)
        return if (m.engine == SpeechEngine.WHISPER) f.isFile && f.length() > MIN_VALID_BYTES
        else LocalSherpaStt.isReady(f, m)
    }

    /**
     * Delete model [id] from disk, freeing its bytes. Returns bytes reclaimed (0 if it wasn't
     * there). Callers are responsible for not deleting the model currently selected — the
     * Settings UI only offers this on a downloaded-but-inactive model, per issue #53.
     */
    suspend fun delete(context: Context, id: String): Long = withContext(Dispatchers.IO) {
        val f = modelFile(context, id)
        val freed = if (model(id).engine == SpeechEngine.WHISPER) ModelDownloader.deleteWithSidecars(f)
            else LocalSherpaStt.delete(f)
        // The download flows landed in #55, so a stale "done" can now outlive the file it
        // referred to. Clear it here rather than leaving onboarding to trust it.
        if (_downloadState.value == "done") _downloadState.value = "idle"
        freed
    }

    /**
     * Copy a ggml file the user picked into model [id]'s slot, reporting progress 0f..1f.
     * Rejects anything that isn't a whisper.cpp ggml file (magic "ggml", stored little-endian
     * as the bytes `lmgg`), so a stray zip or HTML page can't become a model that crashes the
     * native loader. Written to a temp name and renamed, so a half copy is never "ready".
     */
    suspend fun importFrom(context: Context, id: String, uri: android.net.Uri, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val total = resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
            val target = modelFile(context, id)
            val tmp = File(target.parentFile, target.name + ".import")
            try {
                val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Couldn't open that file.")
                input.use { inp ->
                    val head = ByteArray(4)
                    if (inp.read(head) != 4 || !(head[0] == 'l'.code.toByte() && head[1] == 'm'.code.toByte() &&
                            head[2] == 'g'.code.toByte() && head[3] == 'g'.code.toByte())
                    ) throw IllegalArgumentException("That isn't a whisper.cpp model file (.bin).")
                    tmp.outputStream().use { out ->
                        out.write(head)
                        val buf = ByteArray(1 shl 20)
                        var done = 4L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                        out.fd.sync()
                    }
                }
                if (tmp.length() <= MIN_VALID_BYTES) throw IllegalStateException("That file is too small to be a model.")
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) throw IllegalStateException("Couldn't save the model.")
                onProgress(1f)
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }

    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** True while any model download runs (onboarding's or one started from Settings). */
    val anyDownloading: Boolean get() = inFlight.get() > 0 || _downloadState.value == "downloading"

    /** Download model [id], reporting progress 0f..1f. Throws on network error. */
    suspend fun download(context: Context, id: String, onProgress: (Float) -> Unit) {
        inFlight.incrementAndGet()
        try { fetchModel(context, id, onProgress) } finally { inFlight.decrementAndGet() }
    }

    private suspend fun fetchModel(context: Context, id: String, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            if (isReady(context, id)) { onProgress(1f); return@withContext }
            val m = model(id)
            if (m.engine != SpeechEngine.WHISPER) {
                val url = if (m.githubHosted) {
                    AppUpdater.releaseAssetUrl(m.releaseTag!!, "${m.fileName}.tar.bz2")
                } else m.url
                LocalSherpaStt.download(url, modelFile(context, id), m, onProgress)
                return@withContext
            }
            if (m.importOnly) throw IllegalStateException("${m.label} has no download; import its file instead.")
            val url = if (m.githubHosted) {
                AppUpdater.releaseAssetUrl(m.releaseTag!!, m.fileName)
            } else m.url
            ModelDownloader.fetch(url, modelFile(context, id), onProgress)
        }

    // --- Lifecycle-independent download ---
    // Onboarding picks a Whisper size per device ([DeviceFit]) and waits on its download. That flow observes state rather than owning
    // the coroutine, because the Activity closing must not cancel a half-finished download.

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadState = MutableStateFlow("idle") // "idle" | "downloading" | "done" | "error"
    val downloadState: StateFlow<String> = _downloadState.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    /** Idempotent: no-ops if [id] is already downloaded or a download is already in flight. */
    fun ensureDownloading(context: Context, id: String) {
        if (_downloadState.value == "downloading") return
        val appContext = context.applicationContext
        if (isReady(appContext, id)) { _downloadState.value = "done"; return }
        _downloadState.value = "downloading"; _downloadProgress.value = 0f; _downloadError.value = null
        managerScope.launch {
            try {
                download(appContext, id) { p -> _downloadProgress.value = p }
                _downloadState.value = "done"
            } catch (t: Throwable) {
                Log.w("WhisperModel", "download failed", t)
                _downloadError.value = t.message ?: "Download failed"
                _downloadState.value = "error"
            }
        }
    }
}

/** The runtime a speech model needs. */
enum class SpeechEngine {
    WHISPER,
    /** OpenAI Whisper on the Qualcomm NPU through sherpa-onnx's QNN runtime ([NpuSupport]). */
    QNN_WHISPER,
}
