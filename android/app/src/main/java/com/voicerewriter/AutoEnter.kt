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

    /** What pressing Enter did, from the field's text [before] and [after] it. */
    fun judge(before: String, after: String, inserted: String): Outcome {
        val said = inserted.trim()
        if (said.isNotEmpty() && !after.contains(said)) return Outcome.SENT
        if (after.count { it == '\n' } > before.count { it == '\n' }) return Outcome.NEWLINE
        return Outcome.NOTHING
    }

    private val SEND_WORDS = Regex("(?i)(\\bsend\\b|\\bsubmit\\b|보내기|전송|제출)")
    private val SEND_ID = Regex("(?i)(^|[/_.:])(send|submit)([_.]|button|btn|$)")
    private val NOT_SEND = Regex("(?i)(voice|audio|\\bmic|record|attach|sticker|emoji|camera|gif|음성|녹음|마이크|첨부|이모티콘|사진|카메라)")

    /**
     * Whether a button's [label] (content description or text) or [viewId] (resource name) says
     * it sends the message. Voice-message and attachment buttons say "send" too and are excluded.
     */
    fun isSendButton(label: String?, viewId: String?): Boolean {
        val l = label.orEmpty()
        val id = viewId.orEmpty()
        if (NOT_SEND.containsMatchIn(l) || NOT_SEND.containsMatchIn(id)) return false
        return SEND_WORDS.containsMatchIn(l) || SEND_ID.containsMatchIn(id.substringAfter(":id/", id))
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
