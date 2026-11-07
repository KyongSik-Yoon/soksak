package com.voicerewriter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * In-app updates for this personal fork. CI publishes every main build as a GitHub Release
 * `build-<run>` carrying the APK, and stamps the APK with versionCode `1000 + run`. This checks
 * the latest release, downloads its APK and hands it to [PackageInstaller].
 *
 * The repo is private, so the GitHub API needs a token: a fine-grained, read-only ("Contents:
 * Read") token the user creates and pastes into Settings. It is only ever sent to api.github.com
 * (OkHttp drops the Authorization header when the asset download redirects to the storage host).
 *
 * Android asks the user to confirm every install (plus Play Protect and biometrics on the user's
 * phone); a silent self-update is refused for sideloaded apps, see [downloadAndInstall].
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    const val REPO = "KyongSik-Yoon/openWispr"
    private const val TAG_PREFIX = "build-"
    private const val VERSION_BASE = 1000L

    data class Release(val run: Int, val versionCode: Long, val assetId: Long, val assetName: String, val size: Long, val notes: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /**
     * A short-lived, unauthenticated download URL for asset [name] of release [tag] — what the
     * API's asset endpoint redirects to. Handing that to [ModelDownloader] keeps its resume and
     * size checks, and the token never leaves api.github.com.
     */
    suspend fun releaseAssetUrl(token: String, tag: String, name: String): String = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Add a GitHub token in Settings > Updates first." }
        val rel = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/tags/$tag")
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/vnd.github+json")
            .build()
        val assetId = http.newCall(rel).execute().use { res ->
            if (res.code == 401) throw IllegalStateException("GitHub rejected the token (expired or wrong).")
            if (!res.isSuccessful) throw IllegalStateException("Couldn't find release $tag (${res.code}).")
            val assets = JSONObject(res.body?.string().orEmpty()).optJSONArray("assets")
            (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name") == name }?.getLong("id")
                ?: throw IllegalStateException("Release $tag has no $name.")
        }
        val dl = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/assets/$assetId")
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/octet-stream")
            .build()
        noRedirect.newCall(dl).execute().use { res ->
            res.header("Location")?.takeIf { res.isRedirect }
                ?: throw IllegalStateException("GitHub didn't return a download link (${res.code}).")
        }
    }

    fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** The newest build release, or throws with a message fit for a toast. */
    suspend fun latest(token: String): Release = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Add a GitHub token first." }
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            when (res.code) {
                200 -> {}
                401 -> throw IllegalStateException("GitHub rejected the token (expired or wrong).")
                403, 404 -> throw IllegalStateException("The token can't read $REPO. Give it Contents: Read on that repo.")
                else -> throw IllegalStateException("GitHub returned ${res.code}.")
            }
            val o = JSONObject(body)
            val tag = o.optString("tag_name")
            val run = tag.removePrefix(TAG_PREFIX).toIntOrNull()
                ?: throw IllegalStateException("Latest release '$tag' is not an app build.")
            val assets = o.optJSONArray("assets")
            var asset: JSONObject? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { asset = a; break }
            }
            asset ?: throw IllegalStateException("Build $run has no APK attached.")
            Release(
                run = run,
                versionCode = VERSION_BASE + run,
                assetId = asset.getLong("id"),
                assetName = asset.optString("name"),
                size = asset.optLong("size", -1),
                notes = o.optString("body"),
            )
        }
    }

    /** Last install outcome reported by [InstallResultReceiver], shown in Settings > Updates. */
    val lastResult = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** The system screen where the user allows this app to install updates. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Stream the release APK straight into a PackageInstaller session and commit it. The result
     * (including the confirm prompt, when Android wants one) arrives at [InstallResultReceiver].
     */
    suspend fun downloadAndInstall(context: Context, token: String, release: Release, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                // Always let Android ask. A silent self-update (USER_ACTION_NOT_REQUIRED) fails on
                // the user's Galaxy in both states this app can be in: sideloaded from the browser
                // ("Self update…" abort), and after it became its own installer of record
                // ("Self update is blocked by unknown source package"), because the package's
                // original source is still an unknown-sources install. Asking costs one tap.
                // Explicit rather than UNSPECIFIED, so the OS never takes the silent path itself.
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                if (release.size > 0) setSize(release.size)
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$REPO/releases/assets/${release.assetId}")
                    .header("Authorization", "Bearer ${token.trim()}")
                    .header("Accept", "application/octet-stream")
                    .build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw IllegalStateException("Download failed (${res.code}).")
                    val body = res.body ?: throw IllegalStateException("Empty download.")
                    val total = body.contentLength().takeIf { it > 0 } ?: release.size
                    session.openWrite("base.apk", 0, if (total > 0) total else -1).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(1 shl 16)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                            session.fsync(out)
                        }
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(
                    app, sessionId, Intent(app, InstallResultReceiver::class.java), flags,
                )
                lastResult.value = null
                session.commit(pi.intentSender)
                onProgress(1f)
            } catch (e: Exception) {
                runCatching { session.abandon() }
                throw e
            } finally {
                session.close()
            }
        }
}

/** Receives the PackageInstaller result: show the confirm screen, or say what went wrong. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i("AppUpdater", "update installed")
                AppUpdater.lastResult.value = "Installed."
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.w("AppUpdater", "install failed: $msg")
                AppUpdater.lastResult.value = "Update failed: $msg"
                Toast.makeText(context, "Update failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
