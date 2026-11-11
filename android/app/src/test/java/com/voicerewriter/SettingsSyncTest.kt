package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSyncTest {
    private val base = Settings(sttModel = "medium-npu", quietModel = "small-acft", pcSend = PcLink.OFF)

    @Test fun nothingMovedMeansNothingToApply() {
        assertEquals(SettingsSync.Changes(), SettingsSync.changedByOthers(base, base))
    }

    @Test fun theBubblesSwitchesAreReported() {
        val after = base.copy(quietMode = true, pcSend = PcLink.BOTH, pcType = true)
        assertEquals(
            SettingsSync.Changes(quietMode = true, pcSend = PcLink.BOTH, pcType = true),
            SettingsSync.changedByOthers(base, after),
        )
    }

    @Test fun aModelPickedOnTheBubbleIsReported() {
        assertEquals(
            SettingsSync.Changes(sttModel = "turbo-acft-q4"),
            SettingsSync.changedByOthers(base, base.copy(sttModel = "turbo-acft-q4")),
        )
        assertEquals(
            SettingsSync.Changes(quietModel = "medium-npu"),
            SettingsSync.changedByOthers(base, base.copy(quietModel = "medium-npu")),
        )
    }

    @Test fun typedFieldsAreLeftToTheScreen() {
        // Saved on every keystroke; applying an older save back would eat what was typed since.
        val after = base.copy(pcHost = "100.64.0.9", promptExample = "x", pcCode = "ABCDE")
        assertEquals(SettingsSync.Changes(), SettingsSync.changedByOthers(base, after))
    }
}
