package com.voicerewriter

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sends a finished dictation to a computer running `soksak-daemon` (the `daemon/` folder of
 * this repository), which puts it on that computer's clipboard.
 *
 * Off unless the user set an address and a pairing code and picked a mode; then one short TCP
 * request per dictation, straight to that computer. No relay and no account. The connection is
 * not encrypted by us: the daemon only answers Tailscale peers, and Tailscale is WireGuard.
 *
 * A raw socket rather than OkHttp: the request is one line and the text, and Android refuses
 * cleartext HTTP to an address like 100.x by default, which no unit test would have shown.
 *
 * Wire format (see `daemon/src/lib.rs`): `SOKSAK/1 <code> <byte length>\n<text>`, answered
 * with `OK` or `ERR <reason>`. A length of 0 only checks the code.
 */
object PcLink {
    const val OFF = "off"
    /** Insert on the phone as usual and send a copy to the PC. */
    const val BOTH = "both"
    /** Send to the PC; insert on the phone only if that fails. */
    const val PC_ONLY = "pc"
    val MODES = listOf(OFF, BOTH, PC_ONLY)

    const val DEFAULT_PORT = 48620
    private const val TAG = "PcLink"
    private const val CONNECT_MS = 1500
    private const val REPLY_MS = 3000
    /** Over everything, name lookup included: a PC that is off must not hold a dictation. */
    private const val SEND_MS = 5000L
    /** How long the phone's own "inserted" confirmation gets before the bubble says the PC failed. */
    private const val AFTER_INSERT_MS = 1700L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    /** The mode in effect: [OFF] until both the address and the code are filled in. */
    fun mode(s: Settings): String =
        if (s.pcSend in MODES && s.pcHost.isNotBlank() && normalizeCode(s.pcCode).isNotEmpty()) s.pcSend else OFF

    /** Whether the address and code are filled in, whatever the mode. */
    fun configured(s: Settings): Boolean = mode(s.copy(pcSend = BOTH)) != OFF

    fun next(mode: String): String = MODES[(MODES.indexOf(mode) + 1).mod(MODES.size)]

    /** English label; wrap in `tr()`. */
    fun label(mode: String): String = when (mode) {
        BOTH -> "Phone and PC"
        PC_ONLY -> "PC only"
        else -> "This phone only"
    }

    /** The daemon prints `ABCDE-FGHJK`; however it is typed, the letters and digits are the code. */
    fun normalizeCode(code: String): String =
        code.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.uppercase()

    /** `host`, `host:port`, an IPv6 literal, or `[ipv6]:port`. Null if it can't be an address. */
    fun address(input: String): Pair<String, Int>? {
        val t = input.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val bracketed = Regex("""^\[([^\]]+)](?::(\d+))?$""").matchEntire(t)
        val (host, port) = when {
            bracketed != null -> bracketed.groupValues[1] to bracketed.groupValues[2]
            t.count { it == ':' } == 1 -> t.substringBefore(':') to t.substringAfter(':')
            else -> t to "" // a name, an IPv4 address, or a bare IPv6 literal
        }
        val p = if (port.isEmpty()) DEFAULT_PORT else port.toIntOrNull() ?: return null
        if (host.isEmpty() || p !in 1..65535) return null
        return host to p
    }

    fun request(code: String, text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return "SOKSAK/1 ${normalizeCode(code)} ${body.size}\n".toByteArray(Charsets.UTF_8) + body
    }

    /** The daemon's reply line as an error to show (English; wrap in `tr()`), or null for OK. */
    fun replyError(line: String?): String? = when {
        line == null -> "PC not reachable"
        line.trim() == "OK" -> null
        line.trim() == "ERR code" -> "PC refused the pairing code"
        else -> "PC could not copy the text"
    }

    /** Sends [text] (empty = connection test). Null on success, else an English error. */
    suspend fun send(host: String, code: String, text: String): String? {
        val (h, port) = address(host) ?: return "PC address is not valid"
        // async on IO and a timeout on the await: a blocked name lookup can't be cancelled,
        // but the caller stops waiting for it.
        val attempt = scope.async(Dispatchers.IO) {
            try {
                Socket().use { so ->
                    so.connect(InetSocketAddress(h, port), CONNECT_MS)
                    so.soTimeout = REPLY_MS
                    so.getOutputStream().apply { write(request(code, text)); flush() }
                    replyError(so.getInputStream().bufferedReader().readLine())
                }
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.javaClass.simpleName}: ${e.message}")
                "PC not reachable"
            }
        }
        // Wrapped: a successful send is itself null, which must not read as the timeout.
        val done = withTimeoutOrNull(SEND_MS) { Result.success(attempt.await()) }
        if (done == null) attempt.cancel()
        return if (done != null) done.getOrNull() else "PC not reachable"
    }

    /**
     * Delivers a dictation according to the mode. [onPhone] is the usual delivery (insert, or
     * the clipboard) and always runs on the main thread: at once, or in [PC_ONLY] only after
     * the PC could not take the text, so a take is never lost to a computer that is off.
     * Main thread.
     */
    fun deliver(s: Settings, text: String, onPhone: () -> Unit) {
        when (mode(s)) {
            OFF -> onPhone()
            BOTH -> {
                onPhone()
                scope.launch {
                    if (send(s.pcHost, s.pcCode, text) != null) BubbleService.instance?.showPcResult(false)
                }
            }
            else -> scope.launch {
                if (send(s.pcHost, s.pcCode, text) == null) {
                    BubbleService.instance?.showPcResult(true)
                } else {
                    onPhone()
                    main.postDelayed({ BubbleService.instance?.showPcResult(false) }, AFTER_INSERT_MS)
                }
            }
        }
    }
}
