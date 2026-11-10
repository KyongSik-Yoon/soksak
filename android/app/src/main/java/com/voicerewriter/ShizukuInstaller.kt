package com.voicerewriter

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.File

/**
 * Installs app updates through Shizuku (https://shizuku.rikka.app), i.e. as the ADB shell user,
 * so an update needs no confirmation screen at all.
 *
 * Why: every in-app update otherwise ends in the system installer, where Play Protect asks to
 * scan the (always new) APK and "install without scanning" then wants a fingerprint. A
 * PackageInstaller session of our own with USER_ACTION_NOT_REQUIRED would be the API answer,
 * but on the user's Galaxy it is refused as a self update ("Self update is blocked by unknown
 * source package"; see AppUpdater). `pm install` run by the shell user is an ADB install, which
 * neither rule applies to.
 *
 * Shizuku is started over wireless debugging on the phone itself and must be started again
 * after a reboot. When it isn't running or Soksak isn't allowed, updates fall back to the
 * system installer as before.
 */
object ShizukuInstaller {
    private const val TAG = "ShizukuInstaller"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 4711

    enum class State { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

    fun state(context: Context): State {
        val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
        return try {
            when {
                !Shizuku.pingBinder() -> if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
                Shizuku.isPreV11() -> State.NOT_RUNNING // too old to use; treat as unavailable
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
                else -> State.NEEDS_PERMISSION
            }
        } catch (t: Throwable) {
            if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
        }
    }

    /** Shows Shizuku's own "allow Soksak?" dialog. */
    fun requestPermission() = runCatching { Shizuku.requestPermission(REQUEST_CODE) }

    /** Opens Shizuku if installed (to start it), else its Play Store page. */
    fun openShizuku(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"))
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * `pm install -r -S <size>` with the APK streamed on stdin (the shell user can't read our
     * cache directory). Installing over ourselves kills this process on success, so a return
     * only ever means failure was reported, or Android didn't kill us yet.
     */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val proc = newProcess(arrayOf("sh", "-c", "pm install -r -S ${apk.length()}"))
        proc.outputStream.use { out -> apk.inputStream().use { it.copyTo(out) } }
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        Log.i(TAG, "pm install exit=$code out=${stdout.trim()} err=${stderr.trim()}")
        if (code != 0 || !stdout.contains("Success")) {
            throw IllegalStateException((stdout + " " + stderr).trim().ifBlank { "pm install failed ($code)" })
        }
    }

    // Shizuku 13 keeps newProcess private in favour of UserService, which needs an AIDL
    // service for what is one shell command here; it is still the same binder call.
    private fun newProcess(cmd: Array<String>): ShizukuRemoteProcess {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as ShizukuRemoteProcess
    }
}
