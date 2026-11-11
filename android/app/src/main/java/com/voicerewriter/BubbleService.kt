package com.voicerewriter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Foreground service that paints a draggable bubble. Gestures:
 *  - single tap  → start a hands-free dictation (tap again to stop). Long-form: your hands are
 *                  free, and auto-stop ends it if enabled.
 *  - hold        → talk while held, release to send. Short-form, and no VAD has to guess where
 *                  you finished. This is the split Wispr Flow and the desktop tools converged
 *                  on; see research/07-recommendations.md.
 *  - drag        → reposition (disabled once a hold-to-talk take is live)
 *
 * Recording itself runs in RewriteActivity (a visible activity legitimately holds
 * the mic on Android 14; a background overlay cannot start a mic service). While
 * recording, the activity calls back here to swap the bubble to a live waveform.
 *
 * ## Shape and position
 *
 * [BubbleView] draws every state; this class owns the window it lives in. Two consequences of
 * the redesign land here rather than in the view:
 *
 *  - **The bubble docks to a screen edge.** The user drags it anywhere, and on release it snaps
 *    to whichever side is nearer, keeping the height they chose. Free x/y positioning was the
 *    old behaviour; an edge is now load-bearing, because the pill states grow *inward* from the
 *    docked edge and would otherwise have no fixed side to grow from.
 *  - **The window resizes with the state.** Listening and Copied are 112dp pills, the rest are
 *    48dp circles. [animateGeometry] moves width and x together so the docked edge stays pinned
 *    while the other edge travels — which is what makes the pill look like it is growing out of
 *    the side of the screen rather than jumping.
 *
 * The window is deliberately larger than the bubble by [BubbleView.PAD_DP] on every side; see
 * [BubbleView] for why.
 */
class BubbleService : Service() {

    companion object {
        /** How long the clipboard mark holds before the bubble goes back to idle. */
        private const val FALLBACK_HOLD_MS = 2600L

        /** How long the green check holds. The design's figure. */
        private const val INSERTED_HOLD_MS = 1600L

        /**
         * Idle this long with no touch and the bubble tucks itself away: smaller, toward its
         * docked edge, and translucent (as Typeless does), so it covers less of the app beneath.
         * Any touch or any non-idle state brings it straight back.
         */
        private const val IDLE_SHRINK_MS = 3500L
        private const val SHRUNK_SCALE = 0.7f
        private const val SHRUNK_ALPHA = 0.5f

        /** Gap between the screen edge and the docked bubble. */
        private const val EDGE_INSET_DP = 8f

        /** Width/position transition. The design's `width .38s cubic-bezier(.3,.7,.2,1)`. */
        private const val MORPH_MS = 380L

        @Volatile
        var isRunning = false

        /** The running bubble, so RewriteActivity can drive its visual state. */
        @Volatile
        var instance: BubbleService? = null

        /** Start action sent by [DictateWidget]. */
        const val ACTION_DICTATE = "com.voicerewriter.DICTATE"

        /** Silence that ends a widget take; see [widgetTake]. One of [AUTO_STOP_CHOICES]. */
        const val WIDGET_AUTO_STOP_S = 2.2

        /**
         * Set by the active recording activity to its stop() handler; null when not
         * recording. The bubble invokes it (on the main thread) to stop & process.
         */
        @Volatile
        var recordingStopper: (() -> Unit)? = null

        /**
         * True while a finger is held on the bubble for hold-to-talk. Read by RewriteActivity
         * the moment its recorder is up, to catch the release that arrived while the activity
         * was still starting.
         */
        @Volatile
        var holdingToTalk = false

        /**
         * True when this service is in the foreground with the microphone type, which it can
         * only become while our UI is visible (Android 14+ while-in-use rule). It then keeps
         * the microphone after the user leaves, which is what lets [HeadlessDictation] record
         * without opening a sheet. False after a background (re)start — boot, or the system
         * restarting a killed service — until the user opens Soksak again.
         */
        @Volatile
        var micReady = false

        private const val CHANNEL_ID = "bubble"
        private const val NOTIF_ID = 1
    }

