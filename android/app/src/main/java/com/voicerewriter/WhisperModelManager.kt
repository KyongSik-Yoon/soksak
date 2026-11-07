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
 * Manages on-device Whisper model files (ggml) for whisper.cpp: a registry of
 * multilingual sizes, one-time download with progress, and readiness checks.
 *
 * `small` (~488MB) is accurate but heavy/slow on phones (memory + CPU), so the
 * default is `base` — a good accuracy/speed balance; `tiny` is the fastest.
 */
object WhisperModelManager {

    data class WhisperModel(
        val id: String,
        val label: String,
        val fileName: String,
        val url: String,
        val sizeLabel: String,
    ) {
        /** No public download: the file is converted in CI and imported from the phone. */
        val importOnly: Boolean get() = url.isBlank()
    }

    private fun hf(file: String) = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"

    const val SMALL_ACFT_MODEL = "small-acft"
    const val TURBO_ACFT_MODEL = "turbo-acft"

    val MODELS = listOf(
        WhisperModel("tiny", "Tiny (fastest)", "ggml-tiny.bin", hf("ggml-tiny.bin"), "~75MB"),
        WhisperModel("base", "Base (balanced)", "ggml-base.bin", hf("ggml-base.bin"), "~142MB"),
        WhisperModel("small", "Small (most accurate)", "ggml-small.bin", hf("ggml-small.bin"), "~488MB"),
        // FUTO's audio-context fine-tune of multilingual whisper-small (Apache 2.0,
        // github.com/futo-org/whisper-acft) — the same weights FUTO Voice Input ships. jni.c
        // shrinks audio_ctx to the clip length (768 floor) for speed; stock Whisper was never
        // trained on a truncated context and loses accuracy there, ACFT was trained for it.
        WhisperModel(
            id = SMALL_ACFT_MODEL,
            label = "Small ACFT (FUTO)",
            fileName = "small_acft_q8_0.bin",
            url = "https://voiceinput.futo.org/VoiceInput/small_acft_q8_0.bin",
            sizeLabel = "~264MB · tuned for short clips",
        ),
        // whisper-large-v3-turbo with FUTO's audio-context fine-tune, by Mahmoud Ashraf
        // (Apache 2.0, huggingface.co/MahmoudAshraf/acft-whisper-large-v3-turbo). Stock turbo
        // collapses under a shortened audio_ctx (WER 15.4 -> 62.9 in its card); this keeps
        // full-context accuracy. ~3x the parameters of small and much stronger on Korean, but
        // slower: try it before relying on it. No public ggml exists, so the
        // convert-model workflow builds one as a release asset and it is imported from a file.
        WhisperModel(
            id = TURBO_ACFT_MODEL,
            label = "Large v3 Turbo ACFT",
            fileName = "ggml-acft-large-v3-turbo-q5_0.bin",
            url = "",
            sizeLabel = "~550MB · import file · slowest, most accurate",
        ),
    )

    const val DEFAULT_MODEL = "tiny"
    private const val MIN_VALID_BYTES = 30L * 1024 * 1024


    fun model(id: String): WhisperModel =
        MODELS.firstOrNull { it.id == id } ?: MODELS.first { it.id == DEFAULT_MODEL }

    fun modelFile(context: Context, id: String): File =
        File(File(context.filesDir, "models").apply { mkdirs() }, model(id).fileName)

    fun isReady(context: Context, id: String): Boolean =
        modelFile(context, id).let { it.exists() && it.length() > MIN_VALID_BYTES }

    /**
     * Delete model [id] from disk, freeing its bytes. Returns bytes reclaimed (0 if it wasn't
     * there). Callers are responsible for not deleting the model currently selected — the
     * Settings UI only offers this on a downloaded-but-inactive model, per issue #53.
     */
    suspend fun delete(context: Context, id: String): Long = withContext(Dispatchers.IO) {
        val freed = ModelDownloader.deleteWithSidecars(modelFile(context, id))
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

    /** Download model [id], reporting progress 0f..1f. Throws on network error. */
    suspend fun download(context: Context, id: String, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            if (isReady(context, id)) { onProgress(1f); return@withContext }
            if (model(id).importOnly) throw IllegalStateException("${model(id).label} has no download; import its file instead.")
            ModelDownloader.fetch(model(id).url, modelFile(context, id), onProgress)
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
