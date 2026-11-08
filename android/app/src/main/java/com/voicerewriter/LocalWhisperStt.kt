package com.voicerewriter

import android.content.Context
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperCpuConfig
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
    @Volatile private var loadedId: String? = null

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
        val whisper = loadLock.withLock {
            if (ctx == null || loadedId != id) {
                ctx?.let { runCatching { it.release() } }
                ctx = withContext(Dispatchers.IO) {
                    WhisperContext.createContextFromFile(WhisperModelManager.modelFile(context, id).absolutePath)
                }
                loadedId = id
            }
            ctx!!
        }
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
        val raw = whisper.transcribeData(
            samples,
            printTimestamp = false,
            prompt = prompt,
            language = lang,
            beamSize = beamSize,
            singleSegment = !codeSwitched,
            // ACFT weights tolerate a context sized to the clip; 128 keeps a small margin.
            audioCtxFloor = if (WhisperModelManager.model(id).acft) 128 else 768,
        )
        val tDone = System.nanoTime()
        Log.i(
            "LocalWhisperStt",
            "model=$id audio=${"%.1f".format(seconds)}s " +
                "load=${(tLoaded - t0) / 1_000_000}ms infer=${(tDone - tLoaded) / 1_000_000}ms " +
                "threads=${WhisperCpuConfig.preferredThreadCount}",
        )
        return cleanTranscript(raw)
    }

    /**
     * Preload the model context ahead of the first dictation (e.g. on service start) so
     * the first transcription doesn't pay the createContextFromFile cost. Best-effort:
     * returns quietly if the model isn't downloaded. Reuses the same cached ctx/loadedId
     * as [transcribe], so a warmed context is used directly by the next call.
     */
    suspend fun warm(context: Context, modelId: String) {
        val id = modelId.ifBlank { WhisperModelManager.DEFAULT_MODEL }
        if (!WhisperModelManager.isReady(context, id)) return
        loadLock.withLock {
            if (ctx != null && loadedId == id) return  // already warm
            val t0 = System.nanoTime()
            ctx?.let { runCatching { it.release() } }
            ctx = withContext(Dispatchers.IO) {
                WhisperContext.createContextFromFile(WhisperModelManager.modelFile(context, id).absolutePath)
            }
            loadedId = id
            Log.i("LocalWhisperStt", "warm model=$id load=${(System.nanoTime() - t0) / 1_000_000}ms")
        }
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
