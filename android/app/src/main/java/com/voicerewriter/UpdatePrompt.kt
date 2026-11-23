package com.voicerewriter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** In-memory throttle so reopening Home doesn't hit the GitHub API every time. */
object UpdateCheck {
    private const val INTERVAL_MS = 10 * 60 * 1000L
    private var lastCheck = 0L
    /** A build the user said "Later" to: not offered again until a newer one appears. */
    var dismissedRun: Int = -1
    /**
     * The newer build the last check found, or null. Held here rather than in the prompt so the
     * banner on Home and the Updates page in Settings show the same answer, and so it outlives
     * "Later" and a recreated Home (the throttle would not check again for ten minutes).
     */
    var available by mutableStateOf<AppUpdater.Release?>(null)
    /** Whether the dialog is up: opened by the check for a build not yet declined, or by the banner. */
    var promptOpen by mutableStateOf(false)

    /**
     * Record what a check found. A newer build is always kept for the banner; the dialog opens
     * by itself only when [prompt] is set and that build was not already answered with "Later".
     */
    fun offer(latest: AppUpdater.Release, installedCode: Long, prompt: Boolean) {
        if (latest.versionCode <= installedCode) { available = null; promptOpen = false; return }
        available = latest
        if (prompt && latest.run != dismissedRun) promptOpen = true
    }

    fun due(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCheck < INTERVAL_MS) return false
        lastCheck = now
        return true
    }
}

/**
 * Game-style update prompt: when Soksak opens and a newer build is published, offer it in a
 * dialog with one Update button that downloads and installs in place. Silent when the check
 * fails (offline, rate limit) or when already current.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // On every resume, not once per activity: the bubble keeps the process alive and Home is
    // usually resumed rather than recreated, so a check tied to creation ran about once a day.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && UpdateCheck.due()) scope.launch {
                val r = runCatching { AppUpdater.latest() }.getOrNull() ?: return@launch
                UpdateCheck.offer(r, AppUpdater.installedVersionCode(context), prompt = true)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val r = UpdateCheck.available?.takeIf { UpdateCheck.promptOpen } ?: return
    val busy = progress != null
    // "Later" only stops the dialog from coming back by itself; the banner stays.
    fun later() { UpdateCheck.dismissedRun = r.run; UpdateCheck.promptOpen = false }

    AlertDialog(
        onDismissRequest = { if (!busy) later() },
        title = { Text("Update available · build ${r.run}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(r.notes.ifBlank { "A new build of Soksak is ready." }, style = MaterialTheme.typography.bodyMedium)
                progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
                val installResult by AppUpdater.lastResult.collectAsState()
                (installResult ?: message)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (!AppUpdater.canInstall(context)) {
                    message = "Allow Soksak to install updates, then come back and tap Update."
                    context.startActivity(AppUpdater.installPermissionIntent(context))
                    return@TextButton
                }
                message = null
                progress = 0f
                scope.launch {
                    try {
                        AppUpdater.downloadAndInstall(context, r) { p -> progress = p }
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

/**
 * A strip saying a newer build is out, for the top of Home and of the Settings index. Draws
 * nothing when there is none. Tapping opens the update dialog unless the caller sends it elsewhere.
 */
/**
 * On Home while Accessibility is on but not connected ([SetupUtils.accessibilityStuck]); opens
 * the system's Accessibility settings. In the error colours: until it is fixed nothing is typed.
 */
@Composable
fun AccessibilityStuckBanner(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.errorContainer).clickable { onClick() }.padding(16.dp, 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr("Accessibility is on but not connected, so text goes to the clipboard. Turn Soksak off and on again in Accessibility."),
            style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(tr("Open"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cs.onErrorContainer)
    }
}

@Composable
fun UpdateBanner(modifier: Modifier = Modifier, onClick: () -> Unit = { UpdateCheck.promptOpen = true }) {
    val r = UpdateCheck.available ?: return
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.primary).clickable { onClick() }.padding(16.dp, 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.SystemUpdate, null, tint = cs.onPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(trf("Build %s is available.", r.run), style = MaterialTheme.typography.bodyMedium, color = cs.onPrimary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(tr("Update"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cs.onPrimary)
    }
}
