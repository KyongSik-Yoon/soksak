package com.voicerewriter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * In-app updates for this personal fork. CI publishes every main build as a GitHub Release
 * `build-<run>` carrying the APK, and stamps the APK with versionCode `1000 + run`. This checks
 * the latest release, downloads its APK and opens it in the system package installer.
 *
 * The GitHub token is optional. A public repo's releases need no login (anonymous API calls are
 * limited to 60 an hour per IP, plenty for a check per app open); a private repo needs a
 * fine-grained, read-only ("Contents: Read") token pasted into Settings. When set, it is only
 * ever sent to api.github.com (OkHttp drops the Authorization header when the asset download
 * redirects to the storage host).
 *
 * The system installer asks the user to confirm (plus Play Protect and biometrics on the user's
 * phone); see [downloadAndInstall] for why it is not a session of our own.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    const val REPO = "KyongSik-Yoon/soksak"
    private const val TAG_PREFIX = "build-"
    private const val VERSION_BASE = 1000L

    data class Release(val run: Int, val versionCode: Long, val assetId: Long, val assetName: String, val size: Long, val notes: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Adds the token when there is one; without it the call goes out anonymously. */
    private fun Request.Builder.auth(token: String): Request.Builder =
        apply { if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }

    /** The message for a failed API call, which depends on whether a token was sent. */
    internal fun failure(code: Int, token: String, what: String): String = when {
        code == 401 -> "GitHub rejected the token (expired or wrong)."
        code == 403 && token.isBlank() -> "GitHub's hourly limit for checks without a token is used up. Try again later, or add a token in Settings > Updates."
        code == 404 && token.isBlank() -> "Couldn't see $what. If $REPO is private, add a GitHub token in Settings > Updates."
        code == 403 || code == 404 -> "The token can't read $REPO. Give it Contents: Read on that repo."
        else -> "GitHub returned $code."
    }

    private val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /**
     * A short-lived, unauthenticated download URL for asset [name] of release [tag] — what the
     * API's asset endpoint redirects to. Handing that to [ModelDownloader] keeps its resume and
     * size checks, and the token (if any) never leaves api.github.com.
     */
    suspend fun releaseAssetUrl(token: String, tag: String, name: String): String = withContext(Dispatchers.IO) {
        val rel = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/tags/$tag")
            .auth(token)
            .header("Accept", "application/vnd.github+json")
            .build()
        val assetId = http.newCall(rel).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException(failure(res.code, token, "release $tag"))
            val assets = JSONObject(res.body?.string().orEmpty()).optJSONArray("assets")
            (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name") == name }?.getLong("id")
                ?: throw IllegalStateException("Release $tag has no $name.")
        }
        val dl = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/assets/$assetId")
            .auth(token)
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
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .auth(token)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (res.code != 200) throw IllegalStateException(failure(res.code, token, "the releases"))
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

    /** Last update step result, shown in Settings > Updates and the update dialog. */
    val lastResult = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls() ||
            ShizukuInstaller.state(context) == ShizukuInstaller.State.READY

    /** The system screen where the user allows this app to install updates. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Download the release APK to the cache and hand it to the **system package installer**,
     * exactly as a browser download does.
     *
     * Why not a PackageInstaller session of our own: on the user's Galaxy that path is a "self
     * update" (the app installing a new version of itself) and is refused with
     * `INSTALL_FAILED_ABORTED: Self update is blocked by unknown source package` whenever the
     * installed copy's installer of record is this app — i.e. every second in-app update, after
     * the first one made us the installer. Explicit USER_ACTION_REQUIRED did not help. Installs
     * opened from the browser always worked; with ACTION_VIEW the session belongs to the system
     * installer, the same path.
     */
    suspend fun downloadAndInstall(context: Context, token: String, release: Release, onProgress: (Float) -> Unit) {
        val app = context.applicationContext
        val apk = withContext(Dispatchers.IO) {
            val dir = java.io.File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // older downloads
            val out = java.io.File(dir, "soksak-${release.run}.apk")
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/assets/${release.assetId}")
                .auth(token)
                .header("Accept", "application/octet-stream")
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException(failure(res.code, token, "the APK"))
                val body = res.body ?: throw IllegalStateException("Empty download.")
                val total = body.contentLength().takeIf { it > 0 } ?: release.size
                out.outputStream().use { o ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            out
        }
        onProgress(1f)
        // Shizuku running and allowed: install as the shell user, no confirmation screens.
        // Anything going wrong there falls through to the system installer below.
        if (ShizukuInstaller.state(app) == ShizukuInstaller.State.READY) {
            lastResult.value = "Installing via Shizuku… the app restarts when done."
            try {
                ShizukuInstaller.install(apk)
                return // usually not reached: replacing the app kills this process
            } catch (e: Exception) {
                android.util.Log.w("AppUpdater", "Shizuku install failed; using the system installer", e)
                lastResult.value = "Shizuku install failed (${e.message?.take(80)}); opening the installer."
            }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) { app.startActivity(view) }
        lastResult.value = "Installer opened. Confirm there to finish."
    }
}
