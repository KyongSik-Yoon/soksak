package com.voicerewriter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** In-memory throttle so reopening Home doesn't hit the GitHub API every time. */
object UpdateCheck {
    private const val INTERVAL_MS = 10 * 60 * 1000L
    private var lastCheck = 0L
    /** A build the user said "Later" to: not offered again until a newer one appears. */
    var dismissedRun: Int = -1

    fun due(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCheck < INTERVAL_MS) return false
        lastCheck = now
        return true
    }
}

/**
 * Game-style update prompt: when OpenWispr opens and a newer build is published, offer it in a
 * dialog with one Update button that downloads and installs in place. Silent when no token is
 * set, when offline, or when already current.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var token by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!UpdateCheck.due()) return@LaunchedEffect
        val t = runCatching { SettingsRepository(context.applicationContext).get().githubToken }.getOrDefault("")
        if (t.isBlank()) return@LaunchedEffect
        val r = runCatching { AppUpdater.latest(t) }.getOrNull() ?: return@LaunchedEffect
        if (r.versionCode > AppUpdater.installedVersionCode(context) && r.run != UpdateCheck.dismissedRun) {
            token = t
            release = r
        }
    }

    val r = release ?: return
    val busy = progress != null
    fun later() { UpdateCheck.dismissedRun = r.run; release = null }

    AlertDialog(
        onDismissRequest = { if (!busy) later() },
        title = { Text("Update available · build ${r.run}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(r.notes.ifBlank { "A new build of OpenWispr is ready." }, style = MaterialTheme.typography.bodyMedium)
                progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
                val installResult by AppUpdater.lastResult.collectAsState()
                (installResult ?: message)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (!AppUpdater.canInstall(context)) {
                    message = "Allow OpenWispr to install updates, then come back and tap Update."
                    context.startActivity(AppUpdater.installPermissionIntent(context))
                    return@TextButton
                }
                message = null
                progress = 0f
                scope.launch {
                    try {
                        AppUpdater.downloadAndInstall(context, token, r) { p -> progress = p }
                        message = "Installing… confirm if Android asks."
                    } catch (e: Exception) {
                        message = e.message ?: "Update failed."
                    } finally {
                        progress = null
                    }
                }
            }) { Text("Update") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { later() }) { Text("Later") } },
    )
}
