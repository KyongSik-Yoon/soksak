package com.voicerewriter

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.whispercpp.whisper.WhisperGpu
import java.io.File

/**
 * The GPU path runs inside the vendor OpenCL driver; if that crashes, it takes the process with it
 * and no exception ever reaches Kotlin. So every GPU call is bracketed by a flag written to disk
 * first ([run]). A flag still set when nothing is running in this process means the last GPU
 * call never came back: [consumeCrash] reports it once, and the caller turns the GPU setting off
 * so the next dictation doesn't crash the same way.
 *
 * A false positive (the app killed mid-run for another reason) just turns the setting off; the
 * user can turn it back on.
 */
object GpuGuard {
    private const val PREFS = "gpu_guard"
    private const val KEY_BUSY = "busy"
    private const val KEY_TRIPPED = "tripped"
    private const val KEY_REPORT = "report"

    @Volatile private var inFlight = 0

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun logFile(c: Context) = File(c.applicationContext.filesDir, "gpu.log")

    suspend fun <T> run(context: Context, block: suspend () -> T): T {
        WhisperGpu.logPath = logFile(context).absolutePath
        synchronized(this) {
            inFlight++
            prefs(context).edit().putBoolean(KEY_BUSY, true).commit() // commit: must be on disk before the driver runs
        }
        try {
            return block()
        } finally {
            synchronized(this) {
                inFlight--
                if (inFlight == 0) prefs(context).edit().putBoolean(KEY_BUSY, false).apply()
            }
        }
    }

    /** True once if a previous process died inside [run]. Marks the trip for Settings to show. */
    fun consumeCrash(context: Context): Boolean = synchronized(this) {
        val p = prefs(context)
        if (inFlight == 0 && p.getBoolean(KEY_BUSY, false)) {
            // Capture why now: the next GPU call truncates the log.
            p.edit().putBoolean(KEY_BUSY, false).putBoolean(KEY_TRIPPED, true)
                .putString(KEY_REPORT, report(context)).apply()
            true
        } else false
    }

    /**
     * The crashing frames, from the tombstone Android keeps for a native crash (a protobuf on
     * Android 12+). No parser needed: function and library names sit in it as plain strings,
     * so keep the ones that say where it died.
     */
    private fun backtrace(e: ApplicationExitInfo): String? = runCatching {
        val bytes = e.traceInputStream?.use { it.readBytes() } ?: return null
        val strings = Regex("[\\x20-\\x7e]{6,}").findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value.trim() }
        val keep = listOf("ggml", "whisper", "opencl", "OpenCL", "libGLES", "Adreno", "SIG", "abort", "Abort", "assert")
        strings.filter { s -> keep.any { s.contains(it) } }
            .map { it.substringAfterLast('/').take(120) }
            .distinct().take(8).joinToString("\n").ifBlank { null }
    }.getOrNull()

    /** What the last GPU crash left behind, for Settings to show. */
    fun lastReport(context: Context): String? = prefs(context).getString(KEY_REPORT, null)?.ifBlank { null }

    /**
     * How the process ended (Android 11+) plus the GPU log's last meaningful lines. ggml's
     * OpenCL backend logs the reason and calls exit(1), so the log usually holds the answer.
     */
    private fun report(context: Context): String {
        val exit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.let { e ->
                val why = when (e.reason) {
                    ApplicationExitInfo.REASON_EXIT_SELF -> "exited (status ${e.status})"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                    ApplicationExitInfo.REASON_CRASH -> "crash"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for memory"
                    ApplicationExitInfo.REASON_ANR -> "not responding"
                    else -> "reason ${e.reason}"
                }
                why + (e.description?.let { d -> ": $d" } ?: "") +
                    (if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) backtrace(e)?.let { "\n$it" } ?: "" else "")
            }
        }.getOrNull() else null
        val lines = runCatching { logFile(context).readLines() }.getOrDefault(emptyList())
            .map { it.trim() }.filter { it.isNotEmpty() }
        val important = lines.filter { l ->
            listOf("error", "unsupported", "fail", "abort", "assert").any { l.contains(it, ignoreCase = true) }
        }
        val tail = (important.takeLast(4).ifEmpty { lines.takeLast(4) }).joinToString("\n") { it.take(200) }
        return listOfNotNull(exit, tail.ifBlank { null }).joinToString("\n")
    }

    /** The GPU setting was turned off by a crash and the user hasn't turned it back on since. */
    fun tripped(context: Context): Boolean = prefs(context).getBoolean(KEY_TRIPPED, false)

    fun clearTrip(context: Context) = prefs(context).edit().putBoolean(KEY_TRIPPED, false).apply()
}
