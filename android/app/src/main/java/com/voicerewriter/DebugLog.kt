package com.voicerewriter

import android.os.Process

/**
 * This app's recent logcat, so a problem on the phone can be pasted into a chat. An app reads only
 * its own (same uid) lines without permission — including those of earlier processes, which
 * matters because sherpa-onnx's QNN code exits the process on a fatal NPU error: the useful lines
 * belong to the process that died, not this one. pid markers show where each process starts.
 */
object DebugLog {
    fun recent(lines: Int = 3000): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-b", "main,crash", "-v", "time,pid", "-t", lines.toString())
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        "OpenWispr pid ${Process.myPid()} ${android.os.Build.MODEL} ${android.os.Build.SOC_MODEL.takeIf { android.os.Build.VERSION.SDK_INT >= 31 } ?: ""}\n$out"
    }.getOrElse { "logcat failed: $it" }
}
