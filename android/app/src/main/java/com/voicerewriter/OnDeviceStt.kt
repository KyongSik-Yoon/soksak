package com.voicerewriter

import android.content.Context

/**
 * Resolves the stored [Settings.sttModel] to a concrete on-device Whisper model and runs
 * whisper.cpp ([LocalWhisperStt]) with it. Keeps call sites (RewriteActivity, HeadlessDictation,
 * BubbleService) independent of how a stale or blank model id is handled.
 */
object OnDeviceStt {

    /**
     * Resolve a stored [Settings.sttModel] to a known on-device model id. A blank or unrecognized
     * value (a fresh install, or a model a previous build offered and this one dropped) falls
     * back to the local provider's default.
     */
    fun resolveModel(modelId: String): String =
        if (WhisperModelManager.MODELS.any { it.id == modelId }) modelId
        else Defaults.STT_PROVIDERS.getValue("local").defaultModel

    /**
     * Same as [resolveModel] but able to see what is actually on disk: an unrecognized id picks
     * the largest Whisper model already downloaded rather than the registry default, so a user
     * whose previous engine was retired keeps dictating without a new download. Falls back to
     * the default when nothing is present — the caller still has to handle "not downloaded".
     */
    fun resolveModel(context: Context, modelId: String): String {
        val id = resolveModel(modelId)
        if (id == modelId) return id
        // MODELS is ordered smallest -> largest, and accuracy tracks size.
        return WhisperModelManager.MODELS.lastOrNull { WhisperModelManager.isReady(context, it.id) }?.id ?: id
    }

    /** Is the model this user would actually transcribe with downloaded? */
    fun isReady(context: Context, modelId: String): Boolean =
        WhisperModelManager.isReady(context, resolveModel(context, modelId))

    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray, biasPrompt: String? = null): String {
        val id = resolveModel(context, settings.sttModel)
        val s = if (id != settings.sttModel) settings.copy(sttModel = id) else settings
        lastFallback = null
        val engine = WhisperModelManager.model(id).engine
        if (engine == SpeechEngine.WHISPER) return LocalWhisperStt.transcribe(context, s, samples, biasPrompt)
        val text = LocalSherpaStt.transcribe(context, s, samples) // no prompt; the dictionary still runs after
        if (engine == SpeechEngine.MOONSHINE && isMoonshineMiss(text, DictationLanguage.normalize(s.sttLanguage))) {
            val fb = MOONSHINE_FALLBACKS.firstOrNull { it != id && WhisperModelManager.isReady(context, it) }
            if (fb != null) {
                android.util.Log.w("OnDeviceStt", "Moonshine returned '$text'; redoing with $fb")
                val out = transcribe(context, s.copy(sttModel = fb), samples, biasPrompt)
                lastFallback = fb
                return out
            }
            return "" // the placeholder is never what was said
        }
        return text
    }

    /** Model that actually produced the last [transcribe] result when Moonshine had to hand off, else null. */
    @Volatile var lastFallback: String? = null
        private set

    /** Fast models first: the user picked Moonshine for speed. */
    private val MOONSHINE_FALLBACKS = listOf(
        WhisperModelManager.SENSE_VOICE_MODEL,
        WhisperModelManager.SMALL_ACFT_MODEL,
        WhisperModelManager.TURBO_ACFT_Q4_MODEL,
    )

    /**
     * Moonshine Base Korean's failure mode on some short clips (~1-3s, about one in four in
     * testing; the tiny model never did it) is a confident English placeholder, "audiotext",
     * instead of the speech. Neither trimming, padding, gain nor dither avoided it reliably, so
     * the result is recognised and redone by another model. For Korean dictation, any output
     * with no Hangul at all is treated the same way: Moonshine Korean writes even English
     * terms in Hangul, so a Latin-only result is the placeholder in another spelling.
     */
    internal fun isMoonshineMiss(text: String, language: String): Boolean {
        val t = text.trim()
        if (t.contains("audiotext", ignoreCase = true)) return true
        return language == "ko" && t.isNotEmpty() && t.none { it in '\uAC00'..'\uD7A3' }
    }

    suspend fun warm(context: Context, modelId: String) {
        val id = resolveModel(context, modelId)
        if (WhisperModelManager.model(id).engine == SpeechEngine.WHISPER) LocalWhisperStt.warm(context, id)
        else LocalSherpaStt.warm(context, SettingsRepository(context.applicationContext).get().copy(sttModel = id))
    }
}
