package com.voicerewriter

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The app's default settings. */
data class Settings(
    // --- Speech-to-text (Whisper) ---
    val sttModel: String = "",
    // Dictation language handed to whisper.cpp (non-English also switches on beam search).
    val sttLanguage: String = DictationLanguage.DEFAULT,
    // --- Dictation behavior ---
    val vadAutoStop: Boolean = true, // Silero VAD: auto-stop when the speaker pauses
    /** Silence before auto-stop ends the take: one of [AUTO_STOP_CHOICES]. */
    val autoStopSeconds: Double = AUTO_STOP_CHOICES.first(),
    /**
     * Record from the bubble's foreground service instead of opening the dictation sheet, so
     * the app being typed into keeps focus and its keyboard. Needs on-device STT and
     * accessibility; falls back to the sheet whenever it can't run. See [HeadlessDictation].
     */
    val headlessRecording: Boolean = true,
    /**
     * A sentence in the user's own dictation style, fed to Whisper as preceding context after
     * the glossary. Whisper continues the style of its prompt, so a mixed Korean/English dev
     * sentence nudges it to keep English terms in Latin script and to punctuate the same way.
     * Blank disables it.
     */
    val promptExample: String = DEFAULT_PROMPT_EXAMPLE,
    /** Decode Whisper dictations in pieces at pauses while still recording ([StreamingWhisper]). */
    val pipelinedWhisper: Boolean = true,
    /**
     * Quiet mode, for whispering where others are working: [quietModel] instead of [sttModel],
     * no Silero trimming, auto-stop by loudness over the room ([QuietSpeechDetector]).
     */
    val quietMode: Boolean = false,
    val quietModel: String = WhisperModelManager.SMALL_ACFT_MODEL,
    /** sherpa's QNN debug output and per-execution profiling, for Copy debug log. Costs NPU time. */
    val npuDiagnostics: Boolean = false,
    /** Settings screen language: "auto" (phone), "ko" or "en". See [I18n]. */
    val uiLanguage: String = "auto",
    /** Where a finished dictation goes: one of [PcLink.MODES]. See [PcLink]. */
    val pcSend: String = PcLink.OFF,
    /** The computer running soksak-daemon: `host` or `host:port`. */
    val pcHost: String = "",
    /** Ask the PC to type the text into its focused window, not only copy it. See [PcLink]. */
    val pcType: Boolean = false,
    /** That daemon's pairing code. A secret: kept out of backups. */
    val pcCode: String = "",
    /**
     * Show the result and wait before inserting it. Off by default: the field the user is
     * already looking at is a better place to read new text than a sheet showing it out of
     * context, and the review step cost every dictation 2-6s. On restores the old flow.
     */
    val reviewBeforeInsert: Boolean = false,
    val bubbleOnlyOnFields: Boolean = true, // show the bubble only while a text field is focused (needs accessibility)
    val hasCompletedOnboarding: Boolean = false, // first-run onboarding shown once; re-launchable from Settings
) {
    /** The settings a dictation runs with: Quiet mode swaps in its own model. */
    fun forDictation(): Settings =
        if (quietMode && quietModel.isNotBlank()) copy(sttModel = quietModel) else this

    /**
     * The settings a widget take runs with: sent to the PC whatever the PC-send setting says
     * (there is no field to type into), recorded as in Quiet mode (the user whispers there), and
     * ended after [autoStop] seconds of silence. It runs on the model the bubble is using right
     * now — the one already loaded — and not on Quiet mode's own model, which meant a model load
     * of several seconds at the start of a take whenever the two differed. Nothing is saved.
     */
    fun forWidgetTake(autoStop: Double): Settings = copy(
        pcSend = PcLink.PC_ONLY,
        quietMode = true,
        quietModel = forDictation().sttModel,
        autoStopSeconds = autoStop,
    )
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val STT_MODEL = stringPreferencesKey("sttModel")
        val STT_LANGUAGE = stringPreferencesKey("sttLanguage")
        val VAD_AUTO_STOP = booleanPreferencesKey("vadAutoStop")
        val AUTO_STOP_SECONDS = doublePreferencesKey("autoStopSeconds")
        val HEADLESS_RECORDING = booleanPreferencesKey("headlessRecording")
        val PROMPT_EXAMPLE = stringPreferencesKey("promptExample")
        val PIPELINED_WHISPER = booleanPreferencesKey("pipelinedWhisper")
        val UI_LANGUAGE = stringPreferencesKey("uiLanguage")
        val QUIET_MODE = booleanPreferencesKey("quietMode")
        val QUIET_MODEL = stringPreferencesKey("quietModel")
        val NPU_DIAGNOSTICS = booleanPreferencesKey("npuDiagnostics")
        val PC_SEND = stringPreferencesKey("pcSend")
        val PC_HOST = stringPreferencesKey("pcHost")
        val PC_CODE = stringPreferencesKey("pcCode")
        val PC_TYPE = booleanPreferencesKey("pcType")
        val REVIEW_BEFORE_INSERT = booleanPreferencesKey("reviewBeforeInsert")
        val BUBBLE_ONLY_ON_FIELDS = booleanPreferencesKey("bubbleOnlyOnFields")
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("hasCompletedOnboarding")

        /**
         * Preferences of features this build no longer has (LLM polish/rewrite, rule-based
         * cleanup, Parakeet). Dropped on the next save so a stale LLM API key doesn't linger.
         */
        val RETIRED_STRINGS = listOf(
            "widgetModel", // one build (2026-11-11) let the widget have its own model
            "provider", "model", "customEndpoint", "apiKey", "voice", "defaultMode", "polishLevel",
            "githubToken", "sttProvider", "sttEndpoint", "sttKey",
        ).map { stringPreferencesKey(it) }
        val RETIRED_BOOLEANS = listOf("antiAI", "deterministicCleanup", "parakeetHotwordsExperimental")
            .map { booleanPreferencesKey(it) }
        val RETIRED_TEMPERATURE = doublePreferencesKey("temperature")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val defaults = Settings()
        Settings(
            sttModel = p[Keys.STT_MODEL] ?: defaults.sttModel,
            sttLanguage = DictationLanguage.normalize(p[Keys.STT_LANGUAGE] ?: defaults.sttLanguage),
            vadAutoStop = p[Keys.VAD_AUTO_STOP] ?: defaults.vadAutoStop,
            autoStopSeconds = p[Keys.AUTO_STOP_SECONDS] ?: defaults.autoStopSeconds,
            headlessRecording = p[Keys.HEADLESS_RECORDING] ?: defaults.headlessRecording,
            promptExample = p[Keys.PROMPT_EXAMPLE] ?: defaults.promptExample,
            pipelinedWhisper = p[Keys.PIPELINED_WHISPER] ?: defaults.pipelinedWhisper,
            uiLanguage = p[Keys.UI_LANGUAGE] ?: defaults.uiLanguage,
            quietMode = p[Keys.QUIET_MODE] ?: defaults.quietMode,
            quietModel = p[Keys.QUIET_MODEL] ?: defaults.quietModel,
            npuDiagnostics = p[Keys.NPU_DIAGNOSTICS] ?: defaults.npuDiagnostics,
            pcSend = p[Keys.PC_SEND] ?: defaults.pcSend,
            pcHost = p[Keys.PC_HOST] ?: defaults.pcHost,
            pcCode = p[Keys.PC_CODE] ?: defaults.pcCode,
            pcType = p[Keys.PC_TYPE] ?: defaults.pcType,
            reviewBeforeInsert = p[Keys.REVIEW_BEFORE_INSERT] ?: defaults.reviewBeforeInsert,
            bubbleOnlyOnFields = p[Keys.BUBBLE_ONLY_ON_FIELDS] ?: defaults.bubbleOnlyOnFields,
            hasCompletedOnboarding = p[Keys.HAS_COMPLETED_ONBOARDING] ?: defaults.hasCompletedOnboarding,
        )
    }

    suspend fun get(): Settings = settings.first()

    suspend fun save(s: Settings) {
        context.dataStore.edit { p ->
            p[Keys.STT_MODEL] = s.sttModel
            p[Keys.STT_LANGUAGE] = DictationLanguage.normalize(s.sttLanguage)
            p[Keys.VAD_AUTO_STOP] = s.vadAutoStop
            p[Keys.AUTO_STOP_SECONDS] = s.autoStopSeconds
            p[Keys.HEADLESS_RECORDING] = s.headlessRecording
            p[Keys.PROMPT_EXAMPLE] = s.promptExample
            p[Keys.PIPELINED_WHISPER] = s.pipelinedWhisper
            p[Keys.UI_LANGUAGE] = s.uiLanguage
            p[Keys.QUIET_MODE] = s.quietMode
            p[Keys.QUIET_MODEL] = s.quietModel
            p[Keys.NPU_DIAGNOSTICS] = s.npuDiagnostics
            p[Keys.PC_SEND] = s.pcSend
            p[Keys.PC_HOST] = s.pcHost
            p[Keys.PC_CODE] = s.pcCode
            p[Keys.PC_TYPE] = s.pcType
            p[Keys.REVIEW_BEFORE_INSERT] = s.reviewBeforeInsert
            p[Keys.BUBBLE_ONLY_ON_FIELDS] = s.bubbleOnlyOnFields
            p[Keys.HAS_COMPLETED_ONBOARDING] = s.hasCompletedOnboarding
            Keys.RETIRED_STRINGS.forEach { p.remove(it) }
            Keys.RETIRED_BOOLEANS.forEach { p.remove(it) }
            p.remove(Keys.RETIRED_TEMPERATURE)
        }
    }

    /** Persist just the onboarding-complete flag without disturbing other prefs. */
    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { it[Keys.HAS_COMPLETED_ONBOARDING] = complete }
    }
}

/** Default style example: Korean dictation with English dev terms kept in Latin script. */
/**
 * Auto-stop waits, shortest first. 1.6s was the only option and cut people off while they
 * thought mid-dictation; longer waits suit long, considered prompts.
 */
val AUTO_STOP_CHOICES = listOf(1.6, 2.2, 3.0, 5.0)

const val DEFAULT_PROMPT_EXAMPLE = "GitLab에 MR 올리고, worktree 정리한 다음 Claude Code로 테스트 돌려줘."