    private lateinit var wm: WindowManager
    private var bubble: BubbleView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())

    private var dismissView: View? = null
    private var railLeft: View? = null
    private var railRight: View? = null
    private var overlayType = 0
    private var dismissSize = 0
    private var overDismiss = false
    private var morph: ValueAnimator? = null

    /** Lives as long as the service; cancelled in [onDestroy]. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Speech-model menu, opened by flicking the bubble up. */
    private var picker: ModelPicker? = null

    /** Which edge the bubble is docked to. Persisted indirectly, via the saved x. */
    private var dockedLeft = false

    /**
     * While a confirmation state (Inserted, Copied) is holding, [showIdle] is a no-op.
     *
     * Needed because the two are driven from different places with no ordering between them:
     * the accessibility service reports a successful insert at the moment it writes the text,
     * while RewriteActivity's `onDispose` resets the bubble as it finishes — which is the same
     * instant. Without this the green check would be wiped before a frame of it ever drew.
     */
    private var holdUntil = 0L

    // Field-gated visibility: when on (and accessibility is available), the bubble
    // only appears while a text field is focused — like a contextual keyboard key.
    @Volatile private var gateSetting = true
    private var fieldFocused = false
    private var recording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        enterForeground()
        DictateWidget.refreshAll(this)
        gateSetting = try {
            runBlocking { gateFor(SettingsRepository(applicationContext).get()) }
        } catch (_: Exception) { true }
        addBubble()
        watchModelBadge()
        isRunning = true
        instance = this
        // If gated, start hidden until the accessibility service reports a focused field.
        applyGating(animate = false)
        // In case a field is already focused when the bubble starts, ask for a check.
        OpenWisprAccessibilityService.reevaluate()
        warmModels()
    }

    /**
     * Best-effort cold-start warmup so the first dictation doesn't stall on model loads.
     * Never crashes the service: preloads the cached Whisper context, which is reused across dictations.
     */
    private fun warmModels() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = SettingsRepository(applicationContext).get()
                OnDeviceStt.warm(applicationContext, s.sttModel)
            } catch (e: Exception) {
                android.util.Log.w("BubbleService", "warmup skipped", e)
            }
        }
    }

    /** Keep the bubble's model tab in step with the setting, wherever it gets changed. */
    private fun watchModelBadge() {
        serviceScope.launch {
            SettingsRepository(applicationContext).settings
                .map { s -> s.forDictation().sttModel.ifBlank { WhisperModelManager.DEFAULT_MODEL } to s.quietMode }
                .distinctUntilChanged()
                .collect { (id, quiet) ->
                    bubble?.badge = WhisperModelManager.MODELS.firstOrNull { it.id == id }?.badge
                    bubble?.badgeQuiet = quiet
                }
        }
    }

    /**
     * The model menu, beside the bubble. Lists downloaded models only; picking one saves it as
     * the speech model and loads it in the background so the next dictation doesn't wait.
     */
    private fun showModelPicker() {
        val v = bubble ?: return
        serviceScope.launch {
            val settings = SettingsRepository(applicationContext).get()
            val items = withContext(Dispatchers.IO) {
                WhisperModelManager.MODELS
                    .filter { WhisperModelManager.isReady(applicationContext, it.id) }
                    .map { ModelPicker.Item(it.id, it.label, it.badge) }
            }
            val p = picker ?: ModelPicker(this@BubbleService, wm, overlayType).also { picker = it }
            val cw = params.width - 2 * pad()
            val left = params.x + pad()
            p.show(
                items = items,
                currentId = settings.forDictation().sttModel.ifBlank { WhisperModelManager.DEFAULT_MODEL },
                dark = v.dark,
                anchorLeft = left - dp(4f),
                anchorRight = left + cw + dp(4f),
                anchorCy = contentCenter().second.toInt(),
                dockedLeft = dockedLeft,
                screenW = screenW(),
                screenH = screenH(),
                onPick = { id -> pickModel(id) },
                onMore = {
                    startActivity(Intent(this@BubbleService, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                quiet = settings.quietMode,
                onQuiet = { on -> setQuietMode(on) },
                pcSend = if (PcLink.configured(settings)) PcLink.mode(settings) else null,
                onPcSend = { cyclePcSend() },
                pcType = settings.pcType,
                onPcType = { togglePcType() },
            )
        }
    }

    /** Quiet mode on/off from the bubble menu; the menu reopens to show the new state. */
    private fun setQuietMode(on: Boolean) {
        hapticStart()
        serviceScope.launch {
            val repo = SettingsRepository(applicationContext)
            val s = repo.get().copy(quietMode = on)
            repo.save(s)
            withContext(Dispatchers.IO) { runCatching { OnDeviceStt.warm(applicationContext, s.forDictation().sttModel) } }
        }
        mainHandler.postDelayed({ showModelPicker() }, 120)
    }

    /** In Quiet mode the menu picks Quiet mode's model; otherwise the normal one. */
    private fun pickModel(id: String) {
        hapticStart()
        bubble?.badge = WhisperModelManager.MODELS.firstOrNull { it.id == id }?.badge
        serviceScope.launch {
            val repo = SettingsRepository(applicationContext)
            val cur = repo.get()
            NpuLoadGuard.reset(applicationContext, id) // picking it again is the way to retry
            repo.save(if (cur.quietMode) cur.copy(quietModel = id) else cur.copy(sttModel = id))
            withContext(Dispatchers.IO) {
                runCatching { OnDeviceStt.warm(applicationContext, id) }
                    .onFailure { android.util.Log.w("BubbleService", "warm $id failed", it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A start from our own visible UI is the moment the microphone type can be (re)acquired;
        // see [micReady]. Harmless when it already holds it.
        if (!micReady) enterForeground()
        // The dictation widget: like tapping the bubble (start, or stop the running take), but
        // with the widget's own settings (widgetTake) and, since it is used on the lock screen
        // where the bubble says nothing, a full-screen view of the take.
        if (intent?.action == ACTION_DICTATE) mainHandler.post {
            val starting = recordingStopper == null && !HeadlessDictation.active
            // The last take's result may still be up; the screen reads IDLE as "not started yet".
            if (starting) DictationStatus.phase.value = DictationStatus.Phase.IDLE
            onTap(fromWidget = true)
            if (starting) {
                startActivity(Intent(this, LockDictationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return START_STICKY
    }

    /**
     * Go foreground, with the microphone type when the OS allows it right now. Asking for it
     * from the background throws on Android 14+, so the attempt is made and the plain
     * special-use type is the fallback, never the other way round.
     */
    private fun enterForeground() {
        val notification = buildNotification()
        val hasMic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        try {
            when {
                Build.VERSION.SDK_INT >= 34 -> {
                    val special = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    val mic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    micReady = hasMic && runCatching { startForeground(NOTIF_ID, notification, special or mic) }
                        .onFailure { android.util.Log.i("BubbleService", "microphone FGS type refused: ${it.message}") }
                        .isSuccess
                    if (!micReady) startForeground(NOTIF_ID, notification, special)
                }
                Build.VERSION.SDK_INT >= 30 -> {
                    val mic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    micReady = hasMic && runCatching { startForeground(NOTIF_ID, notification, mic) }.isSuccess
                    if (!micReady) startForeground(NOTIF_ID, notification)
                }
                else -> {
                    // No while-in-use restriction for services before Android 11.
                    startForeground(NOTIF_ID, notification)
                    micReady = hasMic
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BubbleService", "startForeground failed", e)
        }
        android.util.Log.i("BubbleService", "foreground, micReady=$micReady")
    }

    /**
     * Start a dictation: headless when possible ([HeadlessDictation]), otherwise the sheet.
     * Settings are read off the main thread; the gesture's haptic has already fired.
     */
    private fun beginDictation(pushToTalk: Boolean, fromWidget: Boolean = false) {
        CoroutineScope(Dispatchers.Main).launch {
            val s = runCatching {
                SettingsRepository(applicationContext).get().let { if (fromWidget) widgetTake(it) else it }.forDictation()
            }.getOrNull()
            val headless = s != null && HeadlessDictation.canRun(applicationContext, s) &&
                HeadlessDictation.start(applicationContext, s, pushToTalk) {
                    // Silenced microphone: say nothing, just let the sheet take this take.
                    launchRewrite(autoRecord = true, pushToTalk = pushToTalk && holdingToTalk)
                }
            // A widget take that could not get the microphone is retried by its screen once
            // that is visible (widgetTakeFromScreen); the sheet cannot show over the lock screen.
            if (!headless && !(fromWidget && !micReady)) launchRewrite(autoRecord = true, pushToTalk = pushToTalk)
        }
    }

    /**
     * From [LockDictationActivity] once it is on screen: a visible activity is what lets the
     * service take the microphone type, so a widget take that could not start gets one more go.
     */
    fun widgetTakeFromScreen() {
        // With the microphone already held, the take either started or failed for another reason.
        if (micReady || recordingStopper != null || HeadlessDictation.active) return
        enterForeground()
        if (micReady) beginDictation(pushToTalk = false, fromWidget = true)
    }

    /**
     * The bubble floats over other apps, so it can't read the host's theme and has no way to
     * sample the pixels behind it. The system dark-mode setting is the best proxy available for
     * "the screen under me is dark", and it is right most of the time because most apps follow
     * it. Re-read on a configuration change so the bubble flips with the rest of the system.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.dark = isNightMode()
        // A rotation or a fold changes the screen width, so the docked edge moved.
        redock(animate = false)
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Floating bubble", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_mark)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Tap to dictate · hold to talk, release to send")
            .setOngoing(true)
            .build()
    }

    // ---- geometry ----

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).roundToInt()

    private fun screenW(): Int = resources.displayMetrics.widthPixels
    private fun screenH(): Int = resources.displayMetrics.heightPixels

    /** Content width for a state, in px (the drawn bubble, not the window). */
    private fun contentW(state: BubbleView.State): Int = dp(BubbleView.widthDpFor(state))
    private fun contentH(): Int = dp(BubbleView.HEIGHT_DP)
    private fun pad(): Int = dp(BubbleView.PAD_DP)

    private fun windowW(state: BubbleView.State): Int = contentW(state) + 2 * pad()
    private fun windowH(): Int = contentH() + 2 * pad()

    /** Window x that puts the content [EDGE_INSET_DP] from the given edge. */
    private fun dockedX(left: Boolean, cw: Int): Int =
        if (left) dp(EDGE_INSET_DP) - pad() else screenW() - dp(EDGE_INSET_DP) - cw - pad()

    /** Screen-space centre of the drawn bubble (not the padded window). */
    private fun contentCenter(): Pair<Float, Float> {
        val cw = params.width - 2 * pad()
        return (params.x + pad() + cw / 2f) to (params.y + pad() + contentH() / 2f)
    }

    private fun clampY(y: Int): Int =
        y.coerceIn(-pad(), (screenH() - windowH() + pad()).coerceAtLeast(-pad()))

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view = BubbleView(this).apply { dark = isNightMode() }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        overlayType = type

        params = WindowManager.LayoutParams(
            windowW(BubbleView.State.IDLE),
            windowH(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Restore where the user last dropped it. Clamped, because the saved point may be off
            // the current screen: a different orientation, a foldable's inner display, or a
            // smaller window than the one it was saved on would otherwise strand the bubble
            // outside the viewport with no way to drag it back.
            val (px, py) = BubblePrefs.resolvePosition(
                savedX = BubblePrefs.x(this@BubbleService),
                savedY = BubblePrefs.y(this@BubbleService),
                defaultX = dockedX(left = false, cw = contentW(BubbleView.State.IDLE)),
                defaultY = dp(200f),
                screenW = screenW(),
                screenH = screenH(),
                size = windowW(BubbleView.State.IDLE),
            )
            // Only the height is restored verbatim. The side is re-derived and re-snapped,
            // because a point saved before the bubble docked — or on a wider screen — can sit
            // anywhere, and the pill states need an edge to grow inward from.
            dockedLeft = (px + windowW(BubbleView.State.IDLE) / 2) < screenW() / 2
            x = dockedX(dockedLeft, contentW(BubbleView.State.IDLE))
            y = py
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val touchSlop = 12 * density

        // Hold-to-talk. Deliberately the *short* gesture: your finger is pinned for the whole
        // recording, so it can't run long, and releasing is an unambiguous end - no VAD has to
        // guess. Tap is the long-form one. Wispr Flow, Handy, Amical, FreeFlow and Muesli all
        // landed on this same split (see research/07-recommendations.md).
        //
        // Note this is why dragging starts on movement rather than after a 300ms hold, which is
        // what the design's "press & hold to pick up" frame describes: the hold is already
        // taken, and taking it twice would mean every hold-to-talk take began by lifting the
        // bubble out of its dock. The *look* of that frame — the lift and the coral outline —
        // is what [BubbleView.State.LIFTED] shows, just triggered by the drag instead.
        val longPress = Runnable {
            if (!moved && recordingStopper == null) {
                longFired = true
                holdingToTalk = true
                hapticHeld() // a heavier beat than a tap: you are holding, and release will send
                beginDictation(pushToTalk = true)
            }
        }

        /**
         * Short, fast and mostly vertical upward travel. A deliberate drag to reposition is
         * slower than this, so moving the bubble up still works when done at a normal pace.
         */
        fun isFlickUp(e: MotionEvent): Boolean {
            val dx = e.rawX - downRawX
            val dy = e.rawY - downRawY
            return e.eventTime - e.downTime <= FLICK_MAX_MS &&
                dy <= -dp(FLICK_MIN_DP) && abs(dy) >= 1.5f * abs(dx)
        }

        /**
         * End of a hold-to-talk. The recording lives in RewriteActivity, which is still
         * launching when a quick release lands, so [recordingStopper] may not exist yet.
         * Clearing [holdingToTalk] covers that: the activity checks it once the recorder is
         * up and stops itself immediately if the finger is already gone.
         */
        fun endHold() {
            holdingToTalk = false
            recordingStopper?.let { mainHandler.post(it) }
        }

        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    picker?.dismiss() // touching the bubble again closes the menu
                    wake(animate = false) // full size under the finger before anything else
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longFired = false
                    mainHandler.postDelayed(longPress, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Once hold-to-talk is live the bubble is a mic button, not a draggable
                    // thing. Letting it drag here would both move it mid-sentence and risk
                    // dropping it on the dismiss target while recording.
                    if (longFired) return@setOnTouchListener true
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        if (!moved) {
                            moved = true
                            mainHandler.removeCallbacks(longPress)
                            morph?.cancel() // a drag beats an in-flight snap
                            // Picked up: lift it, and show the rails it can be dropped onto.
                            if (view.state == BubbleView.State.IDLE) {
                                view.state = BubbleView.State.LIFTED
                            }
                        }
                    }
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    if (moved) {
                        val wasOver = overDismiss
                        overDismiss = isOverDismiss()
                        // Tick on the way in, so "let go now and it's gone" is felt rather than
                        // watched. The bubble is under the finger at this point, so the target it
                        // is about to snap into is the thing most likely to be hidden by the hand.
                        if (overDismiss && !wasOver) hapticThreshold()
                        showDismiss(overDismiss)
                        showRails(nearLeft = contentCenter().first < screenW() / 2f)
                        if (overDismiss) {
                            // Magnetize into the target for a clear "drop to remove" feel.
                            val (cx, cy) = dismissCenter()
                            val cw = params.width - 2 * pad()
                            params.x = (cx - cw / 2f - pad()).toInt()
                            params.y = (cy - contentH() / 2f - pad()).toInt()
                        }
                    }
                    bubble?.let { wm.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
                    scheduleShrink()
                    if (longFired) {
                        // Release ends the take. ACTION_CANCEL lands here too, deliberately:
                        // if the window system takes the gesture away mid-hold we must not
                        // leave a recording running with nothing able to stop it.
                        hapticEnd()
                        endHold()
                        return@setOnTouchListener true
                    }
                    if (moved && !overDismiss && recordingStopper == null && isFlickUp(e)) {
                        // A quick upward flick isn't a move: put the bubble back where it was
                        // and open the model menu instead.
                        hideDismiss()
                        hideRails()
                        params.x = startX
                        params.y = startY
                        bubble?.let { wm.updateViewLayout(it, params) }
                        if (view.state == BubbleView.State.LIFTED) view.state = BubbleView.State.IDLE
                        hapticThreshold()
                        showModelPicker()
                        return@setOnTouchListener true
                    }
                    if (moved && overDismiss) {
                        vibrate(longArrayOf(0, 40))
                        // Drag-to-dismiss is the user switching the bubble off, so it must not
                        // come back on the next boot. Note the position is deliberately NOT
                        // saved here: the last thing they did was drag it onto the dismiss
                        // target, which is nowhere they'd want it to reappear.
                        BubblePrefs.setEnabled(this@BubbleService, false)
                        stopSelf() // drag-to-dismiss
                        return@setOnTouchListener true
                    }
                    hideDismiss()
                    hideRails()
                    overDismiss = false
                    if (moved) {
                        if (view.state == BubbleView.State.LIFTED) view.state = BubbleView.State.IDLE
                        // Snap to the nearer edge, keeping the height the user chose.
                        dockedLeft = contentCenter().first < screenW() / 2f
                        params.y = clampY(params.y)
                        animateGeometry(
                            targetW = params.width,
                            targetX = dockedX(dockedLeft, params.width - 2 * pad()),
                            onEnd = {
                                BubblePrefs.setPosition(this@BubbleService, params.x, params.y)
                            },
                        )
                    }
                    if (!moved) onTap()
                    true
                }
                else -> false
            }
        }

        bubble = view
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            android.util.Log.e("BubbleService", "addView failed (overlay permission?)", e)
        }
        addDismissTarget()
        scheduleShrink()
    }

    private val shrinkRunnable = Runnable { shrink() }

    /** (Re)start the idle countdown; only an idle bubble ever shrinks. */
    private fun scheduleShrink() {
        mainHandler.removeCallbacks(shrinkRunnable)
        if (bubble?.state == BubbleView.State.IDLE) mainHandler.postDelayed(shrinkRunnable, IDLE_SHRINK_MS)
    }

    private fun shrink() {
        val v = bubble ?: return
        if (v.state != BubbleView.State.IDLE) return
        // Shrink toward the docked edge so it tucks in rather than floating off the edge.
        v.pivotX = if (dockedLeft) pad().toFloat() else (v.width - pad()).toFloat()
        v.pivotY = v.height / 2f
        v.animate().scaleX(SHRUNK_SCALE).scaleY(SHRUNK_SCALE).alpha(SHRUNK_ALPHA)
            .setDuration(420L).start()
    }

    /** Back to full size and opacity; cancels a pending shrink. */
    private fun wake(animate: Boolean) {
        mainHandler.removeCallbacks(shrinkRunnable)
        val v = bubble ?: return
        v.animate().cancel()
        if (animate) {
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160L).start()
        } else {
            v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
        }
    }

    /**
     * Animate window width and x together. Both at once is the point: the pill grows from the
     * docked edge inward, which means the far edge travels while the docked one does not — and
     * that only reads correctly if x shrinks by exactly as much as the width grows.
     */
    private fun animateGeometry(targetW: Int, targetX: Int, onEnd: (() -> Unit)? = null) {
        val v = bubble ?: return
        morph?.cancel()
        val fromW = params.width
        val fromX = params.x
        if (fromW == targetW && fromX == targetX) { onEnd?.invoke(); return }
        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MORPH_MS
            interpolator = PathInterpolator(0.3f, 0.7f, 0.2f, 1f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                params.width = (fromW + (targetW - fromW) * f).roundToInt()
                params.x = (fromX + (targetX - fromX) * f).roundToInt()
                try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** Re-apply the dock for the current state (after a state change, rotation or fold). */
    private fun redock(animate: Boolean) {
        val v = bubble ?: return
        val targetW = windowW(v.state)
        val targetX = dockedX(dockedLeft, contentW(v.state))
        params.y = clampY(params.y)
        if (animate) {
            animateGeometry(targetW, targetX)
        } else {
            morph?.cancel()
            params.width = targetW
            params.height = windowH()
            params.x = targetX
            try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
        }
    }

    /** Set the drawn state and move the window to match it. */
    private fun setState(state: BubbleView.State) {
        val v = bubble ?: return
        if (v.state == state) return
        v.state = state
        // Anything happening (listening, working, a confirmation) is shown at full size; back
        // to idle starts the countdown to tuck away again.
        if (state == BubbleView.State.IDLE) scheduleShrink() else wake(animate = true)
        redock(animate = true)
    }

    // ---- drag-to-dismiss target (bottom center) ----

    @SuppressLint("InflateParams")
    private fun addDismissTarget() {
        dismissSize = dp(64f)
        val padding = dp(16f)
        val frame = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@BubbleService, R.drawable.dismiss_background)
            visibility = View.GONE
        }
        val x = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setPadding(padding, padding, padding, padding)
        }
        frame.addView(x, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val p = WindowManager.LayoutParams(
            dismissSize,
            dismissSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(80f)
        }
        dismissView = frame
        try { wm.addView(frame, p) } catch (_: Exception) {}
    }

    /** Screen-space center of the dismiss target (top-left coordinate system). */
    private fun dismissCenter(): Pair<Float, Float> {
        val cx = screenW() / 2f
        val cy = screenH() - dp(80f) - dismissSize / 2f
        return cx to cy
    }

    private fun isOverDismiss(): Boolean {
        val (bubbleCx, bubbleCy) = contentCenter()
        val (cx, cy) = dismissCenter()
        val dx = bubbleCx - cx
        val dy = bubbleCy - cy
        return Math.hypot(dx.toDouble(), dy.toDouble()) < dismissSize * 1.1
    }

    private fun showDismiss(over: Boolean) {
        val v = dismissView ?: return
        v.visibility = View.VISIBLE
        v.background = ContextCompat.getDrawable(
            this,
            if (over) R.drawable.dismiss_background_active else R.drawable.dismiss_background,
        )
        val s = if (over) 1.3f else 1f
        v.scaleX = s; v.scaleY = s
    }

    private fun hideDismiss() {
        val v = dismissView ?: return
        v.visibility = View.GONE
        v.scaleX = 1f; v.scaleY = 1f
    }

    // ---- edge rails (shown while dragging) ----

    /**
     * The two edges the bubble can dock to, drawn only while it is being dragged. The nearer
     * one lights up, so the drop is predicted rather than discovered — the bubble lands on an
     * edge, not under the finger, and without this the snap looks like it went somewhere the
     * user did not ask for.
     */
    private fun railView(left: Boolean): View? {
        val existing = if (left) railLeft else railRight
        if (existing != null) return existing
        val v = View(this)
        val p = WindowManager.LayoutParams(
            dp(4f),
            ViewGroup.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or (if (left) Gravity.START else Gravity.END)
            x = dp(2f)
        }
        v.visibility = View.GONE
        return try {
            wm.addView(v, p)
            if (left) railLeft = v else railRight = v
            v
        } catch (_: Exception) { null }
    }

    private fun showRails(nearLeft: Boolean) {
        val l = railView(left = true)
        val r = railView(left = false)
        l?.apply { visibility = View.VISIBLE; setBackgroundColor(if (nearLeft) RAIL_ON else RAIL_OFF) }
        r?.apply { visibility = View.VISIBLE; setBackgroundColor(if (nearLeft) RAIL_OFF else RAIL_ON) }
    }

    private fun hideRails() {
        railLeft?.visibility = View.GONE
        railRight?.visibility = View.GONE
    }

    /** [fromWidget]: see [widgetTake]. */
    private fun onTap(fromWidget: Boolean = false) {
        val stop = recordingStopper
        if (stop != null) {
            hapticEnd()
            mainHandler.post(stop) // stop the in-progress recording
        } else {
            hapticStart()
            beginDictation(pushToTalk = false, fromWidget = fromWidget)
        }
    }

    /**
     * A widget take, used with the phone locked to talk to the PC: sent to the PC whatever the
     * PC-send setting says (there is no field to type into), recorded in Quiet mode (the user
     * whispers there), and ended after [WIDGET_AUTO_STOP_S] of silence: with the phone locked
     * the wait is dead time, and the screen shows it running out. The saved settings stay as
     * they are.
     */
    private fun widgetTake(s: Settings): Settings =
        s.copy(pcSend = PcLink.PC_ONLY, quietMode = true, autoStopSeconds = WIDGET_AUTO_STOP_S)

    private fun launchRewrite(autoRecord: Boolean = true, pushToTalk: Boolean = false) {
        startActivity(
            Intent(this, RewriteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(RewriteActivity.EXTRA_AUTO_RECORD, autoRecord)
                .putExtra(RewriteActivity.EXTRA_PUSH_TO_TALK, pushToTalk)
        )
    }

    // ---- called from RewriteActivity / the accessibility service (same process) ----

    fun showRecording() {
        DictationStatus.silence.value = 0f
        DictationStatus.phase.value = DictationStatus.Phase.LISTENING
        mainHandler.post {
            holdUntil = 0L
            recording = true
            ensureVisible()
            setState(BubbleView.State.LISTENING)
        }
    }

    fun showAmplitude(amp: Int) {
        DictationStatus.amplitude.value = amp
        bubble?.pushAmplitude(amp)
    }

    /**
     * Transcribing. With no sheet on the voice path, this is the only thing
     * telling the user we're still working.
     *
     * Keeps [recording] set so the bubble stays on screen for the duration — our own
     * activity holds focus while this runs, so the host field it was gating on isn't
     * focused and the gate would otherwise hide the bubble mid-dictation.
     */
    fun showWorking() {
        DictationStatus.silence.value = 0f
        DictationStatus.phase.value = DictationStatus.Phase.WORKING
        mainHandler.post {
            holdUntil = 0L
            recording = true
            ensureVisible()
            setState(BubbleView.State.PROCESSING)
        }
    }

    /**
     * The text went into the field. Called from the accessibility service the moment the write
     * succeeds, which is also the moment RewriteActivity is tearing down and resetting the
     * bubble — hence the hold; see [holdUntil].
     *
     * Worth showing at all because the insert is not always visible: the field may be scrolled,
     * or behind the keyboard, or the user may be looking at the bubble rather than the caret.
     * It is also the only positive signal that distinguishes a successful dictation from the
     * clipboard fallback below, which is otherwise indistinguishable from the user's side.
     */
    fun showInserted() {
        DictationStatus.phase.value = DictationStatus.Phase.INSERTED
        mainHandler.post {
            recording = true
            ensureVisible()
            setState(BubbleView.State.INSERTED)
            hold(INSERTED_HOLD_MS)
        }
    }

    /**
     * We transcribed fine but couldn't type it anywhere, so it went to the clipboard (#78).
     * Android raises its own "Copied" confirmation for that write, which reports a success and
     * names no failure — the dictation reads as having worked while the field stays empty.
     *
     * A toast of our own is not an option: the accessibility service runs in the background,
     * and Android suppresses background toasts, so it never reaches the screen. The bubble is
     * already on screen and is ours, so it carries the message: the clipboard pill holds for
     * [FALLBACK_HOLD_MS], then the bubble returns to idle on its own.
     */
    fun showClipboardFallback() {
        DictationStatus.phase.value = DictationStatus.Phase.COPIED
        mainHandler.post {
            // Held like the working state: by now the dictation is over, so without this the
            // gate could hide the bubble before the user has seen where their text went.
            recording = true
            ensureVisible()
            bubble?.pillLabel = "Copied"
            setState(BubbleView.State.COPIED)
            hold(FALLBACK_HOLD_MS)
        }
    }

    /**
     * Whether the dictation reached the PC ([PcLink]). On the bubble for the same reason as the
     * clipboard fallback: a toast from here would not be shown.
     */
    fun showPcResult(sent: PcLink.Outcome) {
        mainHandler.post {
            // The answer can arrive seconds later; a take that has started since owns the bubble.
            if (HeadlessDictation.active) return@post
            DictationStatus.phase.value = when {
                !sent.ok -> DictationStatus.Phase.NO_PC
                sent.clipboardOnly -> DictationStatus.Phase.PC_CLIP
                else -> DictationStatus.Phase.TO_PC
            }
            recording = true
            ensureVisible()
            // "PC clip": typing was asked for, the PC only allows its clipboard.
            bubble?.pillLabel = if (!sent.ok) "No PC" else if (sent.clipboardOnly) "PC clip" else "To PC"
            setState(BubbleView.State.COPIED)
            hold(FALLBACK_HOLD_MS)
        }
    }

    /** "PC only" has no field on the phone to wait for, so the bubble is not gated on one. */
    private fun gateFor(s: Settings): Boolean =
        s.bubbleOnlyOnFields && PcLink.mode(s) != PcLink.PC_ONLY

    /** Next "Send to PC" mode from the bubble menu; the menu reopens to show it. */
    private fun cyclePcSend() {
        hapticStart()
        serviceScope.launch {
            val repo = SettingsRepository(applicationContext)
            val s = repo.get()
            repo.save(s.copy(pcSend = PcLink.next(PcLink.mode(s))))
            refreshGating()
        }
        mainHandler.postDelayed({ showModelPicker() }, 120)
    }

    /** Clipboard or typing on the PC, from the bubble menu; the menu reopens to show it. */
    private fun togglePcType() {
        hapticStart()
        serviceScope.launch {
            val repo = SettingsRepository(applicationContext)
            val s = repo.get()
            repo.save(s.copy(pcType = !s.pcType))
        }
        mainHandler.postDelayed({ showModelPicker() }, 120)
    }

    private fun hold(ms: Long) {
        holdUntil = SystemClock.uptimeMillis() + ms
        mainHandler.postDelayed({
            if (SystemClock.uptimeMillis() >= holdUntil) { holdUntil = 0L; showIdle() }
        }, ms)
    }

    fun showIdle() {
        mainHandler.post {
            // A confirmation is on screen and owns the bubble until it expires; see [holdUntil].
            if (holdUntil > SystemClock.uptimeMillis()) return@post
            holdUntil = 0L
            recording = false
            DictationStatus.phase.value = DictationStatus.Phase.IDLE
            setState(BubbleView.State.IDLE)
            applyGating(animate = true) // re-hide if gated and no field is focused
        }
    }

    // ---- field-gated visibility (driven by the accessibility service) ----

    /** Whether visibility should track focused text fields (needs accessibility on). */
    private fun gateActive(): Boolean = gateSetting && OpenWisprAccessibilityService.isEnabled

    private fun shouldShow(): Boolean = recording || fieldFocused || !gateActive()

    /**
     * Called by the accessibility service when a host app's editable field gains or
     * loses input focus. No-op when gating is off (bubble is always visible then).
     */
    fun setFieldFocused(focused: Boolean) {
        mainHandler.post {
            val gained = focused && !fieldFocused
            fieldFocused = focused
            if (gained) noteTypingNow() // coming back to a text field counts as using the keyboard
            // Reconcile against the *actual* shown state (incl. alpha), so a transition
            // that left the view VISIBLE-but-transparent can't strand it hidden.
            if (shouldShow() != isShown()) applyGating(animate = true)
        }
    }

    private var lastTypingAt = 0L

    /**
     * The user is typing (or just focused a field) in another app: a shrunk bubble grows back,
     * and the idle countdown restarts, so it only shrinks after the keyboard has sat unused.
     * Called per accessibility event, so it's throttled.
     */
    fun noteTyping() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTypingAt < 250) return
        lastTypingAt = now
        mainHandler.post { noteTypingNow() }
    }

    private fun noteTypingNow() {
        val v = bubble ?: return
        if (v.visibility != View.VISIBLE || v.state != BubbleView.State.IDLE) return
        if (v.scaleX < 1f || v.alpha < 1f) wake(animate = true)
        scheduleShrink()
    }

    /** Truly visible = on screen and not transparent (a hidden bubble can be VISIBLE w/ alpha 0). */
    private fun isShown(): Boolean {
        val c = bubble ?: return false
        return c.visibility == View.VISIBLE && c.alpha > 0.01f
    }

    /** Re-read the gating preference and apply (e.g. after accessibility connects). */
    fun refreshGating() {
        gateSetting = try {
            runBlocking { gateFor(SettingsRepository(applicationContext).get()) }
        } catch (_: Exception) { gateSetting }
        mainHandler.post { applyGating(animate = true) }
    }

    private fun ensureVisible() {
        val c = bubble ?: return
        if (c.visibility == View.VISIBLE && c.alpha == 1f) return
        c.visibility = View.VISIBLE
        c.animate().cancel()
        c.alpha = 1f; c.scaleX = 1f; c.scaleY = 1f
    }

    private fun applyGating(animate: Boolean) {
        val c = bubble ?: return
        c.animate().cancel() // drop any in-flight show/hide so they can't clobber us
        if (shouldShow()) {
            if (c.visibility == View.VISIBLE && c.alpha >= 1f) return
            if (c.visibility != View.VISIBLE) { c.alpha = 0f; c.scaleX = 0.6f; c.scaleY = 0.6f }
            c.visibility = View.VISIBLE
            if (animate && c.alpha < 1f) c.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
            else { c.alpha = 1f; c.scaleX = 1f; c.scaleY = 1f }
            scheduleShrink() // shown again at full size: the idle countdown starts over
        } else {
            if (!isShown()) { c.visibility = View.GONE; return }
            if (animate) {
                c.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(140)
                    // Re-check intent at the end: a re-focus mid-animation must win, and
                    // must restore full opacity (never leave VISIBLE-but-transparent).
                    .withEndAction {
                        if (shouldShow()) { c.alpha = 1f; c.scaleX = 1f; c.scaleY = 1f }
                        else c.visibility = View.GONE
                    }.start()
            } else { c.alpha = 0f; c.visibility = View.GONE }
            picker?.dismiss()
        }
    }

    // ---- haptics ----
    //
    // Four distinct feels, on two axes a thumb can learn without being told: *weight* means
    // commitment, *count* means completion.
    //
    // The axis that matters most is start versus stop, because tap-to-start and tap-to-stop are
    // the same gesture on the same target, and the user is typically watching the text field
    // rather than the bubble. The haptic is the only thing that says which way the toggle went,
    // so the two cannot share one effect — which is exactly what they used to do.
    //
    // Stop feels the same whether you tapped or released a hold: a haptic should report what the
    // app did, not which gesture produced it, because the finger already knows the latter. Start
    // is the exception and deliberately differs between tap and hold, because that distinction
    // is not inferable and has a consequence — it decides whether letting go sends or does
    // nothing. That is what the old double-tick on hold was for; it has moved to a heavy single
    // so that two beats can mean "ended" everywhere without collision.

    /** Lightest. A threshold crossed, not an action taken (e.g. onto the dismiss target). */
    private fun hapticThreshold() =
        predefined(VibrationEffect.EFFECT_TICK, longArrayOf(0, 20))

    /** One crisp beat: a dictation just started. */
    private fun hapticStart() =
        predefined(VibrationEffect.EFFECT_CLICK, longArrayOf(0, 28))

    /** One weighty beat: hold-to-talk is engaged, and releasing will send. */
    private fun hapticHeld() =
        predefined(VibrationEffect.EFFECT_HEAVY_CLICK, longArrayOf(0, 45))

    /** Two beats: recording ended and we have taken it from here. */
    private fun hapticEnd() =
        predefined(VibrationEffect.EFFECT_DOUBLE_CLICK, longArrayOf(0, 22, 45, 22))

    /**
     * Play one of the platform's tuned haptic primitives, falling back to an explicit waveform
     * where they aren't available (they arrive in API 29; this app supports 24).
     *
     * The fallback is a [VibrationEffect] with an amplitude rather than a bare duration. Without
     * one the motor is left to guess, and a short pulse gets rounded away to nothing — which is
     * how the original tap ended up imperceptible despite firing correctly every time.
     */
    private fun predefined(effect: Int, fallback: LongArray) {
        val vibrator = vibrator() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(effect))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(fallback, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(fallback, -1)
            }
        } catch (_: Exception) {}
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    /**
     * Arbitrary patterns, for the one event with no matching primitive: dropping the bubble on
     * the dismiss target. That is destructive and terminal, so it deliberately shares its feel
     * with none of the four above — a longer buzz, not a click.
     */
    private fun vibrate(timings: LongArray) {
        val vibrator = vibrator() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        morph?.cancel()
        morph = null
        picker?.dismiss()
        picker = null
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        bubble?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        dismissView?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        railLeft?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        railRight?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        bubble = null
        dismissView = null
        railLeft = null
        railRight = null
        isRunning = false
        // The bubble going away mid-hold means no ACTION_UP is ever delivered, so clear the flag
        // here too. A stale `true` would tell the next hold-to-talk take that a finger is still
        // down, and nothing would be left to stop it.
        holdingToTalk = false
        if (instance === this) instance = null
    }
}

/** Flick-up to open the model menu: at most this long, at least this far. */
private const val FLICK_MAX_MS = 250L
private const val FLICK_MIN_DP = 36f

/** Rail colours, from the design's OKLCH values. */
private const val RAIL_ON = 0xFF8E77C6.toInt()
private const val RAIL_OFF = 0xB2D5CEE0.toInt()
