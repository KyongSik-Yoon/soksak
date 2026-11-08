package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaCleanTest {
    @Test
    fun moonshineWordMarkersBecomeSpaces() {
        assertEquals("조국이 당신을 위해", LocalSherpaStt.clean("▁조국이 당신을▁위해"))
    }

    @Test
    fun senseVoiceTagsAreDropped() {
        assertEquals("조금만 생각을 하면서", LocalSherpaStt.clean("<|ko|><|NEUTRAL|>조금만 생각을  하면서 "))
    }
}
