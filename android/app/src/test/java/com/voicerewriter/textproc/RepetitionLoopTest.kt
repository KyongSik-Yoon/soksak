package com.voicerewriter.textproc

import org.junit.Assert.assertEquals
import org.junit.Test

class RepetitionLoopTest {
    @Test fun cutsTheLoopSeenOnThePhone() {
        val loop = "플로팅 버튼이 사라질 때 이렇게, " + "이제, ".repeat(40) + "이"
        assertEquals("플로팅 버튼이 사라질 때 이렇게, 이제,", RepetitionLoop.cut(loop))
    }

    @Test fun cutsAPhraseLoop() {
        assertEquals("그래서 테스트를 돌려 줘.", RepetitionLoop.cut("그래서 " + "테스트를 돌려 줘. ".repeat(6).trim()))
    }

    @Test fun cutsOneSyllableRunTogether() {
        assertEquals("그게 고", RepetitionLoop.cut("그게 " + "고".repeat(30)))
    }

    @Test fun leavesSpeechAlone() {
        for (t in listOf(
            "아니 아니 아니 그게 아니라",
            "네 네 네 네",
            "이제 이제 이제 이제 이제 좀 그만하자", // not at the end: said, not a token-limit loop
            "빌드하고 테스트하고 빌드하고 테스트하고 올려 줘.",
            "ㅋㅋㅋㅋ",
            "",
        )) assertEquals(t, RepetitionLoop.cut(t))
    }
}
