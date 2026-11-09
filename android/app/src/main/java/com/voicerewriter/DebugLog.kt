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
        // threadtime carries the pid; plain "-v pid" isn't accepted by every logcat (Samsung's).
        val tries = listOf(
            listOf("logcat", "-d", "-b", "main,crash", "-v", "threadtime", "-t", lines.toString()),
            listOf("logcat", "-d", "-v", "threadtime", "-t", lines.toString()),
            listOf("logcat", "-d", "-t", lines.toString()),
        )
        var out = ""
        for (cmd in tries) {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (p.exitValue() == 0 && out.lines().size > 3) break
        }
        "OpenWispr pid ${Process.myPid()} ${android.os.Build.MODEL} ${android.os.Build.SOC_MODEL.takeIf { android.os.Build.VERSION.SDK_INT >= 31 } ?: ""}\n$out"
    }.getOrElse { "logcat failed: $it" }
}
