package com.voicerewriter

import android.view.inputmethod.EditorInfo

/**
 * Auto Enter ([Settings.autoEnterSeconds]): after a dictation is inserted on the phone the bubble
 * counts down, and unless the user taps it or types, [OpenWisprAccessibilityService] presses
 * Enter in the same field. The decisions that need no device live here.
 *
 * "Enter" means "send what I said": the field's IME action first (search boxes, single-line
 * chats), and when that leaves the text sitting in the field (multi-line composers such as chat
 * apps usually ignore it or add a newline) the send button next to the field.
 */
object AutoEnter {
    /**
     * Text changes in the target app this soon after arming are our own insert landing (SET_TEXT,
     * the cursor move, the refocus click), not the user typing, and must not cancel.
     */
    const val TYPING_GRACE_MS = 700L

    /** How long after the IME action to look at the field again to see whether it was sent. */
    const val VERIFY_MS = 450L

    enum class Outcome {
        /** The dictated text left the field: sent, or the screen moved on. */
        SENT,

        /** The field gained a line break: the app took Enter as a newline. */
        NEWLINE,

        /** Nothing changed. */
        NOTHING,
    }

    /** Whether the dictated text is still in the field, so Enter would send it and not something else. */
    fun stillThere(fieldText: String, inserted: String): Boolean {
        val said = inserted.trim()
        return said.isNotEmpty() && fieldText.contains(said)
    }

    /** What pressing Enter did, from the field's text [before] and [after] it. */
    fun judge(before: String, after: String, inserted: String): Outcome {
        if (stillThere(before, inserted) && !stillThere(after, inserted)) return Outcome.SENT
        if (after.count { it == '\n' } > before.count { it == '\n' }) return Outcome.NEWLINE
        return Outcome.NOTHING
    }

    private val SEND_WORDS = Regex("(?i)(\\bsend\\b|\\bsubmit\\b|보내기|전송|제출)")
    private val SEND_ID = Regex("(?i)(^|[/_.:])(send|submit)([_.]|button|btn|$)")
    private val NOT_SEND = Regex("(?i)(voice|audio|\\bmic|record|attach|sticker|emoji|camera|gif|음성|녹음|마이크|첨부|이모티콘|사진|카메라)")

    /**
     * A node's name for [isSendButton]: its content description, or on a button its text. A blank
     * description does not count, so an empty one cannot hide a name in the text (suspected for
     * Collie's "전송" on 2026-11-17, not confirmed). Text only on a button: a message's text is not a label.
     */
    fun buttonLabel(description: CharSequence?, text: CharSequence?, isButton: Boolean): String? =
        description?.toString()?.takeIf { it.isNotBlank() }
            ?: text?.toString()?.takeIf { isButton && it.isNotBlank() }

    /**
     * When no button says "send": among unnamed buttons, the one ending the field's row — its centre
     * within the field's height, starting at or after the field's end ([slack] px of overlap) — and the
     * last of them. Chrome gives a toggle button (`aria-pressed`) neither description nor text, so
     * Collie's Send, `aria-label="전송"`, reached us nameless at the end of the composer row
     * (debug log, 2026-11-17). [boxes] are left, top, right, bottom; returns an index or null.
     */
    fun trailingButton(fieldTop: Int, fieldRight: Int, fieldBottom: Int, boxes: List<IntArray>, slack: Int): Int? {
        var best: Int? = null
        boxes.forEachIndexed { i, b ->
            val cy = (b[1] + b[3]) / 2
            if (cy < fieldTop || cy > fieldBottom || b[0] < fieldRight - slack) return@forEachIndexed
            if (best == null || b[2] > boxes[best!!][2]) best = i
        }
        return best
    }

    /** Longer than this is a sentence (a chat message saying "전송"), not a button's name. */
    const val MAX_LABEL = 20

    /**
     * Whether a button's [label] (content description, or the text of a button) or [viewId]
     * (resource name) says it sends the message. Voice-message and attachment buttons say "send"
     * too and are excluded, and so is anything long enough to be prose.
     */
    fun isSendButton(label: String?, viewId: String?): Boolean {
        val l = label.orEmpty().trim().takeIf { it.length <= MAX_LABEL }.orEmpty()
        val id = viewId.orEmpty()
        if (NOT_SEND.containsMatchIn(l) || NOT_SEND.containsMatchIn(id)) return false
        return SEND_WORDS.containsMatchIn(l) || SEND_ID.containsMatchIn(id.substringAfter(":id/", id))
    }

    /**
     * Whether a button with vertical extent [top]..[bottom] sits beside a field spanning
     * [fieldTop]..[fieldBottom]: on its row or the toolbar row just under or over it, within
     * [reach] px. Keeps a message in the conversation above from passing for the send button.
     * [placeless]: a button off screen that reports no bounds, which only the search inside the
     * field's own composer looks at — taken as beside it.
     */
    fun besideField(top: Int, bottom: Int, fieldTop: Int, fieldBottom: Int, reach: Int, placeless: Boolean = false): Boolean {
        if (placeless) return true
        val cy = (top + bottom) / 2
        return cy >= fieldTop - reach && cy <= fieldBottom + reach
    }

    /** The field on screen to write into when none is selected, and whether Enter may follow. */
    class FieldPick(val index: Int, val enter: Boolean)

    /**
     * With the bubble always shown and the keyboard down, a dictation goes into the text field on
     * screen. One field: that one, and Auto Enter may send it. Several: the only one with a send
     * button beside it (a chat composer under a search box), text only — the user did not pick it,
     * and sending to the wrong field cannot be taken back. Still ambiguous: none (clipboard).
     * [hasSend] is asked only when there are several.
     */
    fun pickField(count: Int, hasSend: (Int) -> Boolean): FieldPick? {
        if (count <= 0) return null
        if (count == 1) return FieldPick(0, enter = true)
        val withSend = (0 until count).filter(hasSend)
        return if (withSend.size == 1) FieldPick(withSend[0], enter = false) else null
    }

    /**
     * The editor action to perform through the input connection for a field with [imeOptions],
     * or null to send an Enter key instead (terminals, multi-line editors, fields asking for no
     * Enter action).
     */
    fun editorActionFor(imeOptions: Int): Int? {
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (val a = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_DONE -> a
            else -> null
        }
    }
}
