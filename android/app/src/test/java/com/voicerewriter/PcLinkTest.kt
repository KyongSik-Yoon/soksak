package com.voicerewriter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of [PcLink] that decide what is sent and where; the daemon's side is `daemon/`. */
class PcLinkTest {
    private val paired = Settings(pcHost = "100.68.1.2", pcCode = "abcde-fgh23")

    @Test fun `nothing is sent by default or without an address and a code`() {
        assertEquals(PcLink.OFF, PcLink.mode(Settings()))
        assertEquals(PcLink.OFF, PcLink.mode(Settings(pcSend = PcLink.BOTH)))
        assertEquals(PcLink.OFF, PcLink.mode(Settings(pcSend = PcLink.BOTH, pcHost = "100.68.1.2")))
        assertEquals(PcLink.OFF, PcLink.mode(Settings(pcSend = PcLink.PC_ONLY, pcCode = "ABCDEFGH23")))
        assertEquals(PcLink.OFF, PcLink.mode(Settings(pcSend = PcLink.BOTH, pcHost = "h", pcCode = " - ")))
        assertEquals(PcLink.OFF, PcLink.mode(paired))
        assertFalse(PcLink.configured(Settings()))
    }

    @Test fun `a paired phone sends in the mode it was set to`() {
        assertTrue(PcLink.configured(paired))
        assertEquals(PcLink.BOTH, PcLink.mode(paired.copy(pcSend = PcLink.BOTH)))
        assertEquals(PcLink.PC_ONLY, PcLink.mode(paired.copy(pcSend = PcLink.PC_ONLY)))
        assertEquals(PcLink.OFF, PcLink.mode(paired.copy(pcSend = "something else")))
    }

    @Test fun `the menu cycles through every mode`() {
        assertEquals(PcLink.BOTH, PcLink.next(PcLink.OFF))
        assertEquals(PcLink.PC_ONLY, PcLink.next(PcLink.BOTH))
        assertEquals(PcLink.OFF, PcLink.next(PcLink.PC_ONLY))
    }

    @Test fun `the request carries the code and the byte length`() {
        val text = "GitLab에 MR 올려줘."
        val expected = "SOKSAK/1 ABCDEFGH23 ${text.toByteArray().size}\n$text".toByteArray()
        assertArrayEquals(expected, PcLink.request("abcde-fgh23", text))
        assertArrayEquals("SOKSAK/1 ABCDEFGH23 0\n".toByteArray(), PcLink.request(" ABCDE FGH23 ", ""))
    }

    @Test fun `addresses with and without a port`() {
        assertEquals("100.68.1.2" to PcLink.DEFAULT_PORT, PcLink.address(" 100.68.1.2 "))
        assertEquals("100.68.1.2" to 5000, PcLink.address("100.68.1.2:5000"))
        assertEquals("my-pc" to PcLink.DEFAULT_PORT, PcLink.address("my-pc"))
        assertEquals("fd7a:115c:a1e0::1" to PcLink.DEFAULT_PORT, PcLink.address("fd7a:115c:a1e0::1"))
        assertEquals("fd7a:115c:a1e0::1" to 5000, PcLink.address("[fd7a:115c:a1e0::1]:5000"))
        assertNull(PcLink.address(""))
        assertNull(PcLink.address("100.68.1.2:port"))
        assertNull(PcLink.address("100.68.1.2:0"))
        assertNull(PcLink.address("100.68.1.2:70000"))
        assertNull(PcLink.address("my pc"))
        assertNull(PcLink.address(":5000"))
    }

    @Test fun `replies from the daemon`() {
        assertNull(PcLink.replyError("OK"))
        assertEquals("PC refused the pairing code", PcLink.replyError("ERR code"))
        assertEquals("PC could not copy the text", PcLink.replyError("ERR wl-copy"))
        assertEquals("PC not reachable", PcLink.replyError(null))
    }

    /** A stand-in daemon: takes one request, answers [reply], and hands back what it received. */
    private fun fakeDaemon(reply: String): Pair<Int, java.util.concurrent.Future<String>> {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val got = java.util.concurrent.Executors.newSingleThreadExecutor().submit<String> {
            server.use { it.accept().use { so ->
                val input = so.getInputStream()
                val header = StringBuilder()
                while (true) { val b = input.read(); if (b < 0 || b == '\n'.code) break; header.append(b.toChar()) }
                val body = input.readNBytes(header.toString().substringAfterLast(' ').toInt())
                so.getOutputStream().apply { write(reply.toByteArray()); flush() }
                header.toString() + "\n" + String(body)
            } }
        }
        return server.localPort to got
    }

    @Test fun `send delivers the text over a socket and reads the answer`() {
        val (port, got) = fakeDaemon("OK\n")
        val err = kotlinx.coroutines.runBlocking { PcLink.send("127.0.0.1:$port", "abcde-fgh23", "테스트 text") }
        assertNull(err)
        assertEquals("SOKSAK/1 ABCDEFGH23 ${"테스트 text".toByteArray().size}\n테스트 text", got.get())
    }

    @Test fun `a refused code comes back as an error`() {
        val (port, _) = fakeDaemon("ERR code\n")
        val err = kotlinx.coroutines.runBlocking { PcLink.send("127.0.0.1:$port", "WRONG", "x") }
        assertEquals("PC refused the pairing code", err)
    }

    /** Against a real soksak-daemon: `SOKSAK_TEST_DAEMON=host:port SOKSAK_TEST_CODE=… ./gradlew …`. */
    @Test fun `send reaches a real daemon when one is given`() {
        val addr = System.getenv("SOKSAK_TEST_DAEMON")
        org.junit.Assume.assumeTrue(addr != null)
        val err = kotlinx.coroutines.runBlocking { PcLink.send(addr!!, System.getenv("SOKSAK_TEST_CODE").orEmpty(), "앱에서 보낸 글 — from PcLink.kt") }
        assertNull(err)
    }

    @Test fun `every message shown has a Korean translation`() {
        val shown = PcLink.MODES.map { PcLink.label(it) } + listOf(
            "PC not reachable", "PC refused the pairing code", "PC could not copy the text", "PC address is not valid",
        )
        for (s in shown) assertTrue(s, I18n.KO.containsKey(s))
    }
}
