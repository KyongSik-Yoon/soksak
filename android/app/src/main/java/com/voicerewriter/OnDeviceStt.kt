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
        return LocalWhisperStt.transcribe(context, s, samples, biasPrompt)
    }

    suspend fun warm(context: Context, modelId: String) =
        LocalWhisperStt.warm(context, resolveModel(context, modelId))
}
