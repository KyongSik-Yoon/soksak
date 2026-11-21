package com.voicerewriter

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The always-shown bubble's tucked edge tab, on the lock screen (2026-11-20, user idea): one tap
 * starts a take exactly like [DictateWidget] (sent to the PC, full-screen [LockDictationActivity]).
 *
 * The bubble itself is a [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] window, which
 * Android draws below the keyguard, so it cannot be reused. An accessibility overlay is drawn
 * above it; that window type can only be added through the accessibility service's own window
 * manager, which is why the service owns this. Shown only while the keyguard is up, the bubble
 * service runs and [BubbleService.lockHandleWanted] (always-shown bubble, PC link set up);
 * hidden during a take, whose screen covers everything anyway.
 */
class LockHandle(private val svc: AccessibilityService) {

    companion object {
        private const val TAG = "OpenWisprA11y"

        /**
         * Whether the tab belongs on screen now. Pure, for the unit test. [unlocked]: the user
         * has unlocked since the screen last went off (USER_PRESENT), which wins over
         * [keyguardLocked] — on the phone (build-160) the tab stayed up after unlocking, the
         * keyguard most likely still reading locked while it animated away.
         */
        fun shouldShow(
            keyguardLocked: Boolean,
            unlocked: Boolean,
            wanted: Boolean,
            phase: DictationStatus.Phase,
        ): Boolean =
            keyguardLocked && !unlocked && wanted &&
                phase != DictationStatus.Phase.LISTENING && phase != DictationStatus.Phase.WORKING

        /** Heights the tab takes in turn, each side of the bubble's own: [SHIFT_SLOTS] of them. */
        const val SHIFT_SLOTS = 9
        const val SHIFT_STEP_DP = 16f

        /**
         * How far (in [SHIFT_STEP_DP] steps, -4..4) the tab sits from the bubble's height after
         * the screen has gone off [screenOffs] times. The tab stays lit on the always-on
         * display, so it must not sit on the same pixels for months (OLED burn-in). A timer
         * would not tick there — the CPU sleeps — so it moves once per screen-off instead; wear
         * adds up per pixel either way. The range is twice the 64dp tab, since a few pixels
         * would only soften its edges. Strides of 4 so two nights in a row are far apart.
         */
        fun shiftSlot(screenOffs: Int): Int = Math.floorMod(screenOffs * 4 + 4, SHIFT_SLOTS) - 4

        /** The window's top with the shift, kept on the screen. */
        fun shiftedY(y: Int, slot: Int, stepPx: Int, height: Int, screenHeight: Int): Int =
            (y + slot * stepPx).coerceIn(0, (screenHeight - height).coerceAtLeast(0))
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val keyguard = svc.getSystemService(KeyguardManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var view: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null

    /** USER_PRESENT came since the last SCREEN_OFF; see [shouldShow]. Main thread. */
    private var unlocked = false

    /** Screen-offs so far, for [shiftSlot]. Main thread. */
    private var screenOffs = 0

    // The keyguard has no lock-state callback an app may use, so re-check on every screen
    // event: with a lock delay the keyguard is not up yet at SCREEN_OFF, but is at SCREEN_ON.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { unlocked = false; screenOffs++ }
                Intent.ACTION_USER_PRESENT -> unlocked = true
            }
            Log.i(TAG, "lock handle: ${intent.action?.substringAfterLast('.')} keyguard=${keyguard?.isKeyguardLocked}")
            apply()
        }
    }

    /**
     * A window came or went (from the accessibility service). While the tab is up this
     * re-checks the keyguard, in case USER_PRESENT never came or came too early. Main thread.
     */
    fun onWindowsChanged() {
        if (view != null) apply()
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(svc, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            DictationStatus.phase
                .map { it == DictationStatus.Phase.LISTENING || it == DictationStatus.Phase.WORKING }
                .distinctUntilChanged()
                .collect { refresh() }
        }
        refresh()
    }

    fun stop() {
        runCatching { svc.unregisterReceiver(receiver) }
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        remove()
    }

    /** Any thread. */
    fun refresh() {
        main.post { apply() }
    }

    private fun apply() {
        val bubble = BubbleService.instance
        val locked = keyguard?.isKeyguardLocked == true
        if (bubble != null && shouldShow(locked, unlocked, bubble.lockHandleWanted, DictationStatus.phase.value)) {
            show(bubble)
        } else {
            remove()
        }
    }

    private fun dp(v: Float): Int = (v * svc.resources.displayMetrics.density).roundToInt()

    private fun show(bubble: BubbleService) {
        val place = bubble.lockHandlePlace()
        val w = dp(BubbleView.TUCKED_W_DP)
        val x = if (place.left) 0 else svc.resources.displayMetrics.widthPixels - w
        // Kept through the lit lock screen too: the tab must not jump as the screen wakes
        // under a finger already on its way.
        val y = shiftedY(
            place.y, shiftSlot(screenOffs), dp(SHIFT_STEP_DP), place.height,
            svc.resources.displayMetrics.heightPixels,
        )
        val existing = view
        val p = params
        if (existing != null && p != null) {
            existing.dockedLeft = place.left
            existing.dark = place.dark
            if (p.x != x || p.y != y || p.height != place.height) {
                p.x = x; p.y = y; p.height = place.height
                runCatching { wm.updateViewLayout(existing, p) }
                Log.i(TAG, "lock handle moved (y=$y, bubble y=${place.y})")
            }
            return
        }
        val v = BubbleView(svc).apply {
            state = BubbleView.State.TUCKED
            dockedLeft = place.left
            dark = place.dark
            contentDescription = "Soksak: dictate to PC"
        }
        val lp = WindowManager.LayoutParams(
            w,
            place.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        attachTouch(v)
        try {
            wm.addView(v, lp)
            view = v
            params = lp
            Log.i(TAG, "lock handle shown (left=${place.left} y=$y, bubble y=${place.y})")
        } catch (e: Exception) {
            Log.w(TAG, "lock handle not added", e)
        }
    }

    private fun remove() {
        val v = view ?: return
        view = null
        params = null
        runCatching { wm.removeView(v) }
        Log.i(TAG, "lock handle removed")
    }

    /**
     * A clean tap starts the take; a slide (a grip, or a system edge gesture taking it with
     * ACTION_CANCEL) does nothing.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(v: BubbleView) {
        val slop = 12 * svc.resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE ->
                    if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) moved = true
                MotionEvent.ACTION_UP -> if (!moved) {
                    val bubble = BubbleService.instance
                    Log.i(TAG, "lock handle tapped (bubble=${bubble != null})")
                    bubble?.dictateFromWidget()
                }
            }
            true
        }
    }
}
