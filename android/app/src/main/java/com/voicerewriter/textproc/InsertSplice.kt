package com.voicerewriter.textproc

/**
 * Splicing a dictation into a field's text at the cursor.
 *
 * A second take dictated straight after the first used to land flush against it
 * ("...있어.조용한데..."): the splice put the text exactly at the cursor. A take is a phrase or
 * a sentence, so it gets a space before it when what precedes the cursor is not already
 * whitespace (or an opening bracket or quote), and a space after it when a word follows.
 */
object InsertSplice {
    private const val OPENERS = "([{<\"'“‘「『"

    /** The new text and where the cursor goes (after the inserted take). */
    fun at(current: String, start: Int, end: Int, insert: String): Pair<String, Int> {
        val before = current.substring(0, start)
        val after = current.substring(end)
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in OPENERS &&
            insert.isNotEmpty() && !insert.first().isWhitespace()) " " else ""
        val trail = if (after.isNotEmpty() && after.first().isLetterOrDigit() &&
            insert.isNotEmpty() && !insert.last().isWhitespace()) " " else ""
        return (before + lead + insert + trail + after) to (before.length + lead.length + insert.length)
    }
}
