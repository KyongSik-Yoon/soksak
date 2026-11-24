package com.voicerewriter

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.voicerewriter.textproc.InsertSplice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Makes "insert the rewrite into the field I was typing in" possible. An overlay
 * cannot write into other apps — only an IME, the owning app, or an accessibility
 * service can.
 *
 * Insertion is event-driven: RewriteActivity hands us the text and finishes; once
 * the *host* app regains window focus (we ignore our own windows), we paste the
 * clipboard into its focused editable field. Timed retries back this up in case
 * no focus event fires. This avoids the trap of inserting while our own activity
 * is still the active window.
 */
class OpenWisprAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OpenWisprA11y"
        private const val DIAG_PREFS = "insert_diag"
        private const val KEY_LAST_FALLBACK = "last_fallback"

        /** Send button search: ancestors of the field to try, nodes under each, nodes in the window. */
        private const val SEND_SEARCH_LEVELS = 8
        private const val SEND_SEARCH_NEAR = 600
        private const val SEND_SEARCH_WINDOW = 3000
        /** Ancestors of the field searched for a send button scrolled out of view (the composer box). */
        private const val SEND_SEARCH_HIDDEN_LEVELS = 3
        /** After asking the page to scroll a send button into view, before tapping it. */
        private const val SHOW_SETTLE_MS = 400L

        /** "MM-dd HH:mm · why" for the last dictation that ended on the clipboard, or null. */
        fun lastFallback(context: Context): String? =
            context.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_FALLBACK, null)

        @Volatile
        private var instance: OpenWisprAccessibilityService? = null

        /**
         * Package of the last non-self app to take window focus. The dictation
         * pipeline reads this to decide whether the target is a code/terminal field
         * (where spoken "dot"/"slash"/"dash" should pass through as words).
         */
        @Volatile
        var lastHostPackage: String? = null
            private set

        /**
         * System surfaces whose window-state changes must NOT be mistaken for the
         * dictation target. The status bar / notification shade / nav bar all report
         * as "com.android.systemui", and the keyboard reports as an input-method
         * package — neither is the app the user is dictating into, so letting them
         * overwrite [lastHostPackage] is what made history entries read "System UI".
         */
        private val NON_HOST_PACKAGES = setOf(
            "com.android.systemui", "android", "com.android.launcher", "com.sec.android.app.launcher",
        )

        private fun isHostPackage(pkg: String, self: String): Boolean =
            pkg != self && pkg !in NON_HOST_PACKAGES &&
                !pkg.contains("inputmethod") && !pkg.contains("honeyboard")

        /** True when the user has enabled the service in Accessibility settings. */
        val isEnabled: Boolean get() = instance != null

        /** Re-decide the lock-screen tab ([LockHandle]); any thread, no-op without the service. */
        fun refreshLockHandle() {
            instance?.lockHandle?.refresh()
        }

        /**
         * Stage [text] on the clipboard and insert it into the host app's focused
         * field as soon as that app is back in front. Returns true if the service
         * is running (so the caller knows auto-insert will be attempted).
         * Call from the main thread. [onScreen] ([Settings.fieldOnScreen]): with no field
         * selected, write into the text field on screen without raising the keyboard.
         */
        fun enqueueInsert(text: String, enterAfterMs: Long = 0L, onScreen: Boolean = false): Boolean {
            val svc = instance ?: return false
            svc.startInsert(text, enterAfterMs, onScreen)
            return true
        }

        /**
         * The bubble's Auto Enter countdown ran out: press Enter in the field the last dictation
         * went into, if it still has focus in the same app. [onDone] gets whether Enter was
         * pressed (on the main thread, possibly after a short check). See [AutoEnter].
         */
        fun pressEnter(onDone: (Boolean) -> Unit) {
            val svc = instance ?: return onDone(false)
            svc.enterHandler.post { svc.doPressEnter(onDone) }
        }

        /** Forget the armed Enter, or stop one being pressed (the bubble was tapped, a new take). */
        fun cancelEnter() {
            val svc = instance ?: return
            svc.enterTarget = null
            svc.enterCancelled = true
        }

        /** Re-check focus now (e.g. when the bubble (re)starts) so it shows if a field is already focused. */
        fun reevaluate() {
            val svc = instance ?: return
            svc.main.post { svc.evaluateFieldFocus() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val clipHandler = Handler(Looper.getMainLooper())
    /** Auto Enter's own queue: `main` is wiped on every insert. */
    private val enterHandler = Handler(Looper.getMainLooper())

    /**
     * Where an armed Auto Enter will go; null when none is armed. [field]: the field on screen the
     * text went into without focus ([fieldOnScreen]); Enter has no focused field to find there.
     */
    private class EnterTarget(val pkg: String, val text: String, val armedAt: Long, val field: AccessibilityNodeInfo? = null)
    @Volatile private var enterTarget: EnterTarget? = null
    /** Set by [cancelEnter] while a press is being checked, so the send-button fallback holds off. */
    @Volatile private var enterCancelled = false
    /** Auto Enter delay for the insert in flight, handed over by [enqueueInsert]. */
    @Volatile private var pendingEnterMs = 0L
    @Volatile private var pendingText: String? = null
    /** The insert in flight may use the field on screen ([enqueueInsert]'s onScreen). */
    @Volatile private var pendingOnScreen = false
    /** When [fieldOnScreen] last scanned, to keep a burst of content events from rescanning. */
    private var lastScanAt = 0L
    private val retryDelays = longArrayOf(250, 500, 900, 1400, 2000)

    /**
     * The last editable field the user had focused in a host app. Tapping the bubble brings
     * up RewriteActivity, which takes window focus away from the host; plenty of apps
     * (messengers especially) do not hand input focus back to the composer when they return
     * to the front, so a focus-only lookup found nothing and the dictation fell back to the
     * clipboard. Remembering the field lets [attemptInsert] write into it directly and
     * re-focus it.
     */
    private var lastEditable: AccessibilityNodeInfo? = null

    /** The bubble's tab on the lock screen; owned here because only this service may draw there. */
    private var lockHandle: LockHandle? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "service connected")
        lockHandle = LockHandle(this).also { it.start() }
        // Now that focus detection is available, let the bubble switch to its
        // "only show on text fields" behavior (it starts always-visible without us).
        BubbleService.instance?.refreshGating()
        main.post { evaluateFieldFocus() }
    }

    /** Debounced re-check of whether a host text field is focused (drives the bubble). */
    private val fieldCheck = Runnable { evaluateFieldFocus() }

    /**
     * Tell the bubble whether the user can currently type somewhere. Skips our own
     * windows so the recording sheet doesn't flap the bubble.
     *
     * Two independent signals, OR'd: an open keyboard, and a focused editable node.
     * The node scan alone is what left the bubble missing in Samsung Notes, Claude and
     * ChatGPT (#69, #70) — plenty of apps never surface their composer as a focused
     * editable we can see, whether because it's a Compose/WebView field, a custom
     * editor, or a node the app marked sensitive (Android 14+ hides those from
     * services that aren't declared accessibility tools). The keyboard is the more
     * reliable of the two: if an IME has a window up, something editable has focus,
     * however the app chose to draw it.
     */
    private fun evaluateFieldFocus() {
        // Scan all interactive windows, not just rootInActiveWindow — across an app
        // switch the "active window" can be null/transient, which left the bubble
        // stuck. The focused editable lives in whichever window holds input focus.
        val wins = try { windows } catch (_: Exception) { null }
        if (wins.isNullOrEmpty()) {
            val root = rootInActiveWindow ?: return
            if (root.packageName == packageName) return
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val editable = f != null && f.isEditable
            @Suppress("DEPRECATION") f?.recycle()
            Log.d(TAG, "fieldFocus(fallback) editable=$editable")
            BubbleService.instance?.setFieldFocused(editable)
            return
        }
        var editable = false
        var imeOpen = false
        var ourModalActive = false
        for (w in wins) {
            // Checked before w.root, which an IME window need not give us at all.
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) { imeOpen = true; continue }
            val root = w.root ?: continue
            if (root.packageName == packageName) {
                // Our recording/transform sheet (an activity) — don't flap the bubble.
                // The bubble's own overlay window is harmless; only the modal counts.
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) ourModalActive = true
                continue
            }
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null) {
                if (f.isEditable) {
                    editable = true
                    lastEditable = f // kept, not recycled: see [lastEditable]
                } else {
                    // The user moved focus somewhere non-editable in a host app: whatever
                    // field we remembered is no longer the target.
                    lastEditable = null
                    @Suppress("DEPRECATION") f.recycle()
                }
            }
            if (editable) break
        }
        if (ourModalActive) return // leave the bubble as-is while our sheet is up
        Log.d(TAG, "fieldFocus editable=$editable ime=$imeOpen host=$lastHostPackage")
        BubbleService.instance?.setFieldFocused(editable || imeOpen, imeOpen)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Track the foreground host app (cheap: window changes are infrequent) so the
        // dictation pipeline can adapt normalization to code/terminal fields.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) lockHandle?.onWindowsChanged()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && isHostPackage(pkg, packageName)) lastHostPackage = pkg
        }
        // Drive the field-gated bubble: re-check focus on any event that can change it
        // (coalesced — content-changed and windows-changed both fire in bursts).
        // TYPE_WINDOWS_CHANGED is what tells us the keyboard came or went: dismissing
        // an IME needn't touch focus or window state, so without it the bubble would
        // stay up after the keyboard slid away.
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                main.removeCallbacks(fieldCheck)
                main.postDelayed(fieldCheck, 120)
            }
        }
        // Typing in another app wakes a shrunk bubble (and restarts its idle countdown).
        // Content-changed is deliberately not activity: a streaming chat reply fires it
        // constantly and would keep the bubble from ever shrinking.
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val pkg = event.packageName
                if (pkg != null && pkg != packageName) BubbleService.instance?.noteTyping()
            }
        }
        enterTarget?.let { t -> watchArmedEnter(event, t) }
        if (pendingText == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName
                if (pkg != null && pkg != packageName) attemptInsert()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(fieldCheck)
        lockHandle?.stop()
        lockHandle = null
        if (instance === this) instance = null
        // Service gone — focus detection is impossible, so let the bubble show always.
        BubbleService.instance?.refreshGating()
    }

    // ---------------- insertion ----------------

    private fun startInsert(text: String, enterAfterMs: Long, onScreen: Boolean) {
        // Note: we deliberately do NOT stage the clipboard here. The common insert path
        // writes the text straight into the field (ACTION_SET_TEXT) without touching the
        // clipboard, so a successful dictation no longer clobbers what the user had copied.
        // The clipboard is only used as a fallback (see attemptInsert / the give-up branch).
        pendingText = text
        pendingEnterMs = enterAfterMs
        pendingOnScreen = onScreen
        lastScanAt = 0L
        if (enterTarget != null) {
            enterTarget = null
            BubbleService.instance?.cancelEnter()
        }
        lastAttempt = null
        main.removeCallbacksAndMessages(null)
        // Backstop retries in case the focus event doesn't arrive.
        for (delay in retryDelays) main.postDelayed({ attemptInsert() }, delay)
        // Give up after the last retry: no field ever focused — fall back to a clipboard copy.
        main.postDelayed({
            val t = pendingText
            if (t != null) {
                pendingText = null
                setClipboard(t)
                recordFallback(lastAttempt ?: "no editable field found")
                // Say what happened. Reaching here means accessibility is running and we still
                // couldn't find an editable node to write into — which is the normal outcome in
                // apps that don't expose their composer (#78), not something the user did.
                //
                // Android 13+ raises its own "Copied" confirmation for the clipboard write, but
                // that reports a success and names no failure, so the dictation reads as having
                // worked while the field stays empty. This is the only place that says otherwise.
                //
                // Deliberately not the same as the no-accessibility path in acceptVoice, which
                // stays silent: a user who declined the permission gets the clipboard by choice
                // and doesn't need telling after every dictation.
                //
                // Said on the bubble rather than in a toast of ours: this service runs in the
                // background, and Android drops background toasts before they reach the screen.
                BubbleService.instance?.showClipboardFallback()
            }
        }, retryDelays.last() + 300)
    }

    /** Try once to insert into the host app's focused editable field. */
    /** What the last insert attempt ran into; shown in Settings when the clipboard fallback fires. */
    @Volatile private var lastAttempt: String? = null

    private fun attemptInsert() {
        val text = pendingText ?: return
        var refocus = false
        val focused = findHostFocusedEditable()
        // No editable node has focus, yet something is taking keyboard input: type it in there.
        if (focused == null && commitToInputConnection(text)) {
            Log.i(TAG, "inserted through the input connection (clipboard untouched)")
            pendingText = null
            main.removeCallbacksAndMessages(null)
            vibrateTick()
            inserted(inputConnectionPackage(), text)
            return
        }
        var onScreen: AutoEnter.FieldPick? = null
        val node = focused
            ?: rememberedEditable()?.also {
                // The bubble always shown: the app let the field go with the keyboard. Refocusing
                // would click the keyboard back up; write the way the field on screen is written.
                if (pendingOnScreen) onScreen = AutoEnter.FieldPick(-1, enter = true) else refocus = true
            }
            ?: fieldOnScreen()?.let { (n, pick) -> onScreen = pick; n }
            ?: run { if (!pendingOnScreen) lastAttempt = "no editable field found"; return } // else fieldOnScreen said why
        val where = "${node.packageName}/${node.className?.toString()?.substringAfterLast('.')}"
        val pkg = node.packageName?.toString()
        var viaPaste = false
        // Kept for Auto Enter, which finds no focused field to press in after an unfocused insert.
        @Suppress("DEPRECATION")
        val screenField = onScreen?.takeIf { it.enter }?.let { AccessibilityNodeInfo.obtain(node) }
        val ok = try {
            if (onScreen != null) {
                // No keyboard: neither focus nor click, which would raise it. No paste either,
                // which needs focus and would leave the text on the clipboard. Without a cursor the
                // text goes at the end. A field that takes SET_TEXT only with focus gets just that.
                insertAtCursor(node, text) || appendAtEnd(node, text) ||
                    (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) && appendAtEnd(node, text))
            } else {
            if (refocus) {
                // Give the field input focus back before writing, and click it so the app
                // raises its keyboard again — the user was mid-typing when they tapped the
                // bubble, and an insert into a field that then sits unfocused reads as the
                // input having been closed.
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.refresh()
            }
            // Prefer a clipboard-free splice at the cursor; fall back to paste only when
            // we can't determine the cursor (e.g. some WebView fields). Last resort, which used to end in the clipboard: append at the end of the field.
            // Web fields (a browser's search box) often report neither a cursor nor a usable
            // paste, but do take SET_TEXT, and the end is where the cursor almost always is.
            insertAtCursor(node, text) ||
                run { viaPaste = true; pasteViaClipboard(node, text) } ||
                run { viaPaste = false; appendAtEnd(node, text) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "insert action failed", e); false
        } finally {
            @Suppress("DEPRECATION") node.recycle()
        }
        if (!ok) lastAttempt = "field $where refused set-text, paste and append"
        if (ok) {
            // Worth distinguishing: the paste path leaves the dictation on the clipboard,
            // overwriting whatever the user had copied, and on Android 13+ the system shows
            // its own "Copied" confirmation for it. Both read as success without this.
            Log.i(TAG, when {
                onScreen != null -> "inserted into the field on screen without focus"
                viaPaste -> "inserted via clipboard paste (clipboard overwritten)"
                else -> "inserted at cursor (clipboard untouched)"
            })
            if (onScreen?.enter == false && pendingEnterMs > 0) {
                Log.i(TAG, "auto Enter not armed: the field on screen was one of several")
                pendingEnterMs = 0L
            }
            pendingText = null
            main.removeCallbacksAndMessages(null)
            // The haptic tick is the confirmation. A toast on top of text visibly appearing in
            // the field is telling the user something they can already see.
            vibrateTick()
            // The bubble says so too, briefly. Not redundant with the text appearing: the field
            // may be scrolled, hidden behind the keyboard, or simply not where the user is
            // looking — and this is the only signal that tells a real insert apart from the
            // clipboard fallback below, which from the user's side looks identical.
            inserted(pkg, text, screenField)
        }
    }

    /**
     * With the keyboard down and no field selected (the bubble always shown, [Settings.fieldOnScreen]):
     * the text field on screen in the app in front, and whether Enter may follow
     * ([AutoEnter.pickField]). Never on the lock screen, in system UI or the launcher, nor a
     * password field.
     */
    private fun fieldOnScreen(): Pair<AccessibilityNodeInfo, AutoEnter.FieldPick>? {
        if (!pendingOnScreen) return null
        val now = SystemClock.uptimeMillis()
        if (now - lastScanAt < 200) return null
        lastScanAt = now
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) { lastAttempt = "locked: no field on screen"; return null }
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (!isHostPackage(pkg, packageName)) return null
        val fields = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < 3000) {
            val n = queue.removeFirst()
            seen++
            if (n.isEditable && n.isVisibleToUser && n.isEnabled && !n.isPassword) { fields += n; continue }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        val pick = AutoEnter.pickField(fields.size) {
            val fb = android.graphics.Rect().also { r -> fields[it].getBoundsInScreen(r) }
            (findSendButton(fields[it], root, fb) ?: trailingUnnamedButton(fields[it], fb)) != null
        }
        Log.i(TAG, "fields on screen in $pkg: ${fields.size}, picked ${pick?.index} enter=${pick?.enter}")
        if (pick == null) {
            lastAttempt = if (fields.isEmpty()) "no field on screen in $pkg" else "${fields.size} fields on screen in $pkg, none clearly the one"
            return null
        }
        return fields[pick.index] to pick
    }

    /**
     * Clipboard-free insert: splice [insert] in at the cursor (replacing any active
     * selection) via ACTION_SET_TEXT, then place the cursor after it. Returns false when
     * the cursor can't be determined in a non-empty field, so the caller can fall back to
     * paste. This is the path that keeps the clipboard untouched on a normal dictation.
     */
    private fun insertAtCursor(node: AccessibilityNodeInfo, insert: String): Boolean {
        // An empty field can report its placeholder ("Ask Gemini") as its text, with no cursor.
        // Read that as non-empty and every first dictation into it took the clipboard paste
        // path (seen in the Gemini app). The hint is not content: treat the field as empty.
        val current = currentText(node)
        val selA = node.textSelectionStart
        val selB = node.textSelectionEnd
        val (start, end) = when {
            selA in 0..current.length && selB in 0..current.length ->
                minOf(selA, selB) to maxOf(selA, selB)
            current.isEmpty() -> 0 to 0
            else -> return false // unknown cursor in a non-empty field — let paste handle it
        }
        val (newText, cursor) = InsertSplice.at(current, start, end, insert)
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        return true
    }

    /**
     * The field's real content. An empty field can report its placeholder as its text: native
     * fields flag that (isShowingHintText; seen in Gemini), web fields often don't and just
     * repeat the placeholder ("검색어를 입력해 주세요." in Naver's search box), so text equal to
     * the hint counts as empty too.
     */
    private fun currentText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString() ?: return ""
        if (Build.VERSION.SDK_INT >= 26) {
            if (node.isShowingHintText) return ""
            val hint = node.hintText?.toString()
            if (!hint.isNullOrEmpty() && text == hint) return ""
        }
        return text
    }

    /** Last resort: SET_TEXT with the dictation appended to whatever the field holds. */
    private fun appendAtEnd(node: AccessibilityNodeInfo, insert: String): Boolean {
        node.refresh()
        val current = currentText(node)
        val sep = if (current.isNotEmpty() && !current.endsWith(" ") && !current.endsWith("\n")) " " else ""
        val newText = current + sep + insert
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return true
    }

    // ---------------- auto Enter ----------------

    /** The insert landed: confirm it on the bubble, which counts down to Enter when that is on. */
    private fun inserted(pkg: String?, text: String, field: AccessibilityNodeInfo? = null) {
        val ms = pendingEnterMs
        pendingEnterMs = 0L
        val bubble = BubbleService.instance
        if (ms > 0 && pkg != null && bubble != null) {
            enterTarget = EnterTarget(pkg, text, SystemClock.uptimeMillis(), field)
            Log.i(TAG, "auto Enter armed: ${ms}ms in $pkg")
            bubble.showInserted(ms)
        } else {
            bubble?.showInserted()
        }
    }

    /** The app taking input through the input connection (Android 13+), for [inserted]. */
    private fun inputConnectionPackage(): String? =
        if (Build.VERSION.SDK_INT >= 33) inputMethod?.currentInputEditorInfo?.packageName ?: lastHostPackage
        else lastHostPackage

    /**
     * Cancel an armed Enter when the user types in that app (they are editing what was said) or
     * another app comes to the front. Our own insert raises text events too, hence the grace.
     */
    private fun watchArmedEnter(event: AccessibilityEvent, t: EnterTarget) {
        val pkg = event.packageName?.toString() ?: return
        val reason = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                if (pkg == t.pkg && SystemClock.uptimeMillis() - t.armedAt > AutoEnter.TYPING_GRACE_MS) "typing" else null
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (isHostPackage(pkg, packageName) && pkg != t.pkg) "switched to $pkg" else null
            else -> null
        } ?: return
        Log.i(TAG, "auto Enter cancelled: $reason")
        enterTarget = null
        BubbleService.instance?.cancelEnter()
    }

    private fun doPressEnter(onDone: (Boolean) -> Unit) {
        val t = enterTarget ?: return onDone(false)
        enterTarget = null
        enterCancelled = false
        try {
            val field = t.field
            if (field != null) {
                // The text went into the field on screen with the keyboard down: press there, in
                // the field it went into, whatever has focus now. Going by focus skipped Enter in
                // agent world, a PWA in Chrome (build-154): another EditText of the page held focus by the time the
                // countdown ran out, and the dictated text was not in that one.
                if (!field.refresh() || !field.isVisibleToUser || field.packageName?.toString() != t.pkg) {
                    Log.i(TAG, "auto Enter skipped: the field on screen is gone")
                    return onDone(false)
                }
                findHostFocusedEditable()?.let { f ->
                    if (f != field) {
                        Log.i(TAG, "auto Enter: focus is on ${f.packageName}/${f.className?.toString()?.substringAfterLast('.')} " +
                            "visible=${f.isVisibleToUser}; pressing in the field the text went into")
                    }
                    @Suppress("DEPRECATION") f.recycle()
                }
                @Suppress("DEPRECATION")
                return enterOnNode(AccessibilityNodeInfo.obtain(field), t) { ok ->
                    // Could not send: bring up the keyboard on the field so the user can finish.
                    // Only while the text is still there: gone means it was sent some other way.
                    if (!ok && !enterCancelled && field.refresh() && AutoEnter.stillThere(currentText(field), t.text)) {
                        Log.i(TAG, "auto Enter: could not send from the field on screen, raising the keyboard")
                        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    onDone(ok)
                }
            }
            val node = findHostFocusedEditable()
            if (node == null) {
                // Editors without a focused editable node (Samsung Notes, terminals) took the text
                // through the input connection; Enter goes the same way.
                val ok = enterThroughInputConnection(t.pkg)
                if (!ok) Log.i(TAG, "auto Enter skipped: no focused field in ${t.pkg}")
                return onDone(ok)
            }
            val pkg = node.packageName?.toString()
            if (pkg != t.pkg) {
                Log.i(TAG, "auto Enter skipped: focus is now in $pkg, not ${t.pkg}")
                @Suppress("DEPRECATION") node.recycle()
                return onDone(false)
            }
            enterOnNode(node, t, onDone)
        } catch (e: Exception) {
            Log.w(TAG, "auto Enter failed", e)
            onDone(false)
        }
    }

    /**
     * IME action on the field; if the text is still there afterwards, the send button beside it.
     * A newline the app added for the IME action is taken back before clicking send, so the
     * message does not end in a blank line. No send button and a newline: the newline stays and
     * the press counts as not sent, so the bubble says so ("Not sent").
     */
    private fun enterOnNode(node: AccessibilityNodeInfo, t: EnterTarget, onDone: (Boolean) -> Unit) {
        val where = "${node.packageName}/${node.className?.toString()?.substringAfterLast('.')}"
        val before = currentText(node)
        // Another field of the same app, or the text already gone: Enter would send something else.
        if (!AutoEnter.stillThere(before, t.text)) {
            Log.i(TAG, "auto Enter skipped: the dictated text is not in the focused field $where")
            @Suppress("DEPRECATION") node.recycle()
            return onDone(false)
        }
        val imeEnter = Build.VERSION.SDK_INT >= 30 &&
            node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }
        if (imeEnter && node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            Log.i(TAG, "auto Enter: IME action on $where ${boundsOf(node)}")
            enterHandler.postDelayed({
                val result = try {
                    val alive = node.refresh()
                    val after = if (alive) currentText(node) else ""
                    // Lengths and line counts only, never the text: enough to see how it was judged.
                    Log.i(TAG, "auto Enter: field before ${before.length} chars/${before.count { it == '\n' }} breaks, " +
                        "after alive=$alive ${after.length} chars/${after.count { it == '\n' }} breaks")
                    if (!alive) Log.i(TAG, "auto Enter: the field went away after the IME action")
                    if (enterCancelled) {
                        Log.i(TAG, "auto Enter: cancelled during the check, no fallback")
                        false
                    } else when (val outcome = AutoEnter.judge(before, after, t.text)) {
                        AutoEnter.Outcome.SENT -> { Log.i(TAG, "auto Enter: sent by the IME action"); true }
                        AutoEnter.Outcome.NEWLINE -> {
                            Log.i(TAG, "auto Enter: IME action added a newline")
                            clickSendButton(node, restore = before)
                        }
                        AutoEnter.Outcome.NOTHING -> {
                            Log.i(TAG, "auto Enter: IME action left the text ($outcome)")
                            clickSendButton(node, restore = null) || enterThroughInputConnection(t.pkg)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "auto Enter check failed", e); false
                } finally {
                    @Suppress("DEPRECATION") node.recycle()
                }
                onDone(result)
            }, AutoEnter.VERIFY_MS)
            return
        }
        Log.i(TAG, "auto Enter: no IME action on $where ${boundsOf(node)}")
        val ok = try {
            clickSendButton(node, restore = null) || enterThroughInputConnection(t.pkg)
        } finally {
            @Suppress("DEPRECATION") node.recycle()
        }
        onDone(ok)
    }

    /** Click the send button nearest [field] in its window. [restore]: field text to put back first. */
    private fun clickSendButton(field: AccessibilityNodeInfo, restore: String?): Boolean {
        val root = field.window?.root ?: rootInActiveWindow ?: return false
        val fb = android.graphics.Rect().also { field.getBoundsInScreen(it) }
        val found = findSendButton(field, root, fb)
        val hidden = if (found == null) hiddenSendButton(field, fb) else null
        val offscreen = hidden != null
        val (btn, label) = found ?: hidden ?: trailingUnnamedButton(field, fb) ?: run {
            Log.i(TAG, "auto Enter: no send button found; beside the field: ${buttonsNear(field)}")
            return false
        }
        if (restore != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, restore)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        if (offscreen) {
            // agent world with a picture attached: the grown field reached the keyboard and pushed
            // its Send row under it (2026-11-20, user log). Scroll it into view, then press it.
            val shown = btn.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            Log.i(TAG, "auto Enter: send button \"$label\" is off screen ${boundsOf(btn)}; show on screen=$shown")
            enterHandler.postDelayed({
                if (enterCancelled) { Log.i(TAG, "auto Enter: cancelled while scrolling to the send button"); return@postDelayed }
                val visible = btn.refresh() && btn.isVisibleToUser
                Log.i(TAG, "auto Enter: after scrolling, send button visible=$visible ${boundsOf(btn)}")
                pressSendButton(btn, label, tap = visible)
            }, SHOW_SETTLE_MS)
            return true
        }
        return pressSendButton(btn, label, tap = true)
    }

    /**
     * A real tap on a web page's button ([inWebContent]) when [tap] allows it (the button is on
     * screen), otherwise an accessibility click.
     */
    private fun pressSendButton(btn: AccessibilityNodeInfo, label: String, tap: Boolean): Boolean {
        if (tap && inWebContent(btn) && tapCenter(btn)) {
            Log.i(TAG, "auto Enter: send button \"$label\" tapped")
            return true
        }
        val ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "auto Enter: send button \"$label\" clicked=$ok")
        return ok
    }

    /**
     * Whether [n] is part of a web page (Chrome, a WebView). There a send button gets a real tap: in
     * Collie (a PWA in Chrome) the accessibility click was accepted (clicked=true) yet nothing was
     * sent, and it took focus off the field and closed the keyboard, while a finger on the same
     * button sends (2026-11-17, user). A tap is what the page is written for.
     */
    private fun inWebContent(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n.parent
        repeat(40) {
            val c = cur ?: return false
            if (c.className?.toString() == "android.webkit.WebView") return true
            cur = c.parent
        }
        return false
    }

    /**
     * A short tap at the middle of [n]'s on-screen bounds. False (so the caller clicks instead) when
     * it cannot be sent, or when one of our own windows (the bubble) covers that point and would
     * take the tap itself.
     */
    private fun tapCenter(n: AccessibilityNodeInfo): Boolean {
        val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
        if (b.isEmpty) return false
        val x = b.exactCenterX()
        val y = b.exactCenterY()
        val over = try { windows } catch (_: Exception) { null }.orEmpty().filter { w ->
            android.graphics.Rect().also { w.getBoundsInScreen(it) }.contains(x.toInt(), y.toInt())
        }
        if (over.any { it.root?.packageName == packageName }) {
            Log.i(TAG, "auto Enter: the bubble covers the send button, clicking instead"); return false
        }
        // A tap there would type on the keyboard instead.
        if (over.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            Log.i(TAG, "auto Enter: the keyboard covers the send button, clicking instead"); return false
        }
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return try { dispatchGesture(gesture, null, null) } catch (e: Exception) { Log.w(TAG, "tap failed", e); false }
    }

    /** A toolbar row under or over the field (Claude's composer) is within reach; the chat isn't. */
    private fun sendReach(): Int = (72 * resources.displayMetrics.density).toInt()

    /** The visible send button nearest [field] (bounds [fb]) under [root], with its label; null when there is none. */
    private fun findSendButton(
        field: AccessibilityNodeInfo, root: AccessibilityNodeInfo, fb: android.graphics.Rect,
    ): Pair<AccessibilityNodeInfo, String>? {
        val reach = sendReach()
        // The composer around the field first, nearest ancestor out: a whole-window scan has a node
        // budget, and a long conversation above the composer may spend it before the button.
        var anc = field.parent
        var levels = 0
        while (levels++ < SEND_SEARCH_LEVELS) {
            val a = anc ?: break
            val (found, full) = sendButtonUnder(a, fb, reach, SEND_SEARCH_NEAR)
            if (found != null) return found
            if (full) break // as big as the window gets; the scan below will not do better nearby
            anc = a.parent
        }
        val (found, full) = sendButtonUnder(root, fb, reach, SEND_SEARCH_WINDOW)
        if (found != null) return found
        if (full) Log.i(TAG, "auto Enter: send button scan hit its node limit")
        return null
    }

    /**
     * A send button in the field's own composer (a few ancestors up) that is not on screen: scrolled
     * away, or under the keyboard. Only that close, since pressing it means scrolling the page.
     */
    private fun hiddenSendButton(field: AccessibilityNodeInfo, fb: android.graphics.Rect): Pair<AccessibilityNodeInfo, String>? {
        val reach = sendReach()
        var anc = field.parent
        repeat(SEND_SEARCH_HIDDEN_LEVELS) {
            val a = anc ?: return null
            sendButtonUnder(a, fb, reach, SEND_SEARCH_NEAR, hidden = true).first?.let { return it }
            anc = a.parent
        }
        return null
    }

    /**
     * No button says "send": the unnamed one ending the field's row ([AutoEnter.trailingButton]).
     * Reached only once the IME action has not sent, so the text is still waiting in the field.
     */
    private fun trailingUnnamedButton(field: AccessibilityNodeInfo, fb: android.graphics.Rect): Pair<AccessibilityNodeInfo, String>? {
        var top: AccessibilityNodeInfo = field
        repeat(4) { top = top.parent ?: return@repeat }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val boxes = mutableListOf<IntArray>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(top) }
        var seen = 0
        while (queue.isNotEmpty() && seen < SEND_SEARCH_NEAR) {
            val n = queue.removeFirst()
            seen++
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            if (n.isEditable || !n.isVisibleToUser || !n.isEnabled) continue
            if (n.className?.toString()?.endsWith("Button") != true || nameOf(n, true) != null) continue
            val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            nodes += n
            boxes += intArrayOf(b.left, b.top, b.right, b.bottom)
        }
        val i = AutoEnter.trailingButton(fb.top, fb.right, fb.bottom, boxes, (8 * resources.displayMetrics.density).toInt())
            ?: return null
        Log.i(TAG, "auto Enter: no button says send; taking the unnamed one ending the field's row ${boundsOf(nodes[i])}")
        return nodes[i] to "(unnamed, end of the field's row)"
    }

    /**
     * A node's name for the send search: description or button text ([AutoEnter.buttonLabel]), else
     * its hint or tooltip, in case a web button keeps its label there.
     */
    private fun nameOf(n: AccessibilityNodeInfo, isButton: Boolean): String? {
        AutoEnter.buttonLabel(n.contentDescription, n.text, isButton)?.let { return it }
        if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.takeIf { isButton && it.isNotBlank() }?.let { return it }
        if (Build.VERSION.SDK_INT >= 28) n.tooltipText?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /**
     * The send button under [top] nearest the field [fb]; and whether [limit] nodes ran out first.
     * [hidden]: only buttons not visible to the user, which may have no place on screen at all.
     */
    private fun sendButtonUnder(
        top: AccessibilityNodeInfo, fb: android.graphics.Rect, reach: Int, limit: Int, hidden: Boolean = false,
    ): Pair<Pair<AccessibilityNodeInfo, String>?, Boolean> {
        var best: AccessibilityNodeInfo? = null
        var bestLabel = ""
        var bestDist = Long.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(top) }
        var seen = 0
        while (queue.isNotEmpty() && seen < limit) {
            val n = queue.removeFirst()
            seen++
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            if (n.isEditable || n.isVisibleToUser == hidden || !n.isEnabled) continue
            val isButton = n.className?.toString()?.endsWith("Button") == true
            val label = nameOf(n, isButton)
            if (!AutoEnter.isSendButton(label, n.viewIdResourceName)) continue
            // A web button need not say it is clickable to take a click (Chrome decides that itself).
            val target = clickableSelfOrParent(n) ?: n.takeIf { isButton } ?: continue
            val b = android.graphics.Rect().also { target.getBoundsInScreen(it) }
            if (!AutoEnter.besideField(b.top, b.bottom, fb.top, fb.bottom, reach, placeless = hidden && b.isEmpty)) continue
            val dx = (b.centerX() - fb.centerX()).toLong()
            val dy = (b.centerY() - fb.centerY()).toLong()
            val d = dx * dx + dy * dy
            if (d < bestDist) { best = target; bestDist = d; bestLabel = label ?: n.viewIdResourceName.orEmpty() }
        }
        return best?.let { it to bestLabel } to queue.isNotEmpty()
    }

    /**
     * For the log when no send button was found: what does sit around the field (class, description,
     * text, id, clickable/enabled, bounds), so the next miss says why without a device to hand.
     */
    private fun buttonsNear(field: AccessibilityNodeInfo): String {
        // Everything visible around the field, unfiltered: a filter that shares the search's
        // assumptions would hide the very node it got wrong.
        var top: AccessibilityNodeInfo = field
        repeat(4) { top = top.parent ?: return@repeat }
        val out = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(top) }
        var seen = 0
        while (queue.isNotEmpty() && seen < SEND_SEARCH_NEAR && out.size < 30) {
            val n = queue.removeFirst()
            seen++
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            if (!n.isVisibleToUser) continue
            val cls = n.className?.toString()?.substringAfterLast('.').orEmpty()
            // Words only for what can be pressed (a button's name is what the search reads). Anything
            // else near the field may be the conversation: its length only, so a pasted log does not
            // carry what was on screen.
            val pressable = !n.isEditable && (n.isClickable || cls.endsWith("Button"))
            fun shown(v: CharSequence?): String? = v?.let { if (pressable) "\"${it.take(20)}\"" else "(${it.length} chars)" }
            out += "$cls desc=${shown(n.contentDescription)} " +
                "text=${if (n.isEditable) "(field)" else shown(n.text)} " +
                "id=${n.viewIdResourceName} click=${n.isClickable} on=${n.isEnabled} ${boundsOf(n)}" +
                (if (Build.VERSION.SDK_INT >= 30) " hint=${shown(n.hintText)} tip=${shown(n.tooltipText)} state=${n.stateDescription?.take(20)}" else "")
        }
        return if (out.isEmpty()) "nothing" else out.joinToString(" | ")
    }

    private fun boundsOf(n: AccessibilityNodeInfo): String =
        android.graphics.Rect().also { n.getBoundsInScreen(it) }.toShortString()

    /** [n] if clickable, else its nearest clickable ancestor (an icon inside a button), up to 3 up. */
    private fun clickableSelfOrParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(4) {
            val c = cur ?: return null
            if (c.isClickable) return c
            cur = c.parent
        }
        return null
    }

    /**
     * Enter through the input connection (Android 13+): the field's editor action when it names
     * one (send, search, go, done), otherwise an Enter key, which is what terminals want.
     */
    private fun enterThroughInputConnection(pkg: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val im = inputMethod ?: return false
            if (!im.currentInputStarted) return false
            val info = im.currentInputEditorInfo
            if (info?.packageName != null && info.packageName != pkg) return false
            val ic = im.currentInputConnection ?: return false
            val action = AutoEnter.editorActionFor(info?.imeOptions ?: 0)
            if (action != null) {
                ic.performEditorAction(action)
                Log.i(TAG, "auto Enter: editor action $action through the input connection")
            } else {
                val now = SystemClock.uptimeMillis()
                val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
                Log.i(TAG, "auto Enter: Enter key through the input connection")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "auto Enter through the input connection failed", e)
            false
        }
    }

    /** Remember why the last dictation ended on the clipboard, for Settings → Troubleshooting. */
    private fun recordFallback(reason: String) {
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_FALLBACK, "$stamp · $reason").apply()
        Log.w(TAG, "clipboard fallback: $reason")
        lastAttempt = null
    }

    /**
     * Commit [text] the way a keyboard does, through the input connection of whatever is taking
     * input (Android 13+, `flagInputMethodEditor`). For editors that expose no focused editable
     * node: in Samsung Notes the focus sits on the ScrollView around the text, the keyboard is up,
     * and every dictation went to the clipboard (2026-11-11). False when nothing is taking input.
     */
    private fun commitToInputConnection(text: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val im = inputMethod ?: return false
            if (!im.currentInputStarted) return false
            val ic = im.currentInputConnection ?: return false
            // One character either side of the cursor decides the spacing, as in InsertSplice.
            val around = ic.getSurroundingText(1, 1, 0)
            val chars = around?.text?.toString().orEmpty()
            val a = around?.selectionStart?.coerceIn(0, chars.length) ?: 0
            val b = around?.selectionEnd?.coerceIn(a, chars.length) ?: a
            ic.commitText(InsertSplice.padded(chars.substring(0, a), chars.substring(b), text), 1, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "input connection insert failed", e)
            false
        }
    }

    /** Fallback insert: stage on the clipboard and paste. Honors cursor position, but the
     *  text is necessarily left on the clipboard (only used when [insertAtCursor] can't). */
    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        setClipboard(text)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        // Don't leave the dictation sitting on the clipboard after it was pasted. Whatever the
        // user had copied before is already gone (a background app can't read it to restore),
        // but at least the next paste won't surprise them with their last dictation.
        if (ok && Build.VERSION.SDK_INT >= 28) {
            // Own handler: attemptInsert clears `main`'s queue right after a successful insert.
            clipHandler.postDelayed({
                runCatching {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    // A background service usually can't read the clip (null description); only
                    // skip when we can see the user copied something else in the meantime.
                    val label = cb.primaryClipDescription?.label
                    if (label == null || label == "rewrite") cb.clearPrimaryClip()
                }
            }, 800)
        }
        return ok
    }

    /**
     * The focused editable under [root]: findFocus, and when that gives nothing editable, a scan
     * for a node that says it is focused and editable. Back in ChatGPT (Compose) from another app,
     * the composer had focus and the keyboard was up, yet findFocus found no editable and every
     * dictation went to the clipboard, while uiautomator's per-node `focused` showed the field.
     */
    private fun focusedEditableIn(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && f.isEditable) return f
        val got = f?.let { "${it.className?.toString()?.substringAfterLast('.')}" } ?: "null"
        @Suppress("DEPRECATION") f?.recycle()
        val found = scanFocusedEditable(root)
        if (found != null) Log.i(TAG, "findFocus gave $got in ${root.packageName}; tree scan found the focused field")
        return found
    }

    private fun scanFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until root.childCount) root.getChild(i)?.let { queue.add(it) }
        var seen = 0
        while (queue.isNotEmpty() && seen < 3000) {
            val n = queue.removeFirst()
            seen++
            if (n.isFocused && n.isEditable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /** Focused editable node in the active window, only if it's NOT our own app. */
    private fun findHostFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName == packageName) return null // our sheet is still up
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        @Suppress("DEPRECATION") focused?.recycle()
        // The "active" window isn't always the one holding input focus (a browser's toolbar or
        // an in-app overlay can be active while the page's field has focus). Same scan as
        // evaluateFieldFocus: any non-IME window of another app with a focused editable.
        val wins = try { windows } catch (_: Exception) { null } ?: return null
        for (w in wins) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val r = w.root ?: continue
            if (r.packageName == packageName) continue
            focusedEditableIn(r)?.let { return it }
        }
        return null
    }

    /**
     * [lastEditable], if it is still on screen in the window that is now in front and that
     * window is not ours. Only consulted once our sheet has gone; the window-id check keeps a
     * field from a screen the user has since left from receiving the text.
     */
    private fun rememberedEditable(): AccessibilityNodeInfo? {
        val node = lastEditable ?: return null
        val root = rootInActiveWindow ?: return null
        if (root.packageName == packageName) return null // our sheet is still up
        if (!node.refresh() || !node.isEditable || !node.isVisibleToUser) return null
        if (node.windowId != root.windowId) return null
        // A copy: attemptInsert recycles what it gets. obtain() rather than the copy
        // constructor, which is API 33+ (minSdk is 24).
        @Suppress("DEPRECATION")
        return AccessibilityNodeInfo.obtain(node)
    }

    private fun setClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("rewrite", text))
    }

    /** Light confirmation buzz when text lands in the field. */
    private fun vibrateTick() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(20)
            }
        } catch (_: Exception) {}
    }
}
