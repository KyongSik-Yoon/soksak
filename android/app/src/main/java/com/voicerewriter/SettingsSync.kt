package com.voicerewriter

/**
 * Which of the settings the floating bubble's menu writes (Quiet mode and its model, the speech
 * model, Send to PC and what the PC does with the text) moved between two stored states. The
 * Settings screen keeps its own copy of every field and saves all of them at once, so it has to
 * pick these up when they change underneath it.
 */
object SettingsSync {
    /** A field is null when it did not change. */
    data class Changes(
        val quietMode: Boolean? = null,
        val quietModel: String? = null,
        val sttModel: String? = null,
        val pcSend: String? = null,
        val pcType: Boolean? = null,
    )

    fun changedByOthers(before: Settings, after: Settings) = Changes(
        quietMode = after.quietMode.takeIf { it != before.quietMode },
        quietModel = after.quietModel.takeIf { it != before.quietModel },
        sttModel = after.sttModel.takeIf { it != before.sttModel },
        pcSend = after.pcSend.takeIf { it != before.pcSend },
        pcType = after.pcType.takeIf { it != before.pcType },
    )
}
