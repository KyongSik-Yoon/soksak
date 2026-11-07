package com.voicerewriter

/**
 * Speech-to-text provider presets used by the dictation flow.
 */

/** Speech-to-text provider (OpenAI-compatible /audio/transcriptions). */
data class SttProvider(
    val id: String,
    val label: String,
    val endpoint: String,
    val defaultModel: String,
    val keyHelp: String,
    val keyUrl: String,
    val modelHint: String,
)

object Defaults {

    // ---------------- speech-to-text ----------------

    val STT_PROVIDERS: Map<String, SttProvider> = mapOf(
        "local" to SttProvider(
            id = "local",
            label = "On-device",
            endpoint = "",
            defaultModel = WhisperModelManager.DEFAULT_MODEL,
            keyHelp = "Runs speech-to-text directly on your phone. Fully offline, private, no API key. Pick and download a model below.",
            keyUrl = "",
            modelHint = "Managed below.",
        ),
        "groq" to SttProvider(
            id = "groq",
            label = "Groq (Whisper)",
            endpoint = "https://api.groq.com/openai/v1/audio/transcriptions",
            defaultModel = "whisper-large-v3-turbo",
            keyHelp = "Use a Groq API key (starts with gsk_). Fast and has a free tier. Get one at console.groq.com → API Keys.",
            keyUrl = "https://console.groq.com/keys",
            modelHint = "Groq model id, e.g. whisper-large-v3-turbo (fast) or whisper-large-v3 (most accurate).",
        ),
        "openai" to SttProvider(
            id = "openai",
            label = "OpenAI (Whisper)",
            endpoint = "https://api.openai.com/v1/audio/transcriptions",
            defaultModel = "whisper-1",
            keyHelp = "Use an OpenAI API key (starts with sk-). Get one at platform.openai.com → API Keys.",
            keyUrl = "https://platform.openai.com/api-keys",
            modelHint = "OpenAI transcription model, e.g. whisper-1 or gpt-4o-mini-transcribe.",
        ),
        "custom" to SttProvider(
            id = "custom",
            label = "Custom (OpenAI-compatible)",
            endpoint = "",
            defaultModel = "",
            keyHelp = "Any OpenAI-compatible /v1/audio/transcriptions endpoint accepting multipart audio.",
            keyUrl = "",
            modelHint = "Whatever transcription model id your endpoint expects.",
        ),
    )

    // On-device speech-to-text by default (whisper.cpp). Cloud STT is opt-in.
    const val DEFAULT_STT_PROVIDER = "local"
}
