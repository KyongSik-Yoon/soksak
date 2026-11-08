package com.voicerewriter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineMissTest {
    @Test fun placeholderIsAMiss() {
        assertTrue(OnDeviceStt.isMoonshineMiss("audiotext", "ko"))
        assertTrue(OnDeviceStt.isMoonshineMiss(" Audiotext ", "en"))
    }

    @Test fun latinOnlyIsAMissForKorean() {
        assertTrue(OnDeviceStt.isMoonshineMiss("audio text", "ko"))
        assertFalse(OnDeviceStt.isMoonshineMiss("audio text", "en"))
    }

    @Test fun koreanTextAndSilenceAreNot() {
        assertFalse(OnDeviceStt.isMoonshineMiss("아직까지는 문샷이 제일 이해를 잘 하는 것 같다.", "ko"))
        assertFalse(OnDeviceStt.isMoonshineMiss("GitLab에 MR 올려줘", "ko"))
        assertFalse(OnDeviceStt.isMoonshineMiss("", "ko"))
    }
}
