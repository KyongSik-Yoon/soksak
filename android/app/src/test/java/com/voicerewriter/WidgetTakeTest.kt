package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTakeTest {
    private val base = Settings(sttModel = "turbo-acft-npu", quietModel = "small-acft", pcSend = PcLink.OFF, autoStopSeconds = 5.0)

    @Test fun aWidgetTakeGoesToThePcInQuietModeWithItsOwnWait() {
        val s = base.forWidgetTake(2.2)
        assertEquals(PcLink.PC_ONLY, s.pcSend)
        assertTrue(s.quietMode)
        assertEquals(2.2, s.autoStopSeconds, 0.0)
    }

    @Test fun itRunsOnTheModelTheBubbleIsUsing() {
        // Quiet mode off: the bubble's model, not Quiet mode's (which would have to be loaded first).
        assertEquals("turbo-acft-npu", base.forWidgetTake(2.2).forDictation().sttModel)
    }

    @Test fun withQuietModeOnThatIsQuietModesModel() {
        assertEquals("small-acft", base.copy(quietMode = true).forWidgetTake(2.2).forDictation().sttModel)
    }
}
