package com.voicerewriter.textproc

import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanVocabTest {

    private val vocab = listOf(
        VocabEntry("worktree", aliases = listOf("워크트리")),
        VocabEntry("commit", aliases = listOf("커밋")),
        VocabEntry("merge request", aliases = listOf("머지 리퀘스트")),
    )

    private fun fix(s: String) = VocabCorrector.correct(s, vocab)

    @Test
    fun bareAliasIsReplaced() {
        assertEquals("worktree 정리해줘", fix("워크트리 정리해줘"))
    }

    @Test
    fun particleIsKept() {
        assertEquals("worktree를 만들고 worktree에서 작업해", fix("워크트리를 만들고 워크트리에서 작업해"))
    }

    @Test
    fun hadaEndingIsKept() {
        assertEquals("commit해줘", fix("커밋해줘"))
    }

    @Test
    fun multiTokenAliasWithParticle() {
        assertEquals("merge request를 올려", fix("머지 리퀘스트를 올려"))
    }

    @Test
    fun unrelatedSuffixIsLeftAlone() {
        // "스" is not a particle, so this is a different word, not "워크트리" + particle.
        assertEquals("워크트리스", fix("워크트리스"))
    }

    @Test
    fun sameLengthKoreanWordsAreNotFuzzyMatched() {
        val v = listOf(VocabEntry("Jennifer", aliases = listOf("제니퍼스")))
        assertEquals("제니퍼다", VocabCorrector.correct("제니퍼다", v))
    }
}
