package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bubble shows the active model as a 1-2 letter tab, so the letters must tell them apart. */
class ModelBadgeTest {
    @Test
    fun badgesAreShortAndUnique() {
        val badges = WhisperModelManager.MODELS.map { it.badge }
        badges.forEach { assertTrue("badge '$it'", it.isNotBlank() && it.length <= 2) }
        assertEquals(badges.size, badges.toSet().size)
    }
}
