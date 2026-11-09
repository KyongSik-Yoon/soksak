package com.voicerewriter

import android.os.Process

/**
 * This process's recent logcat (an app may read its own lines without any permission), so a
 * problem on the phone — e.g. the NPU model returning nothing — can be pasted into a chat.
 */
object DebugLog {
    fun recent(lines: Int = 1500): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", lines.toString(), "--pid", Process.myPid().toString())
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        "OpenWispr build ${android.os.Build.MODEL} ${android.os.Build.SOC_MODEL.takeIf { android.os.Build.VERSION.SDK_INT >= 31 } ?: ""}\n$out"
    }.getOrElse { "logcat failed: $it" }
}
