package com.voicerewriter

import kotlin.math.sqrt

/**
 * 내 목소리를 여러 번 나눠 받아 하나의 프로필로 합치는 계산(안드로이드·ONNX 의존 없음).
 *
 * 한 번만 녹음하면 그때의 거리·성량·말투가 그대로 "내 목소리"로 굳고, 그 등록이 괜찮은지 알 길이 없다.
 * 짧은·중간·긴 문구를 하나씩 받아 CAM++ 임베딩을 평균하고, 한 녹음이 나머지와 덜 닮았으면 그것만 다시 받는다.
 * 게이트는 무슨 말이든 판정해야 하므로(호출어 방식이 아님) 문구는 매번 다르게 한다. 짧은 문구는 게이트가
 * 실제로 채점하는 0.8초 창과 조건이 비슷해지도록 넣었다.
 */
object VoiceEnrollment {
    val PHRASES = listOf(
        "빌드 다시 돌려 줘.",
        "로그에서 에러 난 부분 찾아서 고쳐 줘.",
        "GitLab에 MR 올리고, worktree 정리한 다음 Claude Code로 테스트 돌려줘. API 응답이 느려서 Redis 캐시도 같이 붙여야 해.",
    )

    /** 녹음 하나에서 잡혀야 하는 말소리 길이(초). 짧은 문구도 이 정도는 나온다. */
    const val MIN_CLIP_VOICED_S = 0.8f

    /** 한 녹음이 나머지 평균과 이보다 덜 닮으면 그 녹음만 다시 받는다 — 게이트가 "내 목소리"로 보는 기준과 같다. */
    const val CONSISTENCY_MIN = SpeakerGate.SIM_THRESHOLD

    /** 등록 녹음 하나의 자동 중지 대기(초). 읽다가 쉼표에서 쉬는 정도로는 끊기지 않게 대화보다 조금 길다. */
    const val CLIP_AUTO_STOP_S = 1.5

    /** 등록 녹음 하나의 상한(초). 말을 안 하면 여기서 멈춘다. */
    const val CLIP_MAX_S = 20f

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "embedding sizes differ: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na <= 0.0 || nb <= 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** 임베딩들의 평균을 L2 정규화해 돌려준다. */
    fun mean(embs: List<FloatArray>): FloatArray {
        require(embs.isNotEmpty()) { "no embeddings" }
        val dim = embs[0].size
        val sum = DoubleArray(dim)
        for (e in embs) {
            require(e.size == dim) { "embedding sizes differ" }
            for (i in 0 until dim) sum[i] += e[i].toDouble()
        }
        var norm = 0.0
        for (v in sum) norm += v * v
        norm = sqrt(norm)
        return FloatArray(dim) { if (norm > 0.0) (sum[it] / norm).toFloat() else 0f }
    }

    data class Consistency(val index: Int, val similarity: Float)

    /** 녹음마다 나머지 녹음 평균과의 유사도. 녹음이 둘 미만이면 빈 목록. */
    fun similarityToOthers(embs: List<FloatArray>): List<Float> {
        if (embs.size < 2) return emptyList()
        return embs.indices.map { i -> cosine(embs[i], mean(embs.filterIndexed { j, _ -> j != i })) }
    }

    /** 각 녹음을 나머지의 평균과 비교해 가장 덜 닮은 녹음과 그 유사도. 녹음이 둘 미만이면 null. */
    fun leastConsistent(embs: List<FloatArray>): Consistency? {
        val sims = similarityToOthers(embs)
        if (sims.isEmpty()) return null
        val i = sims.indices.minBy { sims[it] }
        return Consistency(i, sims[i])
    }

    /**
     * 길이 [n]에서 [window] 길이 구간의 시작점을 [hop] 간격으로 훑어(후보가 [maxWindows]개를 넘으면 간격을 넓혀
     * 고르게) [score]가 가장 높은 시작점을 돌려준다. 마지막 구간(끝에 붙은 것)은 늘 후보에 든다.
     * 점수를 못 낸(null) 구간은 건너뛰고, 모두 못 내거나 [n] < [window]면 null.
     */
    fun bestWindowStart(n: Int, window: Int, hop: Int, maxWindows: Int, score: (Int) -> Float?): Int? {
        require(window > 0 && hop > 0 && maxWindows >= 2)
        if (n < window) return null
        val span = n - window
        val step = if (span / hop + 1 > maxWindows) (span + maxWindows - 2) / (maxWindows - 1) else hop
        val starts = ArrayList<Int>()
        var s = 0
        while (s < span) { starts.add(s); s += step }
        starts.add(span)
        var best: Int? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (st in starts) {
            val sc = score(st) ?: continue
            if (sc > bestScore) { bestScore = sc; best = st }
        }
        return best
    }
}
