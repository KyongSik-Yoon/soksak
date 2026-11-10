package com.voicerewriter

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * The model a new install starts with, and whether it fits. Small ACFT is the smallest model the
 * app offers, so memory and storage can't pick a smaller one; running out of space is only worth
 * saying before a 264MB download starts rather than partway through it.
 */
object DeviceFit {

    private const val GIB = 1024L * 1024L * 1024L
    private const val MIB = 1024L * 1024L

    /** Headroom over the weights themselves: unpacking, the .part file, and room to breathe. */
    private const val STORAGE_MARGIN_BYTES = 350L * MIB

    /** Approximate download size, matching the model's sizeLabel. Only used for "will this fit". */
    private const val STT_BYTES = 264L * MIB

    /**
     * @param sttModel a [WhisperModelManager] model id.
     * @param storageShortMb how many MB short of the model this device is, or 0 when it fits.
     */
    data class Plan(
        val sttModel: String,
        val storageShortMb: Long = 0,
    ) {
        val downloadMb: Long
            get() = STT_BYTES / MIB
    }

    /** Total physical RAM as Android reports it, or 0 if the service is unavailable. */
    fun totalRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        return runCatching {
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        }.getOrDefault(0L)
    }

    /** Free bytes on the partition the models are written to (`filesDir`). */
    fun freeStorageBytes(context: Context): Long =
        runCatching { context.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)

    fun plan(context: Context): Plan {
        val free = freeStorageBytes(context)
        val plan = planFor(free)
        Log.i(
            "DeviceFit",
            "free=${free / MIB}MB device=${Build.MANUFACTURER} ${Build.MODEL} -> " +
                "${plan.sttModel}, ${plan.downloadMb}MB, short ${plan.storageShortMb}MB",
        )
        return plan
    }

    /** The decision itself, with the device read hoisted out. See DeviceFitTest. */
    fun planFor(freeBytes: Long): Plan = Plan(
        sttModel = WhisperModelManager.SMALL_ACFT_MODEL,
        storageShortMb = (STT_BYTES + STORAGE_MARGIN_BYTES - freeBytes).coerceAtLeast(0) / MIB,
    )

    /** One line for the user, or null when there is nothing worth saying. */
    fun explain(plan: Plan): String? =
        if (plan.storageShortMb > 0)
            "This phone is about ${plan.storageShortMb}MB short of free space for the speech model. " +
                "Free some up and reopen Soksak, or the download will stop partway."
        else null

    /** Short label for Settings, where the user is choosing a model themselves. */
    fun recommendationLabel(context: Context): String {
        val plan = plan(context)
        val ramGb = totalRamBytes(context).toDouble() / GIB
        val ram = if (ramGb > 0) String.format("%.1fGB RAM", ramGb) else "unknown RAM"
        val stt = WhisperModelManager.model(plan.sttModel).label
        return "This device: $ram — recommended: $stt"
    }
}
