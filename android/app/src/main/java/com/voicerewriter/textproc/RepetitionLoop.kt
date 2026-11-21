package com.voicerewriter.textproc

/**
 * A greedy Whisper decode that falls into a loop writes the same word or phrase until its token
 * limit: "…사라질 때 이렇게, 이제, 이제, 이제, … 이제, 이" (Turbo NPU, 2026-11-22, user). Whatever was
 * said after the loop began is lost with it. This finds such a loop at the end of one decode.
 *
 * Only a loop that runs to the end of the text counts — that is what hitting the token limit
 * looks like — so words really said several times in the middle of a sentence are left alone.
 */
object RepetitionLoop {
    /** Fewer repeats than this can be speech ("아니 아니 아니"). */
    private const val MIN_REPEATS = 5
    private const val MAX_UNIT_WORDS = 8

    /** One syllable or a few, run together with no spaces ("고고고고고…"). */
    private const val MAX_UNIT_CHARS = 4
    private const val MIN_CHAR_REPEATS = 8

    /**
     * Where the looping part of [text] starts (the index just after the first occurrence of the
     * repeated unit), or -1 when it does not end in a loop.
     */
    fun start(text: String): Int {
        val words = Regex("\\S+").findAll(text).toList()
        if (words.isEmpty()) return -1
        val norm = words.map { HallucinationFilter.norm(it.value) }
        // The token limit can cut the last word short ("이제, 이제, 이"): it may be a prefix.
        for (dropLast in 0..1) {
            val n = words.size - dropLast
            for (unit in 1..MAX_UNIT_WORDS) {
                if (n < unit * MIN_REPEATS) break
                if (norm.subList(n - unit, n).all { it.isEmpty() }) continue
                var first = n - unit // start of the earliest repeat found so far
                while (first - unit >= 0 && (0 until unit).all { norm[first - unit + it] == norm[first + it] }) first -= unit
                if ((n - first) / unit < MIN_REPEATS) continue
                if (dropLast == 1 && !norm[first].startsWith(norm[n])) continue
                return words[first + unit - 1].range.last + 1
            }
        }
        return charLoopStart(text, words.last().range)
    }

    /** The last word is one short unit over and over. */
    private fun charLoopStart(text: String, last: IntRange): Int {
        val w = text.substring(last)
        for (unit in 1..MAX_UNIT_CHARS) {
            if (w.length < unit * MIN_CHAR_REPEATS) break
            val u = w.takeLast(unit)
            if (!u.all { it.isLetterOrDigit() }) continue
            var first = w.length - unit
            while (first - unit >= 0 && w.regionMatches(first - unit, u, 0, unit)) first -= unit
            if ((w.length - first) / unit >= MIN_CHAR_REPEATS) return last.first + first + unit
        }
        return -1
    }

    /** [text] without the loop it ends in: one occurrence of the repeated unit stays. */
    fun cut(text: String): String {
        val at = start(text)
        return if (at < 0) text else text.substring(0, at).trimEnd()
    }
}
