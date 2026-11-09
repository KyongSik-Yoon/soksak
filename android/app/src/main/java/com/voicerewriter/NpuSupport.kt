package com.voicerewriter

import android.os.Build

/**
 * Whether this phone can run the NPU (Qualcomm QNN, HTP backend) models.
 *
 * The APK carries the HTP stub/skel for Hexagon v81 only (CI copies just those from the QNN
 * runtime), which is Snapdragon 8 Elite Gen 5 = SM8850 — the user's Galaxy S26 Ultra. Other
 * chips need their own libQnnHtpV*Stub/Skel, so they don't see the NPU models at all.
 */
object NpuSupport {
    private val SOCS = listOf("SM8850")

    val available: Boolean by lazy {
        Build.VERSION.SDK_INT >= 31 && supports(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
    }

    internal fun supports(manufacturer: String?, model: String?): Boolean {
        val m = model?.uppercase() ?: return false
        return (manufacturer == null || manufacturer.equals("QTI", true) || manufacturer.equals("Qualcomm", true)) &&
            SOCS.any { m.startsWith(it) }
    }
}
