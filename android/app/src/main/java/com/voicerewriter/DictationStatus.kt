package com.voicerewriter

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where the current dictation is, for a screen that is not the bubble
 * ([LockDictationActivity]). [BubbleService]'s show* calls are the only writers, so this is
 * exactly what the bubble shows.
 */
object DictationStatus {
    enum class Phase { IDLE, LISTENING, WORKING, INSERTED, COPIED, TO_PC, PC_CLIP, NO_PC }

    val phase = MutableStateFlow(Phase.IDLE)

    /** Latest microphone peak while listening, 0..32767. */
    val amplitude = MutableStateFlow(0)

    /** While listening: how much of the auto-stop silence wait has passed, 0..1. */
    val silence = MutableStateFlow(0f)

    /** While working: the estimated share of the transcription done, 0..1 ([TranscribeProgress]). */
    val progress = MutableStateFlow(0f)

    /** Phases that end a take with its text delivered somewhere (or, for NO_PC, not to the PC). */
    fun isResult(p: Phase) = p != Phase.IDLE && p != Phase.LISTENING && p != Phase.WORKING
}
