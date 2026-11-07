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
        assertEquals(Defaults.STT_PROVIDERS.getValue("local").defaultModel, OnDeviceStt.resolveModel(""))
    }

    @Test fun `retired engines fall back to the local default`() {
        for (old in listOf("parakeet", "hinglish", "no-such-model")) {
            assertEquals(WhisperModelManager.DEFAULT_MODEL, OnDeviceStt.resolveModel(old))
        }
    }

    @Test fun `the local default is a real whisper model`() {
        val id = Defaults.STT_PROVIDERS.getValue("local").defaultModel
        assertEquals(id, WhisperModelManager.model(id).id)
    }
}
