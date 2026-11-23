package com.voicerewriter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardEdgeTest {

    @Test
    fun `the keyboard coming up is reported once`() {
        val edge = KeyboardEdge()
        assertFalse(edge.opened(false))
        assertTrue(edge.opened(true))
        assertFalse(edge.opened(true))
    }

    @Test
    fun `it is reported again after the keyboard went away`() {
        val edge = KeyboardEdge()
        assertTrue(edge.opened(true))
        assertFalse(edge.opened(false))
        assertTrue(edge.opened(true))
    }

    @Test
    fun `an unknown report does not make an open keyboard look newly opened`() {
        val edge = KeyboardEdge()
        assertTrue(edge.opened(true))
        assertFalse(edge.opened(null))
        assertFalse(edge.opened(true))
    }
}
