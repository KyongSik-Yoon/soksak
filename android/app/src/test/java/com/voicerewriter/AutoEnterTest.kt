package com.voicerewriter

import android.view.inputmethod.EditorInfo
import com.voicerewriter.AutoEnter.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEnterTest {
    @Test fun `off by default, and the choices cycle back to off`() {
        assertEquals(0.0, Settings().autoEnterSeconds, 0.0)
        assertEquals(0L, Settings().autoEnterMs())
        var v = 0.0
        val seen = mutableListOf<Double>()
        repeat(AUTO_ENTER_CHOICES.size) { v = nextAutoEnter(v); seen += v }
        assertEquals(listOf(1.0, 2.0, 3.0, 5.0, 0.0), seen)
        // A value from an older build that is not a choice starts over at the first.
        assertEquals(0.0, nextAutoEnter(4.0), 0.0)
    }

    @Test fun `the delay applies to phone inserts only`() {
        val on = Settings(autoEnterSeconds = 2.0)
        assertEquals(2000L, on.autoEnterMs())
        assertEquals(2000L, on.copy(pcSend = PcLink.BOTH, pcHost = "h", pcCode = "c").autoEnterMs())
        // "PC only": the phone gets the text only when the PC failed; nothing is sent from it.
        assertEquals(0L, on.copy(pcSend = PcLink.PC_ONLY, pcHost = "h", pcCode = "c").autoEnterMs())
        // The widget take runs on a locked phone with nothing to cancel on.
        assertEquals(0L, on.forWidgetTake(2.2).autoEnterMs())
        assertEquals(0.0, on.copy(pcSend = PcLink.OFF).forWidgetTake(2.2).autoEnterSeconds, 0.0)
    }

    @Test fun `judging what Enter did`() {
        val said = "테스트 돌려줘."
        assertEquals(Outcome.SENT, AutoEnter.judge(said, "", said))
        // Never there in the first place is not a send.
        assertEquals(Outcome.NOTHING, AutoEnter.judge("다른 글", "", said))
        assertFalse(AutoEnter.stillThere("다른 글", said))
        assertTrue(AutoEnter.stillThere("앞 글 $said", " $said"))
        assertFalse(AutoEnter.stillThere("", "  "))
        assertEquals(Outcome.SENT, AutoEnter.judge("앞 글 $said", "앞 글", said))
        assertEquals(Outcome.NEWLINE, AutoEnter.judge(said, "$said\n", said))
        assertEquals(Outcome.NOTHING, AutoEnter.judge(said, said, said))
        // Spacing the splice added round the text does not hide it.
        assertEquals(Outcome.NOTHING, AutoEnter.judge("a $said", "a $said", " $said "))
    }

    @Test fun `send buttons are found by label or id`() {
        assertTrue(AutoEnter.isSendButton("Send message", null))
        assertTrue(AutoEnter.isSendButton("Send prompt", null))
        assertTrue(AutoEnter.isSendButton("전송", null))
        assertTrue(AutoEnter.isSendButton("메시지 보내기", null))
        assertTrue(AutoEnter.isSendButton(null, "com.example.chat:id/send_button"))
        assertTrue(AutoEnter.isSendButton(null, "com.example:id/btn_send"))
        assertTrue(AutoEnter.isSendButton(null, "com.example:id/send"))
        assertTrue(AutoEnter.isSendButton("Submit", null))
    }

    @Test fun `voice, attachment and look-alike buttons are not send`() {
        assertFalse(AutoEnter.isSendButton("Send voice message", null))
        assertFalse(AutoEnter.isSendButton("음성 메시지 전송", null))
        assertFalse(AutoEnter.isSendButton("Attach file", null))
        assertFalse(AutoEnter.isSendButton(null, "com.example:id/send_audio"))
        assertFalse(AutoEnter.isSendButton("Sender", null))
        assertFalse(AutoEnter.isSendButton(null, "com.example:id/sender_name"))
        assertFalse(AutoEnter.isSendButton("Microphone", null))
        assertFalse(AutoEnter.isSendButton(null, null))
        // A chat message that happens to say send is prose, not a button name.
        assertFalse(AutoEnter.isSendButton("파일 전송 끝나면 알려줘, 그다음에 배포하자", null))
        assertFalse(AutoEnter.isSendButton("Can you send the logs to me after lunch?", null))
    }

    @Test fun `a button's name is its description, else its text`() {
        assertEquals("Send", AutoEnter.buttonLabel("Send", "ignored", isButton = true))
        // An empty description must not hide the name in the text (as a web button may have).
        assertEquals("전송", AutoEnter.buttonLabel("", "전송", isButton = true))
        assertEquals("전송", AutoEnter.buttonLabel(" ", "전송", isButton = true))
        assertTrue(AutoEnter.isSendButton(AutoEnter.buttonLabel("", "전송", isButton = true), null))
        // A message's text is not a label.
        assertNull(AutoEnter.buttonLabel(null, "전송", isButton = false))
        assertNull(AutoEnter.buttonLabel("", "", isButton = true))
    }

    @Test fun `an unnamed button ending the field's row stands in for send`() {
        // Collie's composer from the debug log: the field, then two unnamed buttons only if any.
        val fieldTop = 1777; val fieldRight = 1052; val fieldBottom = 1925
        val send = intArrayOf(1228, 1777, 1372, 1925)
        val toolbar = intArrayOf(900, 1513, 1048, 1665) // the row above
        val left = intArrayOf(0, 1777, 60, 1925) // before the field
        assertEquals(1, AutoEnter.trailingButton(fieldTop, fieldRight, fieldBottom, listOf(toolbar, send, left), 16))
        // Two on the row after the field: the last one.
        val mic = intArrayOf(1068, 1777, 1212, 1925)
        assertEquals(1, AutoEnter.trailingButton(fieldTop, fieldRight, fieldBottom, listOf(mic, send), 16))
        assertNull(AutoEnter.trailingButton(fieldTop, fieldRight, fieldBottom, listOf(toolbar, left), 16))
    }

    @Test fun `a form's go-ahead is the one button on its Cancel's row`() {
        // agent world's new-session dialog from the debug log (2026-11-25): field 1029..1805.
        val more = intArrayOf(76, 733, 1364, 897)
        val cancel = intArrayOf(920, 1809, 1128, 1957)
        val build = intArrayOf(1140, 1809, 1348, 1957)
        val labels = listOf("더 보기 agy-1", "취소", "짓기")
        assertEquals(2, AutoEnter.confirmButton(labels, listOf(more, cancel, build), 1029, 1805, 252))
        // No Cancel, two of them, or a third button on the row: not a form's last row.
        assertNull(AutoEnter.confirmButton(listOf("더 보기", "짓기"), listOf(more, build), 1029, 1805, 252))
        assertNull(AutoEnter.confirmButton(listOf("취소", "취소", "짓기"), listOf(more, cancel, build), 1029, 1805, 252))
        val third = intArrayOf(700, 1809, 908, 1957)
        assertNull(AutoEnter.confirmButton(listOf("미리보기", "취소", "짓기"), listOf(third, cancel, build), 1029, 1805, 252))
        // A Cancel far from the field belongs to something else.
        assertNull(AutoEnter.confirmButton(listOf("Cancel", "OK"), listOf(cancel, build), 200, 400, 252))
        assertEquals(1, AutoEnter.confirmButton(listOf("Cancel", "Create"), listOf(cancel, build), 1029, 1805, 252))
    }

    @Test fun `a button that deletes, leaves or pays is never the go-ahead`() {
        val cancel = intArrayOf(920, 1809, 1128, 1957)
        val other = intArrayOf(1140, 1809, 1348, 1957)
        for (l in listOf("삭제", "Delete", "Remove project", "나가기", "Discard", "결제하기", "Log out", "음성 입력")) {
            assertNull(l, AutoEnter.confirmButton(listOf("취소", l), listOf(cancel, other), 1029, 1805, 252))
        }
    }

    @Test fun `a send button must sit beside the field`() {
        // Field at y 1800..1900, reach 200: its row and the toolbar under it, not the chat above.
        assertTrue(AutoEnter.besideField(1820, 1880, 1800, 1900, 200))
        assertTrue(AutoEnter.besideField(1950, 2050, 1800, 1900, 200))
        assertFalse(AutoEnter.besideField(1400, 1500, 1800, 1900, 200))
    }

    @Test fun `an off-screen button with no bounds counts as beside the field`() {
        assertTrue(AutoEnter.besideField(0, 0, 1800, 1900, 200, placeless = true))
        assertFalse(AutoEnter.besideField(0, 0, 1800, 1900, 200))
    }

    @Test fun `editor actions through the input connection`() {
        assertEquals(EditorInfo.IME_ACTION_SEND, AutoEnter.editorActionFor(EditorInfo.IME_ACTION_SEND))
        assertEquals(EditorInfo.IME_ACTION_SEARCH, AutoEnter.editorActionFor(EditorInfo.IME_ACTION_SEARCH))
        // Multi-line editors ask for no Enter action, terminals name none: a real Enter key.
        assertNull(AutoEnter.editorActionFor(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertNull(AutoEnter.editorActionFor(EditorInfo.IME_ACTION_UNSPECIFIED))
        assertNull(AutoEnter.editorActionFor(EditorInfo.IME_ACTION_NONE))
        assertNull(AutoEnter.editorActionFor(EditorInfo.IME_ACTION_NEXT))
    }

    @Test fun `the field on screen only with the bubble always shown, and never for PC only`() {
        assertFalse(Settings().fieldOnScreen()) // the bubble shows only on fields by default
        val always = Settings(bubbleOnlyOnFields = false)
        assertTrue(always.fieldOnScreen())
        assertTrue(always.copy(pcSend = PcLink.BOTH, pcHost = "h", pcCode = "c").fieldOnScreen())
        assertFalse(always.copy(pcSend = PcLink.PC_ONLY, pcHost = "h", pcCode = "c").fieldOnScreen())
        // The widget take on a locked phone: the PC fallback must not land in some field on screen.
        assertFalse(always.forWidgetTake(2.2).fieldOnScreen())
        assertFalse(always.copy(pcHost = "h", pcCode = "c").forWidgetTake(2.2).fieldOnScreen())
    }

    @Test fun `which field on screen takes the text`() {
        assertNull(AutoEnter.pickField(0) { true })
        // One field: it, and Enter may follow. Its send button is not even asked about.
        val one = AutoEnter.pickField(1) { error("not asked") }!!
        assertEquals(0, one.index)
        assertTrue(one.enter)
        // Several: the only one beside a send button, text only.
        val composer = AutoEnter.pickField(3) { it == 2 }!!
        assertEquals(2, composer.index)
        assertFalse(composer.enter)
        // None or more than one beside a send button: do not guess.
        assertNull(AutoEnter.pickField(2) { false })
        assertNull(AutoEnter.pickField(3) { it != 0 })
    }

    /** Chrome reported 9,994 chars for a field whose cursor sat at 24,496: the text cannot be looked for. */
    @Test fun `a field reporting part of its text is judged by its cursor`() {
        assertEquals(AutoEnter.Outcome.NEWLINE, AutoEnter.judgeByCursor(24496, 24497, stillPartial = true))
        assertEquals(AutoEnter.Outcome.NOTHING, AutoEnter.judgeByCursor(24496, 24496, stillPartial = true))
        assertEquals(AutoEnter.Outcome.SENT, AutoEnter.judgeByCursor(24496, 0, stillPartial = false))
    }
}
