package com.voicerewriter

import android.os.Process

/**
 * This app's recent logcat, so a problem on the phone can be pasted into a chat. An app reads only
 * its own (same uid) lines without permission — including those of earlier processes, which
 * matters because sherpa-onnx's QNN code exits the process on a fatal NPU error: the useful lines
 * belong to the process that died, not this one. pid markers show where each process starts.
 */
object DebugLog {
    /** Enough for several dictations once the framework's chatter is gone; a paste stays readable. */
    private const val MAX_LINES = 1000

    private val THREADTIME = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+ ([VDIWEF]) (.+?)\s*: (.*)$""")

    /** Tags that only narrate drawing, input and windows. Unknown tags stay, so a crash is never lost. */
    private val NOISE_TAGS = setOf(
        "View", "ViewRootImpl", "DecorView", "Choreographer", "HWUI", "Surface", "SurfaceComposerClient",
        "BufferQueueProducer", "BLASTBufferQueue", "BLASTBufferQueue_Java", "InsetsController",
        "InsetsSourceConsumer", "ImeFocusController", "InputMethodManager", "InputMethodManagerUtils",
        "InputTransport", "WindowOnBackDispatcher", "NativeCustomFrequencyManager", "perf_hint", "BBA2",
        "SmartClipDataCropperImpl", "SemSmartClipDataRepository", "SmartClipRemoteRequestDispatcher",
        "RefBase", "dsp-client",
    )

    /** The newest [max] of [lines] without drawing chatter or FastRPC's routine (non-error) messages. */
    internal fun relevant(lines: List<String>, max: Int = MAX_LINES): List<String> =
        lines.filterNot { line ->
            val (level, tag, message) = THREADTIME.find(line)?.destructured ?: return@filterNot false
            tag in NOISE_TAGS || tag.startsWith("VRI[") || ("adsprpc/" in message && level != "E")
        }.takeLast(max)

    fun recent(): String = runCatching {
        // threadtime carries the pid; plain "-v pid" isn't accepted by every logcat (Samsung's).
        val tries = listOf(
            // No -t: it counts lines before the uid filter, so other apps' chatter pushed ours out.
            listOf("logcat", "-d", "-b", "main,crash", "-v", "threadtime"),
            listOf("logcat", "-d", "-v", "threadtime"),
            listOf("logcat", "-d"),
        )
        var out = ""
        for (cmd in tries) {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (p.exitValue() == 0 && out.lines().size > 3) break
        }
        out = relevant(out.lines()).joinToString("\n")
        "OpenWispr pid ${Process.myPid()} ${android.os.Build.MODEL} ${android.os.Build.SOC_MODEL.takeIf { android.os.Build.VERSION.SDK_INT >= 31 } ?: ""}\n$out"
    }.getOrElse { "logcat failed: $it" }
}
