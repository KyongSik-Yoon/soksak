package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelTest {
    private val base = Settings(sttModel = "medium-npu", quietModel = "small-acft", pcSend = PcLink.OFF, autoStopSeconds = 5.0)

    @Test fun aWidgetTakeGoesToThePcInQuietModeWithItsOwnWait() {
        val s = base.forWidgetTake(2.2)
        assertEquals(PcLink.PC_ONLY, s.pcSend)
        assertTrue(s.quietMode)
        assertEquals(2.2, s.autoStopSeconds, 0.0)
    }

    @Test fun withoutAWidgetModelItRunsOnQuietModesModel() {
        assertEquals("small-acft", base.forWidgetTake(2.2).forDictation().sttModel)
    }

    @Test fun theWidgetModelWinsOverQuietModes() {
        val s = base.copy(widgetModel = "turbo-acft-npu").forWidgetTake(2.2)
        assertEquals("turbo-acft-npu", s.forDictation().sttModel)
    }

    @Test fun theRowStepsThroughBlankAndTheDownloadedModels() {
        val ready = listOf("small-acft", "turbo-acft-npu")
        assertEquals("small-acft", WidgetModelChoice.next("", ready))
        assertEquals("turbo-acft-npu", WidgetModelChoice.next("small-acft", ready))
        assertEquals("", WidgetModelChoice.next("turbo-acft-npu", ready))
        assertEquals("", WidgetModelChoice.next("a-model-since-deleted", ready))
    }
}
