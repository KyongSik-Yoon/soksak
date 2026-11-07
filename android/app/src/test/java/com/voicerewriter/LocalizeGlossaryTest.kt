package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizeGlossaryTest {

    @Test
    fun koreanGetsKoreanFrame() {
        assertEquals("용어: GitLab, 워크트리, NATS.", LocalWhisperStt.localizeGlossary("Glossary: GitLab, 워크트리, NATS.", "ko"))
    }

    @Test
    fun otherLanguagesUnchanged() {
        assertEquals("Glossary: GitLab.", LocalWhisperStt.localizeGlossary("Glossary: GitLab.", "en"))
        assertEquals("Glossary: GitLab.", LocalWhisperStt.localizeGlossary("Glossary: GitLab.", "ja"))
    }

    @Test
    fun unexpectedShapeLeftAlone() {
        assertEquals("GitLab, MR", LocalWhisperStt.localizeGlossary("GitLab, MR", "ko"))
    }
}
