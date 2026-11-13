package com.voicerewriter

import android.os.Build

/**
 * Whether this phone can run the NPU (Qualcomm QNN, HTP backend) models, and on which chip.
 *
 * A model is compiled per chip (a QNN context binary), and the APK carries the HTP stub and skel
 * of each chip's Hexagon version (build.gradle.kts keeps just those from the QNN runtime AAR): v81 for Snapdragon
 * 8 Elite Gen 5 = SM8850, the user's Galaxy S26 Ultra, and v79 for Snapdragon 8 Elite = SM8750
 * (Galaxy Z Fold7; added 2026-11-11 and not yet run on one). Other chips don't see the NPU models.
 */
object NpuSupport {
    /** The chip names model bundles are built for, as Build.SOC_MODEL starts. */
    const val SM8850 = "SM8850"
    const val SM8750 = "SM8750"
    private val SOCS = listOf(SM8850, SM8750)

    /** This phone's chip when it is one of [SOCS]; null otherwise. */
    val soc: String? by lazy {
        if (Build.VERSION.SDK_INT >= 31) socOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL) else null
    }

    val available: Boolean get() = soc != null

    /** The chip a bundle name is spelled with: this phone's, or the first one where there is none. */
    val bundleSoc: String get() = soc ?: SM8850

    internal fun socOf(manufacturer: String?, model: String?): String? {
        val m = model?.uppercase() ?: return null
        if (!(manufacturer == null || manufacturer.equals("QTI", true) || manufacturer.equals("Qualcomm", true))) return null
        return SOCS.firstOrNull { m.startsWith(it) }
    }

    internal fun supports(manufacturer: String?, model: String?): Boolean = socOf(manufacturer, model) != null
}
