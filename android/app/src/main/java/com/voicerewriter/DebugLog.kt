package com.voicerewriter

import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This app's recent logcat, so a problem on the phone can be pasted into a chat. An app reads only
 * its own (same uid) lines without permission — including those of earlier processes, which
 * matters because sherpa-onnx's QNN code exits the process on a fatal NPU error: the useful lines
 * belong to the process that died, not this one. pid markers show where each process starts.
 */
object DebugLog {
    /** Enough for several dictations once the framework's chatter is gone; a paste stays readable. */
    private const val MAX_LINES = 1000

    /** The crash buffer outlives app updates by days; a crash worth pasting happened today. */
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    private val THREADTIME = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+\d+\s+\d+ ([VDIWEF]) (.+?)\s*: (.*)$""")

    /** Tags that only narrate drawing, input and windows. Unknown tags stay, so a crash is never lost. */
    private val NOISE_TAGS = setOf(
        "View", "ViewRootImpl", "DecorView", "Choreographer", "HWUI", "Surface", "SurfaceComposerClient",
        "BufferQueueProducer", "BLASTBufferQueue", "BLASTBufferQueue_Java", "InsetsController",
        "InsetsSourceConsumer", "ImeFocusController", "InputMethodManager", "InputMethodManagerUtils",
        "InputTransport", "WindowOnBackDispatcher", "NativeCustomFrequencyManager", "perf_hint", "BBA2",
        "SmartClipDataCropperImpl", "SemSmartClipDataRepository", "SmartClipRemoteRequestDispatcher",
        "RefBase", "dsp-client",
    )

    /** sherpa's field-by-field listing of every QNN tensor at load: thousands of lines, always the same. */
    private val TENSOR_DUMP = Regex("""PrintTensor:|Init(In|Out)putTensors:|^\s|^(input|output) \d+$""")

    private fun stamp(ms: Long) = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))

    /**
     * The newest [max] of [lines] without drawing chatter, FastRPC's routine (non-error) messages,
     * sherpa's tensor listing, focus changes outside text fields or, given [nowMs], anything older than a day. Logcat stamps
     * have no year but sort as text; a [since] after [now] means the day spans New Year.
     */
    internal fun relevant(lines: List<String>, max: Int = MAX_LINES, nowMs: Long? = null): List<String> {
        val now = nowMs?.let(::stamp)
        val since = nowMs?.let { stamp(it - MAX_AGE_MS) }
        return lines.filterNot { line ->
            val (time, level, tag, message) = THREADTIME.find(line)?.destructured ?: return@filterNot false
            val stale = now != null && since != null && (if (since <= now) time < since else time in now..since)
            stale || tag in NOISE_TAGS || tag.startsWith("VRI[") || ("adsprpc/" in message && level != "E") ||
                (tag == "sherpa-onnx" && TENSOR_DUMP.containsMatchIn(message)) ||
                (tag == "OpenWisprA11y" && message.startsWith("fieldFocus") && "editable=false" in message)
        }.takeLast(max)
    }

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
        out = relevant(out.lines(), nowMs = System.currentTimeMillis()).joinToString("\n")
        "OpenWispr pid ${Process.myPid()} ${android.os.Build.MODEL} ${android.os.Build.SOC_MODEL.takeIf { android.os.Build.VERSION.SDK_INT >= 31 } ?: ""}\n$out"
    }.getOrElse { "logcat failed: $it" }
}
