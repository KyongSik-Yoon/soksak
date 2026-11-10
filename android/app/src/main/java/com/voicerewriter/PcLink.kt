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
 * Wire format (see `daemon/src/lib.rs`): `SOKSAK/1 <code> <byte length>[ type]\n<text>`, answered
 * with `OK`, `OK clipboard` or `ERR <reason>`. A length of 0 only checks the code.
 *
 * The text always lands on the PC's clipboard. `type` ([Settings.pcType]) asks the PC to type it
 * into the focused window as well. Whether that is allowed is the PC's decision (`allow_type`
 * in its config, off by default, because typing into a terminal runs what was said); a PC that
 * doesn't allow it copies and answers `OK clipboard`.
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

    /** English label for what the PC does with the text; wrap in `tr()`. */
    fun actionLabel(type: Boolean): String = if (type) "Type it in" else "Clipboard"

    fun request(code: String, text: String, type: Boolean): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        val ask = if (type) " type" else ""
        return "SOKSAK/1 ${normalizeCode(code)} ${body.size}$ask\n".toByteArray(Charsets.UTF_8) + body
    }

    /**
     * How a send went. [error] is English (wrap in `tr()`), null when the PC took the text;
     * [clipboardOnly] when typing was asked for and the PC only copied.
     */
    data class Outcome(val error: String? = null, val clipboardOnly: Boolean = false) {
        val ok: Boolean get() = error == null
    }

    private val UNREACHABLE = Outcome("PC not reachable")

    /** The daemon's reply line. */
    fun outcome(line: String?): Outcome = when (line?.trim()) {
        null -> UNREACHABLE
        "OK" -> Outcome()
        "OK clipboard" -> Outcome(clipboardOnly = true)
        "ERR code" -> Outcome("PC refused the pairing code")
        else -> Outcome("PC could not copy the text")
    }

    /** Sends [text] (empty = connection test), asking the PC to type it if [type]. */
    suspend fun send(host: String, code: String, text: String, type: Boolean): Outcome {
        val (h, port) = address(host) ?: return Outcome("PC address is not valid")
        // async on IO and a timeout on the await: a blocked name lookup can't be cancelled,
        // but the caller stops waiting for it.
        val attempt = scope.async(Dispatchers.IO) {
            try {
                Socket().use { so ->
                    so.connect(InetSocketAddress(h, port), CONNECT_MS)
                    so.soTimeout = REPLY_MS
                    so.getOutputStream().apply { write(request(code, text, type)); flush() }
                    outcome(so.getInputStream().bufferedReader().readLine())
                }
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.javaClass.simpleName}: ${e.message}")
                UNREACHABLE
            }
        }
        return withTimeoutOrNull(SEND_MS) { attempt.await() } ?: UNREACHABLE.also { attempt.cancel() }
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
                    val sent = send(s.pcHost, s.pcCode, text, s.pcType)
                    // The phone's own "inserted" already answered a normal send; say only what
                    // differs from what was asked.
                    if (!sent.ok || sent.clipboardOnly) BubbleService.instance?.showPcResult(sent)
                }
            }
            else -> scope.launch {
                val sent = send(s.pcHost, s.pcCode, text, s.pcType)
                if (sent.ok) {
                    BubbleService.instance?.showPcResult(sent)
                } else {
                    onPhone()
                    main.postDelayed({ BubbleService.instance?.showPcResult(sent) }, AFTER_INSERT_MS)
                }
            }
        }
    }
}
