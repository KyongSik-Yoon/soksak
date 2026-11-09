package com.voicerewriter

import java.io.File

/**
 * Stops an NPU model that kills the process from doing it on every launch.
 *
 * sherpa-onnx's QNN code exits the process (not an exception) when the NPU can't be set up, and
 * the bubble warms the selected model at start — so one bad load became a crash loop (build-66:
 * missing `uses-native-library libcdsprpc.so`). A marker file in the model folder is written
 * before loading and removed after, success or exception. Finding it at the next load means the
 * last one died: the model is then refused with a message instead of loaded. Picking the model
 * again (Settings or the bubble menu) clears it, as does a new app build.
 */
class NpuLoadGuard(private val dir: File, private val build: Long) {
    private val loading = File(dir, ".npu-loading")
    private val failed = File(dir, ".npu-failed")

    /** Throws if a previous load of this model killed the app (in this build). */
    fun begin() {
        if (loading.exists()) {
            loading.delete()
            failed.writeText(build.toString())
        }
        if (failed.exists() && failed.readText().trim() == build.toString()) {
            throw IllegalStateException(
                "The NPU model closed the app last time it loaded. Pick another model, or pick it again to retry.",
            )
        }
        loading.writeText(build.toString())
    }

    fun end() {
        loading.delete()
    }

    companion object {
        /** The user picked NPU model [id] on purpose: let it try again. */
        fun reset(context: android.content.Context, id: String) {
            if (WhisperModelManager.model(id).engine != SpeechEngine.QNN_WHISPER) return
            val dir = WhisperModelManager.modelFile(context, id)
            File(dir, ".npu-loading").delete()
            File(dir, ".npu-failed").delete()
        }
    }
}
