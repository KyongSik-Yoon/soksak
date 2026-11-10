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
     * Same as [resolveModel] but able to see what is actually on disk: when the stored model is
     * not downloaded — an id a previous build offered, or a model this install never fetched
     * (a backup restored from another install names the model that one used) — the largest
     * downloaded model runs instead, so dictation keeps working without a new download. Falls
     * back to the registry id when nothing is present; the caller still handles "not downloaded".
     */
    fun resolveModel(context: Context, modelId: String): String =
        pick(modelId) { WhisperModelManager.isReady(context, it) }

    /** [resolveModel] with the disk check passed in, for tests. */
    internal fun pick(modelId: String, ready: (String) -> Boolean): String {
        val id = resolveModel(modelId)
        if (ready(id)) return id
        // MODELS is ordered smallest -> largest, and accuracy tracks size.
        return WhisperModelManager.MODELS.lastOrNull { ready(it.id) }?.id ?: id
    }

    /** Is the model this user would actually transcribe with downloaded? */
    fun isReady(context: Context, modelId: String): Boolean =
        WhisperModelManager.isReady(context, resolveModel(context, modelId))

    suspend fun transcribe(context: Context, settings: Settings, samples: FloatArray, biasPrompt: String? = null): String {
        val id = resolveModel(context, settings.sttModel)
        val s = if (id != settings.sttModel) settings.copy(sttModel = id) else settings
        return if (WhisperModelManager.model(id).engine == SpeechEngine.WHISPER)
            LocalWhisperStt.transcribe(context, s, samples, biasPrompt)
        else LocalSherpaStt.transcribe(context, s, samples) // no prompt; the dictionary still runs after
    }

    suspend fun warm(context: Context, modelId: String) {
        val id = resolveModel(context, modelId)
        if (WhisperModelManager.model(id).engine == SpeechEngine.WHISPER) LocalWhisperStt.warm(context, id)
        else LocalSherpaStt.warm(context, SettingsRepository(context.applicationContext).get().copy(sttModel = id))
    }
}
