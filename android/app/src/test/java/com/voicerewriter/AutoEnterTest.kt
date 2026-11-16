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
}
