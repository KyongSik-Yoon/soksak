package com.voicerewriter.textproc

/**
 * Drops the two kinds of Whisper output that are not what was said:
 *
 *  1. **Prompt echo.** On a near-empty clip Whisper copies the end of its prompt. With the
 *     default Style example, Small ACFT turned a 1s clip into "정리한 다음 Claude Code로 테스트
 *     돌려줘." (seen in Compare, 2026-11-09). Output that is just a piece of the Style example or
 *     the glossary is dropped.
 *  2. **Stock phrases** from Whisper's training data (TV news, YouTube outros) that come out of
 *     silence or noise: "이 시각 세계였습니다." (Turbo on the same clip), "시청해 주셔서 감사합니다".
 *     Sentences made only of these are removed; real words around them stay.
 */
object HallucinationFilter {
    /** Shorter echoes ("네.", "확인") are too likely to be real speech that also appears in the prompt. */
    private const val MIN_ECHO_CHARS = 6

    private val STOCK = listOf(
        "이 시각 세계였습니다",
        "시청해 주셔서 감사합니다",
        "시청해주셔서 감사합니다",
        "구독과 좋아요",
        "구독 좋아요",
        "좋아요와 구독",
        "다음 영상에서 만나요",
        "다음 시간에 만나요",
        "mbc 뉴스",
        "kbs 뉴스",
        "sbs 뉴스",
        "ytn 뉴스",
        "자막 제공",
        "자막은 설정에서",
        "한글자막",
        "thank you for watching",
        "thanks for watching",
        "please subscribe",
        "subtitles by",
    ).map(::norm)

    /** [promptParts]: the Style example and the glossary as handed to Whisper. */
    fun clean(text: String, vararg promptParts: String?): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        if (promptParts.any { isEcho(t, it) }) return ""
        val sentences = splitSentences(t)
        val kept = sentences.filterNot { s -> norm(s).let { n -> n.isNotEmpty() && STOCK.any { n.contains(it) } && isMostlyStock(n) } }
        // Untouched text goes back as it was ("3.5 버전" must not become "3. 5 버전").
        return if (kept.size == sentences.size) t else kept.joinToString(" ").trim()
    }

    private fun isEcho(text: String, source: String?): Boolean {
        if (source.isNullOrBlank()) return false
        val n = norm(text)
        return n.length >= MIN_ECHO_CHARS && norm(source).contains(n)
    }

    /** The sentence is a stock phrase plus at most a few characters (a name, "MBC 뉴스 김철수입니다"). */
    private fun isMostlyStock(n: String): Boolean {
        val hit = STOCK.filter { n.contains(it) }.maxOf { it.length }
        return n.length - hit <= 8
    }

    internal fun splitSentences(t: String): List<String> =
        Regex("""[^.!?。]+[.!?。]*""").findAll(t).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()

    /** Lowercase, letters and digits only — spacing and punctuation differ between runs. */
    internal fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
