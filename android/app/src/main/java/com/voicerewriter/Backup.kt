package com.voicerewriter

import org.json.JSONArray
import org.json.JSONObject

/**
 * A JSON file of the personal dictionary (including learned words) and the settings, so they can
 * move to another install: a new phone, or this app under a new application id, which Android
 * treats as a different app with empty storage.
 *
 * Secrets stay out of the file: the GitHub token, the speech API key and the PC pairing code are
 * never written and a restore keeps whatever this install already has for them. Downloaded models aren't included
 * (hundreds of MB; download them again).
 */
object Backup {
    const val FORMAT = "soksak-backup"
    const val VERSION = 1

    data class Restored(val settings: Settings, val vocabJson: String?)

    fun encode(s: Settings, vocabJson: String?): String {
        val settings = JSONObject()
            .put("sttProvider", s.sttProvider)
            .put("sttEndpoint", s.sttEndpoint)
            .put("sttModel", s.sttModel)
            .put("sttLanguage", s.sttLanguage)
            .put("vadAutoStop", s.vadAutoStop)
            .put("autoStopSeconds", s.autoStopSeconds)
            .put("headlessRecording", s.headlessRecording)
            .put("promptExample", s.promptExample)
            .put("pipelinedWhisper", s.pipelinedWhisper)
            .put("quietMode", s.quietMode)
            .put("quietModel", s.quietModel)
            .put("npuDiagnostics", s.npuDiagnostics)
            .put("uiLanguage", s.uiLanguage)
            .put("reviewBeforeInsert", s.reviewBeforeInsert)
            .put("bubbleOnlyOnFields", s.bubbleOnlyOnFields)
            .put("pcSend", s.pcSend)
            .put("pcHost", s.pcHost)
            .put("pcType", s.pcType)
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("settings", settings)
            .put("vocab", vocabJson?.takeIf { it.isNotBlank() }?.let { JSONArray(it) } ?: JSONArray())
        return root.toString(2)
    }

    /**
     * Reads a backup over [current]: a field the file lacks keeps its current value, and the
     * secrets always do. Throws [IllegalArgumentException] for anything that isn't a backup.
     */
    fun decode(text: String, current: Settings): Restored {
        val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("Not a Soksak backup file.") }
        require(root.optString("format") == FORMAT) { "Not a Soksak backup file." }
        require(root.optInt("version", 0) in 1..VERSION) { "This backup is from a newer version of the app." }
        val o = root.optJSONObject("settings") ?: JSONObject()
        fun str(k: String, d: String) = if (o.has(k)) o.optString(k, d) else d
        fun bool(k: String, d: Boolean) = if (o.has(k)) o.optBoolean(k, d) else d
        fun dbl(k: String, d: Double) = if (o.has(k)) o.optDouble(k, d) else d
        val settings = current.copy(
            sttProvider = str("sttProvider", current.sttProvider),
            sttEndpoint = str("sttEndpoint", current.sttEndpoint),
            sttModel = str("sttModel", current.sttModel),
            sttLanguage = str("sttLanguage", current.sttLanguage),
            vadAutoStop = bool("vadAutoStop", current.vadAutoStop),
            autoStopSeconds = dbl("autoStopSeconds", current.autoStopSeconds),
            headlessRecording = bool("headlessRecording", current.headlessRecording),
            promptExample = str("promptExample", current.promptExample),
            pipelinedWhisper = bool("pipelinedWhisper", current.pipelinedWhisper),
            quietMode = bool("quietMode", current.quietMode),
            quietModel = str("quietModel", current.quietModel),
            npuDiagnostics = bool("npuDiagnostics", current.npuDiagnostics),
            uiLanguage = str("uiLanguage", current.uiLanguage),
            reviewBeforeInsert = bool("reviewBeforeInsert", current.reviewBeforeInsert),
            bubbleOnlyOnFields = bool("bubbleOnlyOnFields", current.bubbleOnlyOnFields),
            pcSend = str("pcSend", current.pcSend),
            pcHost = str("pcHost", current.pcHost),
            pcType = bool("pcType", current.pcType),
        )
        val vocab = root.optJSONArray("vocab")?.takeIf { it.length() > 0 }?.toString()
        return Restored(settings, vocab)
    }
}
