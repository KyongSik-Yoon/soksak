package com.voicerewriter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.voicerewriter.ui.MarkLight
import com.voicerewriter.ui.MonoEyebrow
import com.voicerewriter.ui.trackedFor
import com.voicerewriter.ui.SoksakTheme
import com.voicerewriter.ui.DuskBrush
import com.voicerewriter.ui.Wordmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: an index of grouped rows (Dictation, Bubble & PC, Personalization, App) that opens
 * one page per section ([SettingsPage]); rows that already have their own screen (dictionary,
 * Compare models) open it straight from the index. Changes persist immediately.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(applicationContext)
        setContent {
            SoksakTheme {
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
internal fun SettingsScreen(repo: SettingsRepository, launch: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    // null is the index: a short grouped list. Every section lives on its own page, but all of
    // them stay in this one composable because snapshot() rebuilds Settings from its state.
    // Saveable so the language switch (which recreates the activity) comes back to its page.
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }

    // permissions / setup
    var bubbleOn by remember { mutableStateOf(BubbleService.isRunning || BubblePrefs.enabled(context)) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var showA11yConsent by remember { mutableStateOf(false) }
    var notifOn by remember { mutableStateOf(true) }
    var micGranted by remember { mutableStateOf(false) }

    // STT
    var sttModel by remember { mutableStateOf("") }
    var sttLanguage by remember { mutableStateOf(DictationLanguage.DEFAULT) }
    var vadAutoStop by remember { mutableStateOf(true) }
    var autoStopSeconds by remember { mutableStateOf(AUTO_STOP_CHOICES.first()) }
    var autoEnterSeconds by remember { mutableStateOf(0.0) }
    var headlessRecording by remember { mutableStateOf(true) }
    var promptExample by remember { mutableStateOf(DEFAULT_PROMPT_EXAMPLE) }
    var pipelinedWhisper by remember { mutableStateOf(true) }
    var quietMode by remember { mutableStateOf(false) }
    var quietModel by remember { mutableStateOf(WhisperModelManager.SMALL_ACFT_MODEL) }
    var npuDiagnostics by remember { mutableStateOf(false) }
    var uiLanguage by remember { mutableStateOf(I18n.mode) }
    var pcSend by remember { mutableStateOf(PcLink.OFF) }
    var pcHost by remember { mutableStateOf("") }
    var pcCode by remember { mutableStateOf("") }
    var pcType by remember { mutableStateOf(false) }
    var pcTestStatus by remember { mutableStateOf("") }
    var updateStatus by remember { mutableStateOf("") }
    val latestBuild = UpdateCheck.available // shared with Home's banner and dialog
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var reviewBeforeInsert by remember { mutableStateOf(false) }
    var bubbleOnlyOnFields by remember { mutableStateOf(true) }
    var keepHistory by remember { mutableStateOf(DictationHistory.keepHistory(context)) }
    var audioKeepDays by remember { mutableStateOf(PendingAudio.keepDays(context)) }

    // download tracking (a single in-flight download at a time)
    var dlId by remember { mutableStateOf<String?>(null) }
    var dlProgress by remember { mutableFloatStateOf(0f) }
    var modelsRev by remember { mutableStateOf(0) } // bump to recompute readiness after a download

    // Voice lock (내 목소리 학습 · 상시 화자 게이팅 · 수동 VoxLock 분리)
    var speakerGate by remember { mutableStateOf(true) }
    var voiceIsolation by remember { mutableStateOf(false) }
    var vlModelsReady by remember { mutableStateOf(false) }
    var vlProfileReady by remember { mutableStateOf(false) }
    var vlDownloading by remember { mutableStateOf(false) }
    var vlProgress by remember { mutableFloatStateOf(0f) }
    var vlEnrollRecording by remember { mutableStateOf(false) }
    var vlEnrolling by remember { mutableStateOf(false) }
    var vlEnrollSec by remember { mutableFloatStateOf(0f) }
    var vlEnrollLevel by remember { mutableFloatStateOf(0f) }
    // Enrollment takes one recording per VoiceEnrollment.PHRASES entry; a null slot is still to record.
    var vlEnrollActive by remember { mutableStateOf(false) }
    val vlClips = remember { mutableStateListOf<VoiceLockManager.EnrollClip?>(*arrayOfNulls(VoiceEnrollment.PHRASES.size)) }
    var vlErrorMsg by remember { mutableStateOf<String?>(null) }
    var pendingDeleteVl by remember { mutableStateOf(false) }
    val enrollRecorder = remember { AudioRecorder(context.applicationContext) }

    DisposableEffect(Unit) {
        onDispose { enrollRecorder.cancel() }
    }

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
        sttModel = s.sttModel
        // A blank id, or one a previous build offered (Parakeet, the Hinglish fine-tune), shows
        // here as the downloaded Whisper model that will actually run, so the list has an active
        // row. Only when that model is on disk: an "Active" row hides its own Get button.
        if (OnDeviceStt.resolveModel(sttModel) != sttModel) {
            val running = OnDeviceStt.resolveModel(context, sttModel)
            if (WhisperModelManager.isReady(context, running)) sttModel = running
        }
        sttLanguage = DictationLanguage.normalize(s.sttLanguage)
        vadAutoStop = s.vadAutoStop; autoStopSeconds = s.autoStopSeconds; autoEnterSeconds = s.autoEnterSeconds; headlessRecording = s.headlessRecording; promptExample = s.promptExample; pipelinedWhisper = s.pipelinedWhisper; quietMode = s.quietMode; quietModel = s.quietModel; npuDiagnostics = s.npuDiagnostics; bubbleOnlyOnFields = s.bubbleOnlyOnFields
        speakerGate = s.speakerGate; voiceIsolation = s.voiceIsolation
        pcSend = s.pcSend; pcHost = s.pcHost; pcCode = s.pcCode; pcType = s.pcType
        reviewBeforeInsert = s.reviewBeforeInsert
        uiLanguage = s.uiLanguage
        vlModelsReady = withContext(Dispatchers.IO) { VoiceLockManager.isModelsReady(context) }
        vlProfileReady = withContext(Dispatchers.IO) { VoiceLockManager.hasProfile(context) }
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

    // The bubble's menu writes these while this screen stays composed (it is a tab on Home, and
    // stays alive behind other apps). Read once, the rows went stale, and the next change made
    // here saved the whole stale snapshot over what the bubble had set. Only the fields the bubble
    // writes are followed, and only when the stored value moves: text being typed here is saved
    // on every keystroke, and re-applying an older save would eat characters.
    LaunchedEffect(Unit) {
        var last: Settings? = null
        repo.settings.collect { s ->
            val prev = last
            last = s
            if (prev == null) return@collect
            SettingsSync.changedByOthers(prev, s).let { c ->
                if (c.quietMode != null) quietMode = c.quietMode
                if (c.quietModel != null) quietModel = c.quietModel
                if (c.sttModel != null) sttModel = c.sttModel
                if (c.pcSend != null) pcSend = c.pcSend
                if (c.pcType != null) pcType = c.pcType
                if (c.voiceIsolation != null) voiceIsolation = c.voiceIsolation
            }
        }
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

    // Fill the screen while settings load: as a tab on Home this sits in an AnimatedContent, and a
    // zero-size first frame made it shrink the content area and spring back (seen on the phone).
    if (!loaded) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { HeroHeader() }
        return
    }

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
        sttModel = sttModel.trim(), sttLanguage = sttLanguage,
        vadAutoStop = vadAutoStop, autoStopSeconds = autoStopSeconds, autoEnterSeconds = autoEnterSeconds, headlessRecording = headlessRecording, promptExample = promptExample, pipelinedWhisper = pipelinedWhisper, quietMode = quietMode, quietModel = quietModel, npuDiagnostics = npuDiagnostics, uiLanguage = uiLanguage, bubbleOnlyOnFields = bubbleOnlyOnFields,
        pcSend = pcSend, pcHost = pcHost.trim(), pcCode = pcCode.trim(), pcType = pcType,
        reviewBeforeInsert = reviewBeforeInsert,
        hasCompletedOnboarding = true,
        speakerGate = speakerGate, voiceIsolation = voiceIsolation,
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

    // Backup of the dictionary + settings (no secrets), to move them to another install.
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        launch {
            val msg = try {
                val json = Backup.encode(snapshot(), VocabRepository(context).rawJson())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                }
                tr("Backup saved.")
            } catch (e: Exception) { e.message ?: tr("Backup failed.") }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                val restored = Backup.decode(text, repo.get())
                restored.vocabJson?.let { VocabRepository(context).replaceJson(it) }
                repo.save(restored.settings)
                I18n.mode = restored.settings.uiLanguage
                Toast.makeText(context, tr("Backup restored."), Toast.LENGTH_LONG).show()
                // Every field on this screen is state read once at start; reread them all.
                (context as? android.app.Activity)?.recreate()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: tr("Restore failed."), Toast.LENGTH_LONG).show()
            }
        }
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
        updateStatus = tr("Checking…")
        AppUpdater.lastResult.value = null // a stale "Installer opened" must not hide the answer
        launch {
            updateStatus = try {
                val r = AppUpdater.latest()
                val cur = AppUpdater.installedVersionCode(context)
                UpdateCheck.offer(r, cur, prompt = false)
                if (r.versionCode > cur) trf("Build %s is available.", r.run)
                else trf("Up to date (build %s).", r.run)
            } catch (e: Exception) { e.message ?: tr("Check failed.") }
        }
    }

    fun installUpdate(r: AppUpdater.Release) {
        if (!AppUpdater.canInstall(context)) {
            updateStatus = tr("Allow Soksak to install updates, then tap Update again.")
            context.startActivity(AppUpdater.installPermissionIntent(context))
            return
        }
        updateProgress = 0f
        launch {
            try {
                AppUpdater.downloadAndInstall(context, r) { p -> updateProgress = p }
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
        if (loaded) checkUpdate()
    }

    fun needsImport(id: String): Boolean = WhisperModelManager.model(id).importOnly

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

    // The index keeps its place while a page is open; each page starts at its top.
    val indexScroll = rememberScrollState()
    // Index and page slide past each other the way Home and Settings do. `shown` is the page
    // this pane draws: during the slide the outgoing and incoming panes are both composed.
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != null
            slideInHorizontally(tween(250)) { if (forward) it else -it } togetherWith
                slideOutHorizontally(tween(250)) { if (forward) -it else it }
        },
        label = "settings page",
    ) { shown ->
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(if (shown == null) indexScroll else rememberScrollState())) {
        HeroHeader(shown?.let { tr(it.title) }, onBack = if (shown != null) ({ page = null }) else null)

        Column(
            Modifier.fillMaxWidth().padding(16.dp), // the bottom bar keeps clear of the navigation bar
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // ---------------- SETUP STATUS ----------------
            if (shown == null) UpdateBanner { page = SettingsPage.UPDATES }
            val setupComplete = bubbleOn && a11yEnabled && micGranted
            if (shown == null) SetupStatusCard(setupComplete) {
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

            if (pendingDeleteVl) {
                DeleteModelDialog(
                    target = DeletableModel(
                        "voicelock",
                        tr("Voice lock models"),
                        if (NpuSupport.available) "~93MB" else "~105MB",
                    ),
                    onConfirm = {
                        pendingDeleteVl = false
                        launch {
                            val freed = VoiceLockManager.deleteModels(context)
                            vlModelsReady = false
                            voiceIsolation = false
                            persist()
                            val mb = freed / (1024 * 1024)
                            Toast.makeText(
                                context,
                                if (mb > 0) trf("Deleted %s — %sMB freed", tr("Voice lock models"), mb)
                                else trf("Deleted %s", tr("Voice lock models")),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onDismiss = { pendingDeleteVl = false },
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

            // ---------------- INDEX ----------------
            if (shown == null) {
                fun open(p: SettingsPage): () -> Unit = { page = p }
                fun start(c: Class<*>): () -> Unit = { context.startActivity(Intent(context, c)) }
                val modelLabel = WhisperModelManager.MODELS.firstOrNull { it.id == sttModel }?.label
                Section(tr("Dictation")) {
                    Card {
                        IndexRow(Icons.Outlined.GraphicEq, tr(SettingsPage.MODELS.title), modelLabel ?: tr("Model, dictation language, style example"), open(SettingsPage.MODELS))
                        Divider()
                        IndexRow(Icons.Outlined.Tune, tr(SettingsPage.DICTATION.title), tr("Auto-stop, Auto Enter, Quiet mode"), open(SettingsPage.DICTATION))
                        Divider()
                        IndexRow(Icons.Outlined.RecordVoiceOver, tr(SettingsPage.VOICE_LOCK.title), tr("Enroll your voice, speaker gate, isolation"), open(SettingsPage.VOICE_LOCK))
                        Divider()
                        IndexRow(Icons.Outlined.Science, tr("Compare models"), tr("Re-run a recent dictation on every model"), start(CompareActivity::class.java))
                    }
                }
                Section(tr("Bubble & PC")) {
                    Card {
                        IndexRow(Icons.Outlined.ChatBubbleOutline, tr(SettingsPage.BUBBLE.title), if (bubbleOn) tr("On") else tr("Off"), open(SettingsPage.BUBBLE))
                        Divider()
                        IndexRow(Icons.Outlined.Computer, tr(SettingsPage.PC.title), tr(PcLink.label(PcLink.mode(snapshot()))), open(SettingsPage.PC))
                    }
                }
                Section(tr("Personalization")) {
                    Card {
                        IndexRow(Icons.Outlined.Book, tr("Personal dictionary"), tr("Names & terms you taught it"), start(VocabActivity::class.java))
                        Divider()
                        IndexRow(Icons.Outlined.AutoFixHigh, tr("Learned from edits"), tr("Corrections it remembered"), start(LearnedVocabActivity::class.java))
                        Divider()
                        IndexRow(Icons.Outlined.Contacts, tr("Import from contacts"), tr("Matched on-device · never uploaded"), start(ContactsImportActivity::class.java))
                    }
                }
                Section(tr("App")) {
                    Card {
                        IndexRow(Icons.Outlined.Lock, tr(SettingsPage.PRIVACY.title), tr("History, kept audio, clear all data"), open(SettingsPage.PRIVACY))
                        Divider()
                        IndexRow(
                            Icons.Outlined.SystemUpdate, tr(SettingsPage.UPDATES.title),
                            latestBuild?.let { trf("Build %s is available.", it.run) } ?: trf("Installed: %s", prettyVersion(appVersion(context))),
                            open(SettingsPage.UPDATES), highlight = latestBuild != null,
                        )
                        Divider()
                        IndexRow(Icons.Outlined.BugReport, tr(SettingsPage.TROUBLE.title), tr("Debug log, report a problem"), open(SettingsPage.TROUBLE))
                        Divider()
                        IndexRow(Icons.Outlined.SettingsIcon, tr(SettingsPage.GENERAL.title), tr("Language, backup, licenses"), open(SettingsPage.GENERAL))
                    }
                }
            }

            // ---------------- MODELS & LANGUAGE ----------------
            if (shown == SettingsPage.MODELS) Section {
                Card {
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
                }
            }

            // ---------------- RECORDING & INSERTING ----------------
            if (shown == SettingsPage.DICTATION) Section {
                Card {
                    ToggleRow(
                        tr("Transcribe while you talk"),
                        tr("Whisper models start on what you've said at each pause, so only the last few seconds are left when you stop. Also keeps long dictations from losing sentences."),
                        pipelinedWhisper,
                    ) { pipelinedWhisper = it; persist() }
                    Divider()
                    ToggleRow(tr("Auto-stop on pause"), tr("End recording when you stop talking"), vadAutoStop) { vadAutoStop = it; persist() }
                    if (vadAutoStop) {
                        Divider()
                        // Tap cycles 1.6s → 2.2s → 3s → 5s.
                        NavRow(tr("Auto-stop wait"), trf("Stops after %ss of silence · tap to change", "%.1f".format(autoStopSeconds).removeSuffix(".0"))) {
                            val i = AUTO_STOP_CHOICES.indexOf(autoStopSeconds)
                            autoStopSeconds = AUTO_STOP_CHOICES[(i + 1).mod(AUTO_STOP_CHOICES.size)]
                            persist()
                        }
                    }
                    Divider()
                    // Tap cycles off → 1s → 2s → 3s → 5s.
                    NavRow(
                        tr("Auto Enter"),
                        if (autoEnterSeconds <= 0.0) tr("Off · tap to change")
                        else trf("Presses Enter %ss after inserting; tap the bubble to stop it · tap to change", "%.1f".format(autoEnterSeconds).removeSuffix(".0")),
                    ) {
                        autoEnterSeconds = nextAutoEnter(autoEnterSeconds)
                        persist()
                    }
                    Divider()
                    ToggleRow(
                        tr("Quiet mode"),
                        tr("For whispering in a quiet office: keeps the whole recording, stops on your own voice level instead of the speech detector, and uses its own model. Also in the bubble's menu (tap the model tab under the bubble)."),
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
                        tr("Keep the app you're typing in focused. On-device only; open Soksak once after a reboot"),
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

            // ---------------- VOICE LOCK ----------------
            // Enrollment is one recording per phrase (short, medium, long). The existing profile stays
            // in use until all of them are in and saved.
            fun clearVoiceClips() {
                for (i in vlClips.indices) vlClips[i] = null
            }

            fun saveVoiceProfile() {
                vlEnrolling = true
                launch {
                    try {
                        VoiceLockManager.enrollFromClips(context, vlClips.filterNotNull())
                        vlProfileReady = true
                        vlEnrollActive = false
                        clearVoiceClips()
                        Toast.makeText(context, tr("Your voice profile is ready."), Toast.LENGTH_SHORT).show()
                    } catch (e: VoiceLockManager.InconsistentClipException) {
                        vlClips[e.index] = null
                        vlErrorMsg = trf("Phrase %s didn't sound like the others. Please read it again.", e.index + 1)
                    } catch (e: Exception) {
                        vlErrorMsg = tr(e.message ?: "Failed to save voice profile.")
                    } finally {
                        vlEnrolling = false
                    }
                }
            }

            fun finishVoiceEnroll() {
                if (!vlEnrollRecording) return
                vlEnrollRecording = false
                vlEnrolling = true
                vlErrorMsg = null
                launch {
                    val samples = withContext(Dispatchers.IO) { enrollRecorder.stop() }
                    val step = vlClips.indexOfFirst { it == null }
                    val clip = if (samples == null || step < 0) {
                        vlErrorMsg = tr("Didn't catch enough of your voice. Please read the phrase again.")
                        null
                    } else {
                        try {
                            VoiceLockManager.prepareClip(context, samples)
                        } catch (e: Exception) {
                            vlErrorMsg = tr(e.message ?: "Failed to save voice profile.")
                            null
                        }
                    }
                    vlEnrolling = false
                    if (clip != null) {
                        vlClips[step] = clip
                        if (vlClips.none { it == null }) saveVoiceProfile()
                    }
                }
            }

            fun recordVoiceClip() {
                if (!micGranted) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return
                }
                if (vlEnrollRecording || vlEnrolling) return
                vlErrorMsg = null
                vlEnrollSec = 0f
                vlEnrollLevel = 0f
                try {
                    enrollRecorder.start(
                        vadAutoStop = true,
                        hangoverSeconds = VoiceEnrollment.CLIP_AUTO_STOP_S,
                        rawCaptureOnly = true,
                        onAutoStop = { finishVoiceEnroll() },
                    )
                    vlEnrollRecording = true
                    launch {
                        val startedAt = System.currentTimeMillis()
                        while (vlEnrollRecording && enrollRecorder.isRecording) {
                            val sec = (System.currentTimeMillis() - startedAt) / 1000f
                            vlEnrollSec = sec
                            vlEnrollLevel = AudioRecorder.level(enrollRecorder.amplitude())
                            if (sec >= VoiceEnrollment.CLIP_MAX_S) {
                                finishVoiceEnroll()
                                break
                            }
                            kotlinx.coroutines.delay(55)
                        }
                    }
                } catch (e: Exception) {
                    vlEnrollRecording = false
                    vlErrorMsg = e.message ?: tr("Couldn't start the mic.")
                }
            }

            fun startVoiceEnroll() {
                if (vlEnrollRecording || vlEnrolling) return
                clearVoiceClips()
                vlEnrollActive = true
                recordVoiceClip()
            }

            fun cancelVoiceEnroll() {
                if (vlEnrollRecording) {
                    vlEnrollRecording = false
                    enrollRecorder.cancel()
                }
                vlEnrollActive = false
                clearVoiceClips()
                vlErrorMsg = null
            }

            if (shown == SettingsPage.VOICE_LOCK) Section {
                Card {
                    Padded {
                        Text(
                            tr("Learn your voice once on this device to keep TV or nearby voices out and unlock voice isolation for noisy cafes."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        val vlState = when {
                            vlDownloading -> "downloading"
                            vlModelsReady && vlProfileReady -> "active"
                            vlModelsReady -> "downloaded"
                            else -> "idle"
                        }
                        ModelRow(
                            name = tr("Voice lock models"),
                            meta = trf("%s · speaker gate + voice isolation", if (NpuSupport.available) "~93MB" else "~105MB"),
                            recommended = false,
                            state = vlState,
                            progress = vlProgress,
                            onGet = {
                                if (vlDownloading) return@ModelRow
                                vlDownloading = true
                                vlProgress = 0f
                                vlErrorMsg = null
                                launch {
                                    try {
                                        VoiceLockManager.downloadModels(context) { p -> vlProgress = p }
                                        vlModelsReady = true
                                    } catch (e: Exception) {
                                        Toast.makeText(context, e.message ?: tr("Download failed"), Toast.LENGTH_LONG).show()
                                    } finally {
                                        vlDownloading = false
                                    }
                                }
                            },
                            onUse = { startVoiceEnroll() },
                            onDelete = if (vlModelsReady && !vlDownloading && !voiceIsolation) {
                                { pendingDeleteVl = true }
                            } else null,
                        )

                        if (vlModelsReady) {
                            Spacer(Modifier.height(12.dp))
                            Column(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(13.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val vlStep = vlClips.indexOfFirst { it == null }
                                val phraseCount = VoiceEnrollment.PHRASES.size
                                Text(
                                    when {
                                        vlEnrollActive && vlStep >= 0 -> trf("Phrase %s of %s — read it at your normal pace:", vlStep + 1, phraseCount)
                                        vlEnrollActive && vlEnrolling -> tr("Saving voice signature…")
                                        vlEnrollActive -> tr("Failed to save voice profile.")
                                        vlProfileReady -> tr("Your voice is registered.")
                                        else -> trf("In a quiet spot, read %s short phrases one at a time. Each recording stops by itself when you pause.", phraseCount)
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (vlEnrollActive && vlStep >= 0) {
                                    Text(
                                        "\"${VoiceEnrollment.PHRASES[vlStep]}\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                if (vlEnrollRecording) {
                                    LinearProgressIndicator(
                                        progress = { vlEnrollLevel.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)),
                                    )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            trf("Listening… %ss", "%.1f".format(vlEnrollSec)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        PillButton(tr("Done")) { finishVoiceEnroll() }
                                    }
                                } else if (vlEnrolling) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Text(
                                            if (vlStep >= 0) tr("Checking…") else tr("Saving voice signature…"),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                } else if (vlEnrollActive) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (vlStep >= 0) {
                                            PillButton(trf("Record phrase %s", vlStep + 1)) { recordVoiceClip() }
                                        } else {
                                            PillButton(tr("Start over")) { startVoiceEnroll() }
                                        }
                                        PillOutline(tr("Cancel")) { cancelVoiceEnroll() }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PillButton(if (vlProfileReady) tr("Re-record") else tr("Record my voice")) {
                                            startVoiceEnroll()
                                        }
                                        if (vlProfileReady) {
                                            PillOutline(tr("Clear voice")) {
                                                VoiceLockManager.clearProfile(context)
                                                vlProfileReady = false
                                                if (voiceIsolation) {
                                                    voiceIsolation = false
                                                    persist()
                                                }
                                            }
                                        }
                                    }
                                }
                                vlErrorMsg?.let { msg ->
                                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    if (vlModelsReady && vlProfileReady) {
                        Divider()
                        ToggleRow(
                            tr("Speaker gate"),
                            tr("Always-on lightweight guard (~7% 1 core): ignores TV or other people between sentences and keeps auto-stop working"),
                            speakerGate,
                        ) { speakerGate = it; persist() }
                        Divider()
                        ToggleRow(
                            tr("Isolate my voice"),
                            if (NpuSupport.available) {
                                tr("Deep neural extraction on NPU when others talk over you at the same time (recommended only in noisy places). You can also toggle it from the bubble menu.")
                            } else {
                                tr("Deep neural extraction when others talk over you at the same time (uses more CPU). You can also toggle it from the bubble menu.")
                            },
                            voiceIsolation,
                        ) { on ->
                            voiceIsolation = on
                            if (on) {
                                if (quietMode) quietMode = false
                                VoiceLockManager.resetNpuGuard(context)
                            }
                            persist()
                        }
                    }
                }
            }

            // ---------------- BUBBLE ----------------
            if (shown == SettingsPage.BUBBLE) Section {
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
                        else if (bubbleOnlyOnFields) tr("Appear only when you can type")
                        else tr("Always shown. With the keyboard down, the text goes into the field on screen."),
                        bubbleOnlyOnFields,
                    ) { bubbleOnlyOnFields = it; persist() }
                }
            }

            // ---------------- SEND TO PC ----------------
            if (shown == SettingsPage.PC) Section {
                Card {
                    Padded {
                        Text(tr("Copies each dictation to the clipboard of your computer, directly over Tailscale. The computer runs soksak-daemon; `soksak-daemon pair` there shows the address and code. Nothing is sent while this is set to this phone only."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Label(tr("PC address"))
                        Spacer(Modifier.height(6.dp))
                        KeyField(pcHost, "100.x.y.z:${PcLink.DEFAULT_PORT}") { pcHost = it; pcTestStatus = ""; persist() }
                        Spacer(Modifier.height(12.dp))
                        Label(tr("Pairing code"))
                        Spacer(Modifier.height(6.dp))
                        KeyField(pcCode, "ABCDE-FGHJK", secret = true) { pcCode = it; pcTestStatus = ""; persist() }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(pcTestStatus.ifBlank { tr("Check that the computer answers.") },
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f))
                            PillOutline(tr("Test")) {
                                if (pcHost.isBlank() || PcLink.normalizeCode(pcCode).isEmpty()) {
                                    pcTestStatus = tr("Enter the PC address and pairing code first.")
                                } else {
                                    pcTestStatus = tr("Connecting…")
                                    launch {
                                        val sent = PcLink.send(pcHost, pcCode, "", pcType)
                                        pcTestStatus = tr(sent.error
                                            ?: if (sent.clipboardOnly) "Connected. This computer only allows its clipboard; typing needs allow_type = true in its soksak-daemon config."
                                            else "Connected.")
                                    }
                                }
                            }
                        }
                    }
                    Divider()
                    // Tap cycles phone only → phone and PC → PC only. Also on the bubble's menu.
                    val pcMode = PcLink.mode(snapshot())
                    NavRow(
                        tr("Send dictations to"),
                        trf("%s · tap to change", tr(PcLink.label(pcMode))) +
                            if (pcMode == PcLink.PC_ONLY) "\n" + tr("The bubble stays visible everywhere, and the text is typed on this phone only if the computer can't be reached.") else "",
                    ) {
                        if (pcHost.isBlank() || PcLink.normalizeCode(pcCode).isEmpty()) {
                            pcTestStatus = tr("Enter the PC address and pairing code first.")
                        } else {
                            pcSend = PcLink.next(pcMode); persist()
                        }
                    }
                    Divider()
                    // Also on the bubble's menu. The PC has the last word: see PcLink.
                    NavRow(
                        tr("On the PC"),
                        trf("%s · tap to change", tr(PcLink.actionLabel(pcType))) + "\n" +
                            tr(if (pcType) "Typed into the window that has focus there, and copied too. The computer must allow it: allow_type = true in its soksak-daemon config."
                               else "Copied to its clipboard; paste it where you want it."),
                    ) { pcType = !pcType; pcTestStatus = ""; persist() }
                }
            }

            // ---------------- PRIVACY ----------------
            if (shown == SettingsPage.PRIVACY) Section {
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
                            VoiceLockManager.clearProfile(context)
                            vlProfileReady = false
                            if (voiceIsolation) {
                                voiceIsolation = false
                                persist()
                            }
                        }
                        Toast.makeText(context, tr("On-device history cleared"), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // ---------------- RELIABILITY ----------------
            if (shown == SettingsPage.TROUBLE) Section(tr("Reliability")) {
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
                                .setPrimaryClip(android.content.ClipData.newPlainText("soksak log", log))
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
            // Prefills version/device details and hands off to the browser; nothing is sent by us.
            if (shown == SettingsPage.TROUBLE) Section(tr("Feedback")) {
                Card {
                    NavRow(tr("Report a problem"), tr("Open an issue on GitHub")) {
                        launchOrNotify(context, Feedback.issueIntent(context), tr("Couldn't open a browser."))
                    }
                }
            }

            if (shown == SettingsPage.UPDATES) Section {
                Card {
                    Padded {
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
                        ShizukuInstaller.State.NEEDS_PERMISSION -> tr("Silent updates: allow Soksak") to
                            tr("Shizuku is running. Tap to let Soksak install updates through it.")
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

            if (shown == SettingsPage.GENERAL) Section {
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
                    NavRow(tr("Back up dictionary & settings"), tr("Save a file to move them to another install (no tokens or keys)")) {
                        backupLauncher.launch("soksak-backup-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".json")
                    }
                    Divider()
                    NavRow(tr("Restore from backup"), tr("Replaces the dictionary and settings with the file's")) {
                        restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }
                    Divider()
                    NavRow(tr("Open source licenses"), tr("Libraries, fonts and models this app uses")) {
                        context.startActivity(Intent(context, LicensesActivity::class.java))
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
    } // AnimatedContent
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

/* ------------------------ reusable pieces ------------------------ */

/** The pages behind the Settings index. [title] is the English key for [tr]. */
internal enum class SettingsPage(val title: String) {
    MODELS("Models & language"),
    DICTATION("Recording & inserting"),
    VOICE_LOCK("Voice lock"),
    BUBBLE("Bubble"),
    PC("Send to PC"),
    PRIVACY("Privacy"),
    UPDATES("Updates"),
    TROUBLE("Troubleshooting"),
    GENERAL("General"),
}

/** The index shows the mark and "Settings"; a page shows a back arrow and its own name. */
@Composable
private fun HeroHeader(pageTitle: String? = null, onBack: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().background(DuskBrush).statusBarsPadding().padding(24.dp, 22.dp, 24.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            // The whole title is the back target: the arrow alone is a small thing to hit.
            if (onBack != null) Modifier.fillMaxWidth().clickable { onBack() } else Modifier,
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onBack != null) Icon(Icons.Filled.ChevronLeft, tr("Back"), tint = MarkLight, modifier = Modifier.size(40.dp))
            else Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_mark), null, tint = MarkLight, modifier = Modifier.size(40.dp))
            // Page names are longer than "Settings": at the wordmark's 26sp "Recording & inserting"
            // does not fit a 360dp screen.
            Text(pageTitle ?: tr("Settings"), color = MarkLight, style = if (pageTitle != null) Wordmark.copy(fontSize = 21.sp) else Wordmark, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** [title] is null on a page that holds one section: the header already names it. */
@Composable
private fun Section(title: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (title != null) Text(title.uppercase(), style = MonoEyebrow.trackedFor(title), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        content()
    }
}

/** One line of the index: icon, name, and what is set there now. */
@Composable
private fun IndexRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
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
                Text(meta, style = MonoEyebrow.trackedFor(meta), fontSize = 11.sp, color = cs.onSurfaceVariant)
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
        Text(text.uppercase(), style = MonoEyebrow.trackedFor(text), fontSize = 10.sp, color = fg, maxLines = 1, softWrap = false)
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
