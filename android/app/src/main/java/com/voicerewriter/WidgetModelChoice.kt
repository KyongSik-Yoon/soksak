package com.voicerewriter

/** The "Widget model" row in Settings: blank ("same as Quiet mode"), then each downloaded model. */
object WidgetModelChoice {
    /** The choice after [current] among blank and [ready]; an unknown [current] starts over at blank. */
    fun next(current: String, ready: List<String>): String {
        val choices = listOf("") + ready
        val i = choices.indexOf(current)
        return if (i < 0) "" else choices[(i + 1) % choices.size]
    }
}
