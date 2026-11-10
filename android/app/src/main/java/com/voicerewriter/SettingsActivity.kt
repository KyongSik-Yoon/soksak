package com.voicerewriter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.voicerewriter.ui.MarkCream
import com.voicerewriter.ui.MonoEyebrow
import com.voicerewriter.ui.OpenWisprTheme
import com.voicerewriter.ui.SunsetBrush
import com.voicerewriter.ui.Wordmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: a sectioned, on-device-first surface — setup status, Voice (engine segment +
 * per-model Whisper download manager, the device-fit pick badged as recommended), Bubble,
 * Privacy, Personalization, Reliability, General. Changes persist immediately.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(applicationContext)
        setContent {
            OpenWisprTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { SettingsScreen(repo) { lifecycleScope.launch { it() } } }
                    AppBottomBar(
                        AppTab.SETTINGS,
                        onHome = { AppNav.home(this@SettingsActivity) },
                        onTalk = { AppNav.talk(this@SettingsActivity) },
                        onSettings = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(repo: SettingsRepository, launch: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }

    // permissions / setup
    var bubbleOn by remember { mutableStateOf(BubbleService.isRunning || BubblePrefs.enabled(context)) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var showA11yConsent by remember { mutableStateOf(false) }
    var notifOn by remember { mutableStateOf(true) }
    var micGranted by remember { mutableStateOf(false) }

    // STT
    var sttProvider by remember { mutableStateOf(Defaults.DEFAULT_STT_PROVIDER) }
    var sttEndpoint by remember { mutableStateOf("") }
    var sttKey by remember { mutableStateOf("") }
    var sttModel by remember { mutableStateOf("") }
    var sttLanguage by remember { mutableStateOf(DictationLanguage.DEFAULT) }
    var vadAutoStop by remember { mutableStateOf(true) }
    var autoStopSeconds by remember { mutableStateOf(AUTO_STOP_CHOICES.first()) }
    var headlessRecording by remember { mutableStateOf(true) }
    var promptExample by remember { mutableStateOf(DEFAULT_PROMPT_EXAMPLE) }
    var pipelinedWhisper by remember { mutableStateOf(true) }
    var quietMode by remember { mutableStateOf(false) }
    var quietModel by remember { mutableStateOf(WhisperModelManager.SMALL_ACFT_MODEL) }
    var npuDiagnostics by remember { mutableStateOf(false) }
    var uiLanguage by remember { mutableStateOf(I18n.mode) }
    var githubToken by remember { mutableStateOf("") }
    var updateStatus by remember { mutableStateOf("") }
    var latestBuild by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var reviewBeforeInsert by remember { mutableStateOf(false) }
    var bubbleOnlyOnFields by remember { mutableStateOf(true) }
    var keepHistory by remember { mutableStateOf(DictationHistory.keepHistory(context)) }
    var audioKeepDays by remember { mutableStateOf(PendingAudio.keepDays(context)) }

    // download tracking (a single in-flight download at a time)
    var dlId by remember { mutableStateOf<String?>(null) }
    var dlProgress by remember { mutableFloatStateOf(0f) }
    var modelsRev by remember { mutableStateOf(0) } // bump to recompute readiness after a download

    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (android.provider.Settings.canDrawOverlays(context)) { SetupUtils.startBubble(context); bubbleOn = true }
        else Toast.makeText(context, tr("Permission needed to show the bubble"), Toast.LENGTH_SHORT).show()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notifOn = granted }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> micGranted = granted }
    val a11yLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        a11yEnabled = SetupUtils.accessibilityEnabled(context)
    }
    fun openA11ySettings() = a11yLauncher.launch(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun enableBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifOn) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (!android.provider.Settings.canDrawOverlays(context)) {
            overlayLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        } else { SetupUtils.startBubble(context); bubbleOn = true }
    }

    LaunchedEffect(Unit) {
        val s = repo.get()
        sttProvider = s.sttProvider; sttEndpoint = s.sttEndpoint; sttKey = s.sttKey; sttModel = s.sttModel
        // A blank id, or one a previous build offered (Parakeet, the Hinglish fine-tune), shows
        // here as the downloaded Whisper model that will actually run, so the list has an active
        // row. Only when that model is on disk: an "Active" row hides its own Get button.
        if (sttProvider == "local" && OnDeviceStt.resolveModel(sttModel) != sttModel) {
            val running = OnDeviceStt.resolveModel(context, sttModel)
            if (WhisperModelManager.isReady(context, running)) sttModel = running
        }
        sttLanguage = DictationLanguage.normalize(s.sttLanguage)
        vadAutoStop = s.vadAutoStop; autoStopSeconds = s.autoStopSeconds; headlessRecording = s.headlessRecording; promptExample = s.promptExample; pipelinedWhisper = s.pipelinedWhisper; quietMode = s.quietMode; quietModel = s.quietModel; npuDiagnostics = s.npuDiagnostics; githubToken = s.githubToken; bubbleOnlyOnFields = s.bubbleOnlyOnFields
        reviewBeforeInsert = s.reviewBeforeInsert
        uiLanguage = s.uiLanguage
        // The screen was composed with whatever language was active before the setting was
        // read; redraw it once if the saved choice differs.
        if (I18n.mode != s.uiLanguage) {
            I18n.mode = s.uiLanguage
            (context as? android.app.Activity)?.recreate()
        }
        a11yEnabled = SetupUtils.accessibilityEnabled(context)
        notifOn = SetupUtils.notificationsGranted(context)
        micGranted = SetupUtils.micGranted(context)
        loaded = true
    }

    // Re-check the permission rows every time the screen resumes. None of these grants deliver a
    // result callback (see SetupUtils' header), and accessibility in particular gets revoked out
    // from under the app by OEM battery managers and Play Protect's restricted settings — so a
    // row read once at first composition goes stale and tells the user auto-insert is on when it
    // isn't. This is also where the bubble self-heals if its service was killed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                a11yEnabled = SetupUtils.accessibilityEnabled(context)
                notifOn = SetupUtils.notificationsGranted(context)
                micGranted = SetupUtils.micGranted(context)
                val wanted = BubblePrefs.enabled(context) && SetupUtils.canDrawOverlays(context)
                if (!BubbleService.isRunning && wanted) SetupUtils.startBubble(context)
                // `isRunning` only flips in the service's onCreate, which hasn't happened yet on
                // the line after startForegroundService — so trust the intent we just acted on
                // rather than reading back a flag that is still false.
                bubbleOn = BubbleService.isRunning || wanted
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    if (!loaded) return

    // Device-fit inputs for the on-device model list below. Computed once per screen: reading
    // ActivityManager and free space on every recomposition would be wasteful, and neither
    // answer changes while Settings is open.
    val fit = remember { DeviceFit.plan(context) }
    val recommendedId = fit.sttModel
    val deviceHint = remember { DeviceFit.recommendationLabel(context) }

    // Issue #53: a downloaded model you no longer want. Holds what's pending while the
    // confirmation is up; null when nothing is.
    var pendingDelete by remember { mutableStateOf<DeletableModel?>(null) }

    // Language picker (issue: params.language was hardcoded to "en" in the JNI).
    var pickingLanguage by remember { mutableStateOf(false) }

    fun snapshot() = Settings(
        sttProvider = sttProvider, sttEndpoint = sttEndpoint.trim(), sttKey = sttKey.trim(),
        sttModel = sttModel.trim(), sttLanguage = sttLanguage,
        vadAutoStop = vadAutoStop, autoStopSeconds = autoStopSeconds, headlessRecording = headlessRecording, promptExample = promptExample, pipelinedWhisper = pipelinedWhisper, quietMode = quietMode, quietModel = quietModel, npuDiagnostics = npuDiagnostics, uiLanguage = uiLanguage, githubToken = githubToken.trim(), bubbleOnlyOnFields = bubbleOnlyOnFields,
        reviewBeforeInsert = reviewBeforeInsert,
        hasCompletedOnboarding = true,
    )

    // Persist silently on every change (the design saves immediately).
    fun persist() = launch {
        repo.save(snapshot())
        BubbleService.instance?.refreshGating()
    }

    fun sttModelState(id: String): String = when {
        sttModel == id -> "active"
        dlId == id -> "downloading"
        WhisperModelManager.isReady(context, id) -> "downloaded"
        else -> "idle"
    }

    // Import-only models (no public URL): the user picks the ggml file the convert-model
    // workflow published, and it is copied into the model slot.
    var importTarget by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = importTarget
        importTarget = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        dlId = id; dlProgress = 0f
        launch {
            try {
                WhisperModelManager.importFrom(context, id, uri) { p -> dlProgress = p }
                sttModel = id; dlId = null; modelsRev++; persist()
            } catch (e: Exception) {
                dlId = null
                Toast.makeText(context, e.message ?: tr("Import failed"), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun checkUpdate() {
        updateStatus = tr("Checking…"); latestBuild = null
        AppUpdater.lastResult.value = null // a stale "Installer opened" must not hide the answer
        launch {
            updateStatus = try {
                val r = AppUpdater.latest(githubToken)
                val cur = AppUpdater.installedVersionCode(context)
                if (r.versionCode > cur) { latestBuild = r; trf("Build %s is available.", r.run) }
                else trf("Up to date (build %s).", r.run)
            } catch (e: Exception) { e.message ?: tr("Check failed.") }
        }
    }

    fun installUpdate(r: AppUpdater.Release) {
        if (!AppUpdater.canInstall(context)) {
            updateStatus = tr("Allow OpenWispr to install updates, then tap Update again.")
            context.startActivity(AppUpdater.installPermissionIntent(context))
            return
        }
        updateProgress = 0f
        launch {
            try {
                AppUpdater.downloadAndInstall(context, githubToken, r) { p -> updateProgress = p }
                updateStatus = tr("Installing… confirm if Android asks.")
            } catch (e: Exception) {
                updateStatus = e.message ?: tr("Update failed.")
            } finally {
                updateProgress = null
            }
        }
    }

    // Check on open, so the card says whether a newer build exists without a tap.
    LaunchedEffect(loaded) {
        if (loaded && githubToken.isNotBlank()) checkUpdate()
    }

    fun needsImport(id: String): Boolean = WhisperModelManager.model(id).let {
        it.importOnly || (it.githubHosted && githubToken.isBlank() && it.engine == SpeechEngine.WHISPER)
    }

    fun downloadStt(id: String) {
        dlId = id; dlProgress = 0f
        launch {
            try {
                WhisperModelManager.download(context, id) { p -> dlProgress = p }
                sttModel = id; dlId = null; modelsRev++; persist()
            } catch (e: Exception) {
                dlId = null; Toast.makeText(context, e.message ?: tr("Download failed"), Toast.LENGTH_LONG).show()
            }
        }
    }
    /**
     * Delete a downloaded model and refresh the list. Never called for the active model — the
     * row only offers it on a downloaded, inactive one — so nothing the app is about to load
     * can disappear underneath it.
     */
    fun deleteModel(target: DeletableModel) {
        launch {
            val freed = WhisperModelManager.delete(context, target.id)
            modelsRev++
            val mb = freed / (1024 * 1024)
            Toast.makeText(
                context,
                if (mb > 0) trf("Deleted %s — %sMB freed", target.label, mb) else trf("Deleted %s", target.label),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        HeroHeader()

        Column(
            Modifier.fillMaxWidth().padding(16.dp), // the bottom bar keeps clear of the navigation bar
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // ---------------- SETUP STATUS ----------------
            val setupComplete = bubbleOn && a11yEnabled && micGranted
            SetupStatusCard(setupComplete) {
                if (!setupComplete) {
                    StatusRow(tr("Microphone"), if (micGranted) tr("On") else tr("Needed to hear you"), micGranted) {
                        if (!micGranted) PillButton(tr("Enable")) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    }
                    StatusRow(tr("Floating bubble"), if (bubbleOn) tr("On") else tr("Tap-to-talk over any app"), bubbleOn) {
                        if (!bubbleOn) PillButton(tr("Enable")) { enableBubble() }
                    }
                    StatusRow(tr("Auto-insert"), if (a11yEnabled) tr("On") else tr("Types text into the field you're in"), a11yEnabled) {
                        PillButton(if (a11yEnabled) tr("Manage") else tr("Enable")) {
                            // Enabling is a first-time grant → show the required disclosure first.
                            // "Manage" (already enabled) goes straight to system settings.
                            if (a11yEnabled) openA11ySettings() else showA11yConsent = true
                        }
                    }
                }
            }

            if (pickingLanguage) {
                LanguageDialog(
                    current = sttLanguage,
                    onPick = { code ->
                        sttLanguage = code
                        pickingLanguage = false
                        persist()
                    },
                    onDismiss = { pickingLanguage = false },
                )
            }

            pendingDelete?.let { target ->
                DeleteModelDialog(
                    target = target,
                    onConfirm = { deleteModel(target); pendingDelete = null },
                    onDismiss = { pendingDelete = null },
                )
            }

            if (showA11yConsent) {
                AccessibilityConsentDialog(
                    onConfirm = {
                        showA11yConsent = false
                        AccessibilityConsent.record(context)
                        openA11ySettings()
                    },
                    onDismiss = { showA11yConsent = false },
                )
            }

            // ---------------- VOICE · TRANSCRIPTION ----------------
            Section(tr("Voice · transcription")) {
                Card {
                    Padded {
                        Label(tr("Engine"))
                        Spacer(Modifier.height(12.dp))
                        Segment(
                            options = listOf("local" to tr("On-device"), "groq" to "Groq", "openai" to "OpenAI", "custom" to tr("Custom")),
                            selected = sttProvider,
                            onSelect = {
                                sttProvider = it
                                if (it != "custom" && it != "local") {
                                    val d = Defaults.STT_PROVIDERS[it]?.defaultModel.orEmpty()
                                    if (d.isNotEmpty()) sttModel = d
                                }
                                persist()
                            },
                        )
                    }
                    if (sttProvider == "local") {
                        Divider()
                        Padded {
                            Text(tr("Models download once, then run fully offline."),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            // Which model suits *this* phone, not which is biggest. The list
                            // below shows every option regardless — this is the hint that keeps
                            // someone on a 3GB device from picking the 488MB one and hitting an
                            // out-of-memory failure they have no way to diagnose.
                            Text(deviceHint,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            modelsRev // read so this recomposes after a download finishes
                            sttModelOptions(recommendedId).forEach { m ->
                                ModelRow(
                                    name = m.name, meta = m.meta, recommended = m.recommended,
                                    state = sttModelState(m.id), progress = dlProgress,
                                    // A GitHub-hosted model downloads like the rest once a token is
                                    // set; without one, importing the file is the way in.
                                    getLabel = if (needsImport(m.id)) tr("Import") else tr("Get"),
                                    onGet = {
                                        if (needsImport(m.id)) {
                                            importTarget = m.id
                                            importLauncher.launch(arrayOf("*/*"))
                                        } else downloadStt(m.id)
                                    },
                                    onUse = { NpuLoadGuard.reset(context, m.id); sttModel = m.id; persist() },
                                    onDelete = {
                                        pendingDelete = DeletableModel(m.id, m.name, m.meta)
                                    },
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    } else {
                        Divider()
                        Padded {
                            Label(tr("API key"))
                            Spacer(Modifier.height(8.dp))
                            KeyField(sttKey, Defaults.STT_PROVIDERS[sttProvider]?.let { keyPlaceholder(sttProvider) } ?: tr("key")) { sttKey = it; persist() }
                            if (sttProvider == "custom") {
                                Spacer(Modifier.height(10.dp))
                                KeyField(sttEndpoint, tr("Transcription endpoint URL")) { sttEndpoint = it; persist() }
                                Spacer(Modifier.height(10.dp))
                                KeyField(sttModel, tr("Model id")) { sttModel = it; persist() }
                            }
                            Spacer(Modifier.height(10.dp))
                            InfoNote(buildString {
                                append(tr("Audio is sent to "))
                                append(if (sttProvider == "groq") "Groq" else if (sttProvider == "openai") "OpenAI" else tr("your provider"))
                                append(tr(" for transcription."))
                            }) { sttProvider = "local"; persist() }
                        }
                    }
                    Divider()
                    LanguageRow(current = sttLanguage) { pickingLanguage = true }
                    Divider()
                    Padded {
                        Label(tr("Style example"))
                        Spacer(Modifier.height(6.dp))
                        Text(tr("A sentence the way you usually dictate. Whisper copies its style: English terms stay in English, punctuation matches. Leave blank to turn off."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        KeyField(promptExample, tr("e.g. GitLab에 MR 올려줘.")) { promptExample = it; persist() }
                    }
                    Divider()
                    ToggleRow(
                        tr("Transcribe while you talk"),
                        tr("Whisper models start on what you've said at each pause, so only the last few seconds are left when you stop. Also keeps long dictations from losing sentences."),
                        pipelinedWhisper,
                    ) { pipelinedWhisper = it; persist() }
                    Divider()
                    ToggleRow(tr("Auto-stop on pause"), tr("End recording when you stop talking"), vadAutoStop) { vadAutoStop = it; persist() }
                    if (vadAutoStop) {
                        Divider()
                        // Tap cycles 1.6s → 3s → 5s.
                        NavRow(tr("Auto-stop wait"), trf("Stops after %ss of silence · tap to change", "%.1f".format(autoStopSeconds).removeSuffix(".0"))) {
                            val i = AUTO_STOP_CHOICES.indexOf(autoStopSeconds)
                            autoStopSeconds = AUTO_STOP_CHOICES[(i + 1).mod(AUTO_STOP_CHOICES.size)]
                            persist()
                        }
                    }
                    Divider()
                    ToggleRow(
                        tr("Quiet mode"),
                        tr("For whispering in a quiet office: keeps the whole recording, stops on your own voice level instead of the speech detector, and uses its own model. Also on the bubble's flick-up menu."),
                        quietMode,
                    ) { quietMode = it; persist() }
                    if (quietMode) {
                        Divider()
                        // Tap cycles through downloaded models; Small ACFT hears whispers best.
                        val ready = remember(quietMode) {
                            WhisperModelManager.MODELS.filter { WhisperModelManager.isReady(context, it.id) }
                        }
                        val label = WhisperModelManager.MODELS.firstOrNull { it.id == quietModel }?.label ?: quietModel
                        NavRow(tr("Quiet mode model"), trf("%s · tap to change", label)) {
                            if (ready.isNotEmpty()) {
                                val i = ready.indexOfFirst { it.id == quietModel }
                                quietModel = ready[(i + 1).mod(ready.size)].id
                                persist()
                            }
                        }
                    }
                    Divider()
                    ToggleRow(
                        tr("Record without the sheet"),
                        tr("Keep the app you're typing in focused. On-device only; open OpenWispr once after a reboot"),
                        headlessRecording,
                    ) { headlessRecording = it; persist() }
                    Divider()
                    ToggleRow(
                        tr("Review before inserting"),
                        tr("Show the text and wait, instead of typing it straight into the field"),
                        reviewBeforeInsert,
                    ) { reviewBeforeInsert = it; persist() }
                }
            }

            // ---------------- BUBBLE ----------------
            Section(tr("Bubble")) {
                Card {
                    ToggleRow(tr("Show bubble"), tr("The floating tap-to-talk button"), bubbleOn) { want ->
                        if (want) enableBubble() else { SetupUtils.stopBubble(context); bubbleOn = false }
                    }
                    Divider()
                    // Field gating is driven by the accessibility service (BubbleService.gateActive
                    // needs it to know what's focused). With the grant revoked the toggle keeps
                    // reading "on" while the bubble is in fact always visible, which looks like the
                    // setting broke. Say so instead.
                    ToggleRow(
                        tr("Only on text fields"),
                        if (bubbleOnlyOnFields && !a11yEnabled) tr("Needs auto-insert. The bubble stays visible until you turn it back on.")
                        else tr("Appear only when you can type"),
                        bubbleOnlyOnFields,
                    ) { bubbleOnlyOnFields = it; persist() }
                }
            }

            // ---------------- PRIVACY ----------------
            Section(tr("Privacy")) {
                Card {
                    ToggleRow(tr("Keep history"), tr("Stored on this device · powers personalization"), keepHistory) {
                        keepHistory = it
                        DictationHistory.setKeepHistory(context, it)
                        // "Nothing is saved to disk" has to include the audio, so turning
                        // history off takes the retained recordings with it.
                        if (!it) launch { PendingAudio.purgeAll(context) }
                    }
                    if (keepHistory) {
                        Divider()
                        Padded {
                            Label(tr("Keep audio"))
                            Text(
                                tr("Recordings stay on this device so a dictation that fails can be run again. Never uploaded."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(11.dp))
                            Segment(
                                options = listOf("7" to tr("7 days"), "30" to tr("30 days"), "90" to tr("90 days"), "0" to tr("Forever")),
                                selected = audioKeepDays.toString(),
                                onSelect = { d ->
                                    audioKeepDays = d.toInt()
                                    launch { PendingAudio.setKeepDays(context, audioKeepDays) }
                                },
                            )
                        }
                    }
                    Divider()
                    NavRow(tr("Clear all data"), danger = true, icon = Icons.Default.Delete) {
                        launch {
                            DictationHistory.all(context).forEach { DictationHistory.delete(context, it.id) }
                            PendingAudio.purgeAll(context)
                        }
                        Toast.makeText(context, tr("On-device history cleared"), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // ---------------- PERSONALIZATION ----------------
            Section(tr("Personalization")) {
                Card {
                    NavRow(tr("Personal dictionary"), tr("Names & terms you taught it")) { context.startActivity(Intent(context, VocabActivity::class.java)) }
                    Divider()
                    NavRow(tr("Compare models"), tr("Re-run a recent dictation on every model")) { context.startActivity(Intent(context, CompareActivity::class.java)) }
                    Divider()
                    NavRow(tr("Learned from edits"), tr("Corrections it remembered")) { context.startActivity(Intent(context, LearnedVocabActivity::class.java)) }
                    Divider()
                    NavRow(tr("Import from contacts"), tr("Matched on-device · never uploaded")) { context.startActivity(Intent(context, ContactsImportActivity::class.java)) }
                }
            }

            // ---------------- RELIABILITY ----------------
            Section(tr("Reliability")) {
                Card {
                    // Why the last dictation went to the clipboard instead of the field, so a
                    // screenshot of Settings is enough to diagnose it.
                    OpenWisprAccessibilityService.lastFallback(context)?.let { last ->
                        NavRow(tr("Last clipboard fallback"), last) {}
                        Divider()
                    }
                    if (NpuSupport.available) {
                        ToggleRow(
                            tr("NPU diagnostics"),
                            tr("Adds step-by-step NPU timings to the debug log. Can slow the NPU, so leave it off normally"),
                            npuDiagnostics,
                        ) { npuDiagnostics = it; persist() }
                        Divider()
                    }
                    NavRow(tr("Copy debug log"), tr("This app's recent log, for reporting a problem")) {
                        launch {
                            val log = withContext(Dispatchers.IO) { DebugLog.recent() }
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("openwispr log", log))
                            android.widget.Toast.makeText(context, trf("Copied %s lines", log.lines().size.toString()), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    Divider()
                    NavRow(tr("Auto-start helper"), tr("For Samsung, Xiaomi, OnePlus, Oppo")) {
                        val intent = SetupUtils.oemAutoStartIntents().firstOrNull { it.resolveActivity(context.packageManager) != null }
                            ?: SetupUtils.appInfoIntent(context)
                        runCatching { context.startActivity(intent) }
                    }
                }
            }

            // ---------------- GENERAL ----------------
            // ---------------- FEEDBACK ----------------
            // There was no way to reach us from inside the app at all, so the only channel was a
            // Play review, which we can reply to but not ask questions in. Both rows prefill the
            // version/device details and then hand off to the user's own mail app or browser;
            // nothing is transmitted by us.
            Section(tr("Feedback")) {
                Card {
                    NavRow(tr("Send feedback"), tr("Email us. Ideas, bugs, anything.")) {
                        launchOrNotify(context, Feedback.emailIntent(context), trf("No email app found. Write to %s", Feedback.EMAIL))
                    }
                    Divider()
                    NavRow(tr("Report a problem"), tr("Open an issue on GitHub")) {
                        launchOrNotify(context, Feedback.issueIntent(context), tr("Couldn't open a browser."))
                    }
                    Divider()
                    NavRow(tr("Rate OpenWispr"), tr("Leave a review on Google Play")) {
                        launchOrNotify(context, Feedback.playListingIntent(context), tr("Couldn't open Google Play."))
                    }
                }
            }

            Section(tr("Updates")) {
                Card {
                    Padded {
                        Label(tr("GitHub token"))
                        Spacer(Modifier.height(6.dp))
                        Text(trf("Fine-grained token with Contents: Read-only on %s. Stored only on this phone; used to fetch new builds.", AppUpdater.REPO),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        KeyField(githubToken, "github_pat_…", secret = true) { githubToken = it; persist() }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val installResult by AppUpdater.lastResult.collectAsState()
                            // Newest first: an available build, then the last action's result,
                            // then the check result, then just what's installed.
                            val statusText = latestBuild?.takeIf { updateProgress == null && installResult == null }
                                ?.let { trf("Build %s is available (installed: %s).", it.run, prettyVersion(appVersion(context))) }
                                ?: installResult
                                ?: updateStatus.ifBlank { trf("Installed: %s", prettyVersion(appVersion(context))) }
                            Text(statusText,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f))
                            val avail = latestBuild
                            when {
                                updateProgress != null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                                avail != null -> PillButton(tr("Update")) { installUpdate(avail) }
                                else -> PillOutline(tr("Check")) { checkUpdate() }
                            }
                        }
                        updateProgress?.let { p ->
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Divider()
                    // Silent updates through Shizuku. Polled while Settings is open: Shizuku is
                    // started and its permission granted in other apps/dialogs, with no callback
                    // we'd otherwise see here. pingBinder is a cheap IPC.
                    var shizuku by remember { mutableStateOf(ShizukuInstaller.state(context)) }
                    LaunchedEffect(Unit) {
                        while (true) { shizuku = ShizukuInstaller.state(context); kotlinx.coroutines.delay(2000) }
                    }
                    val (shTitle, shSub) = when (shizuku) {
                        ShizukuInstaller.State.READY -> tr("Silent updates: on") to
                            tr("Shizuku is running. Update installs without the confirmation screens.")
                        ShizukuInstaller.State.NEEDS_PERMISSION -> tr("Silent updates: allow OpenWispr") to
                            tr("Shizuku is running. Tap to let OpenWispr install updates through it.")
                        ShizukuInstaller.State.NOT_RUNNING -> tr("Silent updates: start Shizuku") to
                            tr("Shizuku isn't running (it stops on reboot). Tap to open it and start it with wireless debugging.")
                        ShizukuInstaller.State.NOT_INSTALLED -> tr("Silent updates (optional)") to
                            tr("Install Shizuku to skip the scan and fingerprint screens on every update. Tap to get it.")
                    }
                    NavRow(shTitle, shSub) {
                        when (shizuku) {
                            ShizukuInstaller.State.NEEDS_PERMISSION -> ShizukuInstaller.requestPermission()
                            ShizukuInstaller.State.READY -> {}
                            else -> ShizukuInstaller.openShizuku(context)
                        }
                    }
                }
            }

            Section(tr("General")) {
                Card {
                    NavRow(tr("Replay onboarding"), tr("Walk through setup again")) { context.startActivity(OnboardingActivity.intent(context)) }
                    Divider()
                    // Settings language: phone → 한국어 → English → phone …
                    val phoneKo = java.util.Locale.getDefault().language == "ko"
                    val langLabel = when (uiLanguage) {
                        "ko" -> "한국어"
                        "en" -> "English"
                        else -> trf("Follow phone (%s)", if (phoneKo) "한국어" else "English")
                    }
                    NavRow(tr("Language"), langLabel + " · " + tr("Tap to change")) {
                        uiLanguage = when (uiLanguage) { "auto" -> "ko"; "ko" -> "en"; else -> "auto" }
                        I18n.mode = uiLanguage
                        // Saved before redrawing: the recreated screen reads the setting back and
                        // would otherwise switch to the old language again.
                        launch {
                            repo.save(snapshot())
                            (context as? android.app.Activity)?.recreate()
                        }
                    }
                    Divider()
                    // remember: this is a binder call into PackageManager, and the version can't
                    // change while the screen is up.
                    val version = remember { appVersion(context) }
                    // Label never wraps ("Versio/n"); the value takes what's left and ellipsizes.
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Version"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, softWrap = false)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            prettyVersion(version), style = MonoEyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = TextAlign.End, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}


/* --------------------------- dictation language --------------------------- */

/** The dictation language row. */
@Composable
private fun LanguageRow(current: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr("Dictation language"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                tr("The language you speak while dictating"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            DictationLanguage.label(current),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Picker over whisper.cpp's own language table. Long (100 entries), so it scrolls in a
 * LazyColumn rather than pretending to be a short menu. English sits first because it is the
 * default; the rest are alphabetical.
 */
@Composable
private fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Dictation language")) },
        text = {
            Column {
                Text(
                    tr("Speech is transcribed in this language, never translated."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                    items(DictationLanguage.ALL) { lang ->
                        val selected = lang.code == current
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(lang.code) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                lang.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) Badge(tr("Selected"), Color(0xFF2E6F4E), Color(0xFFDCEFE2))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Done")) } },
    )
}

/* ------------------------ deleting a downloaded model (issue #53) ------------------------ */

private data class DeletableModel(
    val id: String,
    val label: String,
    val size: String,
)

/**
 * Confirmation before removing a downloaded model. Worth a dialog rather than an instant
 * delete: the file is hundreds of megabytes and getting it back means a download, which on a
 * metered connection is a real cost. The copy says exactly that instead of "are you sure?".
 */
@Composable
private fun DeleteModelDialog(target: DeletableModel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(trf("Delete %s?", target.label)) },
        text = {
            Text(
                trf(
                    "This frees %s on this device. The model stays available — you can download it again from this screen whenever you want it, which needs a connection.",
                    target.size.removePrefix("~"),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(tr("Delete"), color = Color(0xFFB4502E)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Keep")) } },
    )
}

/* ------------------------ model option tables ------------------------ */

private data class SttModelOption(val id: String, val name: String, val meta: String, val recommended: Boolean)

/**
 * Every on-device Whisper model, with the "recommended" badge on whichever one [DeviceFit]
 * picked for this phone. The list itself never shrinks — a budget device can still choose the
 * large model deliberately, it just isn't told to.
 */
private fun sttModelOptions(recommendedId: String): List<SttModelOption> =
    WhisperModelManager.available().map { SttModelOption(it.id, it.label, it.sizeLabel, it.id == recommendedId) }

private fun keyPlaceholder(provider: String) = when (provider) {
    "groq" -> "gsk_..."; "openai" -> "sk-..."; else -> tr("key or endpoint")
}

/* ------------------------ reusable pieces ------------------------ */

@Composable
private fun HeroHeader() {
    Column(
        Modifier.fillMaxWidth().background(SunsetBrush).statusBarsPadding().padding(24.dp, 22.dp, 24.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_aperture), null, tint = MarkCream, modifier = Modifier.size(40.dp))
            Text(tr("Settings"), color = MarkCream, style = Wordmark)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title.uppercase(), style = MonoEyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        content()
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
private fun Padded(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SetupStatusCard(complete: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (complete) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFE7F3EA)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = Color(0xFF3E8E5A), modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(if (complete) tr("You're all set") else tr("Finish setup"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(if (complete) tr("Microphone · Bubble · Auto-insert all active") else tr("A couple of quick permissions"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

@Composable
private fun StatusRow(title: String, subtitle: String, on: Boolean, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun Segment(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (id, label) ->
            val sel = id == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(id) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One model in a download list. [onDelete] is only rendered when the model is downloaded and
 * not the active one (issue #53: a second model you no longer want had no way off the device —
 * only "Use"). Deleting the active model is deliberately not offered: switch first, then delete.
 */
@Composable
private fun ModelRow(
    name: String, meta: String, recommended: Boolean, state: String, progress: Float,
    onGet: () -> Unit, onUse: () -> Unit, onDelete: (() -> Unit)? = null, getLabel: String = tr("Get"),
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, cs.outline, RoundedCornerShape(12.dp)).padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                // Name and badge on separate lines: side by side, a long name ("Small ACFT
                // (FUTO)") squeezed the badge to one character wide and it wrapped vertically.
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                if (recommended) {
                    Spacer(Modifier.height(4.dp))
                    Badge(tr("Recommended"), Color(0xFF3E8E5A), Color(0xFFE7F3EA))
                    Spacer(Modifier.height(4.dp))
                }
                Text(meta, style = MonoEyebrow, fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
            when (state) {
                "active" -> Badge(tr("Active"), Color(0xFF3E8E5A), Color(0xFFE7F3EA))
                "downloaded" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = trf("Delete %s", name),
                                tint = cs.onSurfaceVariant,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                    PillOutline(tr("Use"), onUse)
                }
                "downloading" -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = cs.primary)
                else -> PillButton(getLabel, onGet)
            }
        }
        if (state == "downloading") {
            Spacer(Modifier.height(11.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.outline)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(cs.primary))
            }
        }
    }
}

@Composable
private fun Badge(text: String, fg: Color, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text.uppercase(), style = MonoEyebrow, fontSize = 10.sp, color = fg, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(title: String, subtitle: String? = null, danger: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val color = if (danger) Color(0xFFB4502E) else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(icon ?: Icons.Filled.ChevronRight, null, tint = if (danger) color else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun InfoNote(text: String, onSwitch: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(tr("Switch to on-device to keep everything private."), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onSwitch() })
    }
}

@Composable
private fun KeyField(value: String, placeholder: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(cs.surface).border(1.5.dp, cs.outline, RoundedCornerShape(11.dp)).padding(13.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                inner()
            },
        )
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun PillOutline(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * The installed version, for the Settings footer. This used to be the string literal "1.0", which
 * had been wrong since 1.0.1 and told anyone reporting a bug the wrong version to report against.
 *
 * Read from PackageManager rather than BuildConfig so it reflects the APK actually on the device,
 * and because BuildConfig generation is off by default in AGP 8 and would need a new build flag
 * for one string.
 */
/**
 * Start [intent], or say why it didn't work. A phone with no mail client (or no browser) is
 * unusual but real, and an unhandled ActivityNotFoundException would crash the whole Settings
 * screen for a tap on a feedback row.
 */
private fun launchOrNotify(context: Context, intent: Intent, fallback: String) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, fallback, Toast.LENGTH_LONG).show()
    }
}

/** "1.6.0-dev.15" -> "1.6.0 · build 15"; anything else unchanged. */
private fun prettyVersion(raw: String): String =
    Regex("""^(.+)-dev\.(\d+)$""").find(raw)?.let { "${it.groupValues[1]} · build ${it.groupValues[2]}" } ?: raw

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
} catch (_: Exception) {
    // Can only really happen if the package is being replaced out from under us.
    "unknown"
}
