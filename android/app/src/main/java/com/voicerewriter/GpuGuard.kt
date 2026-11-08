package com.voicerewriter

import android.content.Context

/**
 * The GPU path runs inside a vendor Vulkan driver; if that crashes, it takes the process with it
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

    @Volatile private var inFlight = 0

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun <T> run(context: Context, block: suspend () -> T): T {
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
            p.edit().putBoolean(KEY_BUSY, false).putBoolean(KEY_TRIPPED, true).apply()
            true
        } else false
    }

    /** The GPU setting was turned off by a crash and the user hasn't turned it back on since. */
    fun tripped(context: Context): Boolean = prefs(context).getBoolean(KEY_TRIPPED, false)

    fun clearTrip(context: Context) = prefs(context).edit().putBoolean(KEY_TRIPPED, false).apply()
}
