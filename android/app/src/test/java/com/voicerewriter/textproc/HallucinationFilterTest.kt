package com.voicerewriter.textproc

import com.voicerewriter.DEFAULT_PROMPT_EXAMPLE
import org.junit.Assert.assertEquals
import org.junit.Test

class HallucinationFilterTest {
    private fun clean(t: String, glossary: String? = null) = HallucinationFilter.clean(t, DEFAULT_PROMPT_EXAMPLE, glossary)

    @Test fun dropsAnEchoOfTheStyleExample() {
        // Small ACFT on a 1s clip, 2026-11-09.
        assertEquals("", clean("정리한 다음 Claude Code로 테스트 돌려줘."))
    }

    @Test fun keepsShortRealWordsThatAlsoAppearInThePrompt() {
        assertEquals("테스트.", clean("테스트."))
    }

    @Test fun keepsSpeechThatOnlyOverlapsThePrompt() {
        val t = "worktree 정리한 다음에 빌드 다시 돌려줘."
        assertEquals(t, clean(t))
    }

    @Test fun dropsAnEchoOfTheGlossary() {
        assertEquals("", clean("용어: Kubernetes, ClickHouse", glossary = "용어: Kubernetes, ClickHouse, NATS"))
    }

    @Test fun dropsStockNewsLine() {
        // Turbo on the same clip.
        assertEquals("", clean("이 시각 세계였습니다."))
        assertEquals("", clean("MBC 뉴스 김철수입니다."))
    }

    @Test fun removesStockSentenceButKeepsTheRest() {
        assertEquals("PR 올려줘.", clean("PR 올려줘. 시청해주셔서 감사합니다."))
    }

    @Test fun leavesUntouchedTextExactlyAsIs() {
        assertEquals("Kotlin 2.1 버전으로 올려줘", clean("Kotlin 2.1 버전으로 올려줘"))
    }

    @Test fun keepsALongSentenceThatMentionsAStockPhrase() {
        val t = "유튜브 영상 끝에 나오는 구독과 좋아요 문구를 인식기에서 걸러내는 기능 만들어줘."
        assertEquals(t, clean(t))
    }
}
