package com.voicerewriter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.net.URLEncoder

/**
 * Reporting a problem from inside the app: GitHub's new-issue form on this fork, prefilled with
 * the diagnostic details every report needs. Nothing is sent by the app itself; the browser opens
 * with the text filled in and the user decides whether to submit. (The upstream app's email and
 * Play Store rows went to the original author, so this fork dropped them.)
 */
object Feedback {

    const val REPO = "https://github.com/${AppUpdater.REPO}"

    /**
     * Version, phone and OS. Deliberately nothing that identifies a person: no install id, no
     * account, no dictation content.
     */
    fun diagnostics(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        return buildString {
            append("App: Soksak ").append(version).append('\n')
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
        }
    }

    /** GitHub's new-issue form, prefilled with a template and the diagnostics. */
    fun issueIntent(context: Context): Intent {
        val body = buildString {
            append("**What happened?**\n\n\n")
            append("**What did you expect?**\n\n\n")
            append("**Steps to reproduce**\n\n\n")
            append("---\n")
            append(diagnostics(context))
            append('\n')
        }
        val url = "$REPO/issues/new?title=" + enc("") + "&body=" + enc(body)
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    /**
     * URLEncoder is form encoding, which turns a space into "+". %20 is unambiguous wherever the
     * prefilled text ends up, so use that.
     */
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
