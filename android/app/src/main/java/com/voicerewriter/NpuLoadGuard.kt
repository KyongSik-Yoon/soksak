package com.voicerewriter

import java.io.File

/**
 * Stops an NPU model that kills the process from doing it on every launch.
 *
 * sherpa-onnx's QNN code exits the process (not an exception) when the NPU can't be set up, and
 * the bubble warms the selected model at start — so one bad load became a crash loop (build-66:
 * missing `uses-native-library libcdsprpc.so`). A marker file in the model folder is written
 * before loading and removed after, success or exception. Finding it at the next load means the
 * last one died: the model is then refused with a message instead of loaded. Deleting and
 * re-downloading the model clears it, as does a new app build.
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
                "The NPU model closed the app last time it loaded, so it's off for this build. Pick another model.",
            )
        }
        loading.writeText(build.toString())
    }

    fun end() {
        loading.delete()
    }
}
