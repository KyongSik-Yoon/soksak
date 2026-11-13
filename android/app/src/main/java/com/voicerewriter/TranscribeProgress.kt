package com.voicerewriter

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * How far the transcription after the user stops has got, for the ring on the bubble and on the
 * lock-screen dictation screen. A long take used to show the same "working" animation for 2s or
 * 20s, so a slow decode looked the same as a hung one.
 *
 * Neither whisper.cpp nor sherpa reports progress inside a decode, so this is an estimate:
 * the audio still to decode × this model's measured compute seconds per audio second, corrected
 * whenever a piece really finishes. It never closes the ring on its own: past [LINEAR_UNTIL] it
 * creeps toward [CAP], and only the finished text ([end]) shows 1.
 */
object TranscribeProgress {
    const val CAP = 0.94f
    const val LINEAR_UNTIL = 0.8f

    /** Compute seconds per audio second, learned from each decode. */
    private val rates = ConcurrentHashMap<String, Float>()

    /**
     * Before a model has decoded anything in this process. From the phone runs in CLAUDE.md
     * (Run all, ~5s sentences): Small ACFT CPU 0.27, Turbo NPU 0.19, Medium NPU 0.43; Turbo CPU
     * is ~4.6x Small on the x86 bench.
     */
    internal fun defaultRate(model: String): Float = when (model) {
        WhisperModelManager.TURBO_ACFT_Q4_MODEL -> 1.25f
        WhisperModelManager.MEDIUM_NPU_MODEL -> 0.45f
        WhisperModelManager.TURBO_NPU_MODEL -> 0.2f
        else -> 0.3f
    }

    fun rate(model: String): Float = rates[model] ?: defaultRate(model)

    /** A decode of [audioSec] took [computeMs]: blend it into the model's rate. */
    fun record(model: String, audioSec: Float, computeMs: Long) {
        if (audioSec < 0.5f || computeMs <= 0) return
        val r = computeMs / 1000f / audioSec
        rates[model] = rates[model]?.let { 0.5f * it + 0.5f * r } ?: r
    }

    private class Job(val startMs: Long, val totalSec: Float, val rate: Float) {
        @Volatile var doneSec = 0f
        @Volatile var shown = 0f
    }

    @Volatile private var job: Job? = null

    /** The user stopped; [remainingSec] of audio is still to decode with [model]. */
    fun begin(model: String, remainingSec: Float, nowMs: Long) {
        job = Job(nowMs, max(remainingSec, 0.1f), rate(model))
    }

    /** A piece of [audioSec] finished decoding (ignored when nothing is being waited on). */
    fun pieceDone(audioSec: Float) {
        val j = job ?: return
        j.doneSec = min(j.totalSec, j.doneSec + audioSec)
    }

    fun end() { job = null }

    /** 0..[CAP] while a decode is being waited on, never going backwards; null otherwise. */
    fun fraction(nowMs: Long): Float? {
        val j = job ?: return null
        val expectedSec = max(j.totalSec * j.rate, 0.3f)
        val byTime = shape((nowMs - j.startMs) / 1000f / expectedSec)
        val byPieces = min(CAP, j.doneSec / j.totalSec)
        val f = max(j.shown, max(byTime, byPieces))
        j.shown = f
        return f
    }

    /** Linear while the estimate holds, then an ever slower approach to [CAP] once it has run out. */
    internal fun shape(x: Float): Float =
        if (x <= LINEAR_UNTIL) max(0f, x)
        else LINEAR_UNTIL + (CAP - LINEAR_UNTIL) * (1f - exp(-(x - LINEAR_UNTIL) * 4f))
}
