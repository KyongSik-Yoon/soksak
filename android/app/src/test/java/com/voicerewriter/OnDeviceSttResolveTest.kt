package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OnDeviceStt.resolveModel] is what keeps a stale stored model id (a fresh install's blank
 * value, or an engine a previous build offered and this one dropped) from becoming a dictation
 * that cannot run. The routing lives in one pure function so every call site agrees.
 */
class OnDeviceSttResolveTest {

    @Test fun `every whisper model is left alone`() {
        for (m in WhisperModelManager.MODELS) {
            assertEquals(m.id, OnDeviceStt.resolveModel(m.id))
        }
    }

    @Test fun `a blank id falls back to the local default`() {
        assertEquals(WhisperModelManager.DEFAULT_MODEL, OnDeviceStt.resolveModel(""))
    }

    @Test fun `retired engines fall back to the local default`() {
        for (old in listOf("parakeet", "hinglish", "no-such-model")) {
            assertEquals(WhisperModelManager.DEFAULT_MODEL, OnDeviceStt.resolveModel(old))
        }
    }

    @Test fun `the local default is a real whisper model`() {
        val id = WhisperModelManager.DEFAULT_MODEL
        assertEquals(id, WhisperModelManager.model(id).id)
    }

    @Test fun `a selected model that is on disk runs`() {
        val turbo = WhisperModelManager.TURBO_ACFT_Q4_MODEL
        assertEquals(turbo, OnDeviceStt.pick(turbo) { true })
    }

    @Test fun `a selected model that is not on disk falls back to the largest downloaded one`() {
        // A backup from another install names that install's model; this one only has Small.
        val small = WhisperModelManager.SMALL_ACFT_MODEL
        assertEquals(small, OnDeviceStt.pick(WhisperModelManager.TURBO_ACFT_Q4_MODEL) { it == small })
    }

    @Test fun `with nothing on disk the selected model is kept`() {
        val turbo = WhisperModelManager.TURBO_ACFT_Q4_MODEL
        assertEquals(turbo, OnDeviceStt.pick(turbo) { false })
    }
}
