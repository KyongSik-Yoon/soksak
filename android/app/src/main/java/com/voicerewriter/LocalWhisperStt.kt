package com.voicerewriter

import android.content.Context
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperCpuConfig
import com.whispercpp.whisper.WhisperGpu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device speech-to-text via whisper.cpp (vendored `:lib`). Lazily loads the
 * downloaded model into a cached WhisperContext (reused across dictations) and
 * transcribes 16 kHz mono float samples fully offline.
 *
 * Same role as [SttEngine] but local; the caller picks based on the STT provider.
 */
object LocalWhisperStt {

    private val loadLock = Mutex()
    @Volatile private var ctx: WhisperContext? = null
    @Volatile private var loadedKey: String? = null

    /**
     * Transcribe [samples] (16 kHz mono, normalized -1..1). Suspends on heavy CPU work.
     * [biasPrompt] (optional) primes the recognizer toward the user's vocabulary.
     */
    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray, biasPrompt: String? = null): String {
        val id = settings.sttModel.ifBlank { WhisperModelManager.DEFAULT_MODEL }
        if (!WhisperModelManager.isReady(context, id)) {
            throw IllegalStateException("On-device model not downloaded. Open Settings → Voice → Download model.")
        }
        val seconds = samples.size / AudioRecorder.SAMPLE_RATE.toFloat()
        val t0 = System.nanoTime()
        val whisper = loadContext(context, id, wantGpu(context, settings, id))
        val tLoaded = System.nanoTime()
        // transcribeData runs on whisper's own single-thread dispatcher internally.
        val lang = DictationLanguage.normalize(settings.sttLanguage)
        val prompt = buildPrompt(biasPrompt?.ifBlank { null }?.let { localizeGlossary(it, lang) }, settings.promptExample)
        // Non-English dictation is code-switched speech in practice: Korean full of English
        // tech terms ("worktree 만들고 MR 올려") decoded with a Korean language token, where
        // greedy decoding at each ambiguous step drifts into English. Beam search (5) and
        // natural segmentation hold it to the spoken language; the cost lands on decode
        // (~+344ms on a 9s clip, measured). English keeps greedy + single-segment.
        val codeSwitched = !DictationLanguage.isEnglish(lang)
        val beamSize = if (codeSwitched) 5 else 1
        val decode: suspend () -> String = { whisper.transcribeData(
            samples,
            printTimestamp = false,
            prompt = prompt,
            language = lang,
            beamSize = beamSize,
            singleSegment = !codeSwitched,
            // ACFT weights tolerate a context sized to the clip; 128 keeps a small margin.
            audioCtxFloor = if (WhisperModelManager.model(id).acft) 128 else 768,
        ) }
        val raw = if (whisper.gpu) GpuGuard.run(context, decode) else decode()
        val tDone = System.nanoTime()
        lastLoadMs = (tLoaded - t0) / 1_000_000
        lastInferMs = (tDone - tLoaded) / 1_000_000
        Log.i(
            "LocalWhisperStt",
            "model=$id audio=${"%.1f".format(seconds)}s " +
                "load=${(tLoaded - t0) / 1_000_000}ms infer=${(tDone - tLoaded) / 1_000_000}ms " +
                "threads=${WhisperCpuConfig.preferredThreadCount} backend=$lastBackend",
        )
        return cleanTranscript(raw)
    }

    /**
     * Preload the model context ahead of the first dictation (e.g. on service start) so
     * the first transcription doesn't pay the createContextFromFile cost. Best-effort:
     * returns quietly if the model isn't downloaded. Reuses the same cached context
     * as [transcribe], so a warmed context is used directly by the next call.
     */
    suspend fun warm(context: Context, modelId: String) {
        val id = modelId.ifBlank { WhisperModelManager.DEFAULT_MODEL }
        if (!WhisperModelManager.isReady(context, id)) return
        val settings = SettingsRepository(context.applicationContext).get()
        val t0 = System.nanoTime()
        loadContext(context, id, wantGpu(context, settings, id))
        Log.i("LocalWhisperStt", "warm model=$id backend=$lastBackend load=${(System.nanoTime() - t0) / 1_000_000}ms")
    }

    /** Timings of the last [transcribe]: loading the model (0 when cached) and the decode itself. */
    @Volatile var lastLoadMs: Long = 0
        private set
    @Volatile var lastInferMs: Long = 0
        private set

    /**
     * Only the model that needs the help. The small ones already finish in a few seconds on the
     * CPU, and each one on the GPU would be another set of weights repacked into GPU memory.
     */
    val GPU_MODELS = setOf(WhisperModelManager.TURBO_ACFT_Q4_MODEL)

    /** What the last loaded context runs on, for logs and Compare ("GPU · Adreno 750", "CPU"). */
    @Volatile var lastBackend: String = "CPU"
        private set

    /**
     * GPU only when asked for, built in, and the last GPU call didn't take the process down.
     * After such a crash the setting is switched off for good (the user can turn it back on).
     */
    private suspend fun wantGpu(context: Context, settings: Settings, id: String): Boolean {
        if (!settings.whisperGpu || id !in GPU_MODELS) return false
        if (GpuGuard.consumeCrash(context)) {
            Log.w("LocalWhisperStt", "previous GPU run crashed; GPU turned off")
            val repo = SettingsRepository(context.applicationContext)
            repo.save(repo.get().copy(whisperGpu = false))
            return false
        }
        return WhisperGpu.available
    }

    /**
     * The cached context for ([id], [gpu]), loading it if needed. A GPU context that fails to
     * load (no OpenCL device, out of GPU memory) falls back to the CPU build for this model.
     */
    private suspend fun loadContext(context: Context, id: String, gpu: Boolean): WhisperContext = loadLock.withLock {
        val key = "$id|$gpu"
        ctx?.let { if (loadedKey == key) return@withLock it }
        ctx?.let { runCatching { it.release() } }
        ctx = null
        val path = WhisperModelManager.modelFile(context, id).absolutePath
        val loaded = withContext(Dispatchers.IO) {
            if (gpu) {
                try {
                    GpuGuard.run(context) {
                        // Without an OpenCL device the GPU build quietly runs whisper on its own CPU
                        // backend; say so rather than labelling that "GPU".
                        val device = WhisperGpu.deviceName
                        WhisperContext.createGpuContextFromFile(path).also {
                            lastBackend = device?.let { n -> "GPU · $n" } ?: "CPU (no OpenCL GPU)"
                        }
                    }
                } catch (e: Exception) {
                    Log.w("LocalWhisperStt", "GPU context failed, using CPU", e)
                    lastBackend = "CPU (GPU failed: ${e.message})"
                    WhisperContext.createContextFromFile(path)
                }
            } else {
                lastBackend = "CPU"
                WhisperContext.createContextFromFile(path)
            }
        }
        ctx = loaded
        loadedKey = key
        loaded
    }

    /**
     * Whisper reads initial_prompt as preceding transcript, so its language leans the decoder.
     * "Glossary: GitLab, MR, worktree." is an English sentence; in front of Korean speech it
     * pulls toward English. Keep the terms, swap the English frame for a Korean one.
     */
    internal fun localizeGlossary(prompt: String, lang: String): String = when (lang) {
        "ko" -> if (prompt.startsWith(GLOSSARY_PREFIX)) "용어: " + prompt.removePrefix(GLOSSARY_PREFIX) else prompt
        else -> prompt
    }

    private const val GLOSSARY_PREFIX = "Glossary: "

    /**
     * Glossary first, style example last: whisper.cpp keeps the *end* of an over-long prompt,
     * and the text right before the audio is what the decoder imitates most.
     */
    internal fun buildPrompt(glossary: String?, example: String?): String? =
        listOfNotNull(glossary?.trim()?.ifBlank { null }, example?.trim()?.ifBlank { null })
            .joinToString(" ")
            .ifBlank { null }

    /** Strip whisper's bracketed non-speech markers (e.g. [BLANK_AUDIO], [Music]). */
    private fun cleanTranscript(s: String): String =
        s.replace(Regex("\\[[^\\]]*]"), "").trim()
}
