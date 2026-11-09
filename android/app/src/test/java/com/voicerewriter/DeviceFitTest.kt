package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFitTest {

    private val mib = 1024L * 1024L

    @Test
    fun `a phone with room gets Small ACFT and no warning`() {
        val p = DeviceFit.planFor(8 * 1024 * mib)
        assertEquals(WhisperModelManager.SMALL_ACFT_MODEL, p.sttModel)
        assertEquals(0, p.storageShortMb)
        assertNull(DeviceFit.explain(p))
    }

    @Test
    fun `a nearly full phone is told how much it is short by, before the download starts`() {
        val p = DeviceFit.planFor(100 * mib)
        assertEquals(514, p.storageShortMb) // 264MB of weights + 350MB headroom - 100MB free
        assertTrue(DeviceFit.explain(p)!!.contains("514MB"))
    }

    @Test
    fun `the plan names a model the registry knows`() {
        val p = DeviceFit.planFor(0)
        assertEquals(p.sttModel, OnDeviceStt.resolveModel(p.sttModel))
        assertNotNull(DeviceFit.explain(p))
    }
}
