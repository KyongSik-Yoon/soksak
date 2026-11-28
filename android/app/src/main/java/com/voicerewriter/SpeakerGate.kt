package com.voicerewriter

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * 초경량 화자 게이트(Speaker Gate) 로직.
 *
 * Silero VAD 활성 세그먼트를 `0.8s` 창 / `0.25s` 홉으로 채점해 타겟 화자 여부를 판정하며:
 * 1) 녹음 중 TV·타인 말소리 때문에 Auto-stop 타이머가 풀리지 않게 보호하고,
 * 2) 독립된 타인 발화 세그먼트와 혼합 세그먼트의 앞/뒤 비타겟 꼬리만 `0`으로 마스킹해
 *    `overlap0` 동시 발화 구간에서 내 목소리까지 지우는 부작용을 막는다.
 */
object SpeakerGate {
    const val SR = 16_000
    const val VAD_CHUNK = 512
    const val WIN_SAMPLES = (0.8 * SR).toInt() // 12800
    const val HOP_SAMPLES = (0.25 * SR).toInt() // 4000
    const val MIN_SEG_SAMPLES = (0.25 * SR).toInt() // 4000
    const val SIM_THRESHOLD = 0.35f
    const val FALLBACK_SIM = 0.25f

    const val SPEECH_START_PROB = 0.5f
    const val SPEECH_END_PROB = 0.35f
    const val MIN_SPEECH_SAMPLES = (0.4 * SR).toInt() // 6400
    const val PRE_PAD_SAMPLES = (0.2 * SR).toInt() // 3200
    const val POST_PAD_SAMPLES = (0.3 * SR).toInt() // 4800

    /**
     * 내 목소리로 판정된 창이 대기 시간만큼 없었더라도, 말소리가 이만큼 쉰 뒤에만 Auto-stop 한다.
     * 본인 목소리가 기준 아래로 채점되는 동안(2026-11-25 폰 로그: 창 26개 중 4개만 통과, 말이 0.0초 전까지
     * 이어지는데 "내 목소리 2.9초 전")에는 프레임 하나만 조용해도 문장 중간에서 끊겼다. TV는 문장 사이에
     * 이 정도는 쉬므로 여전히 끝난다.
     */
    const val STOP_PAUSE_SAMPLES = (0.7 * SR).toInt() // 11200

    private const val MAX_GAP_SAMPLES = (0.30 * SR).toInt() // 4800
    internal const val SEG_PAD_SAMPLES = (0.15 * SR).toInt() // 2400
    private const val EDGE_PAD_SAMPLES = (0.10 * SR).toInt() // 1600
    private const val FADE_SAMPLES = (0.010 * SR).toInt() // 160 (10 ms)

    data class Segment(val start: Int, val end: Int)

    data class ScoredWindow(val start: Int, val end: Int, val similarity: Float)

    data class GateResult(
        val samples: ShortArray,
        val keepRanges: List<Segment>,
        val trimStart: Int,
        val trimEnd: Int,
        /**
         * 트리밍 전 전체 길이(`rawFull.size`)의 마스킹 배열.
         * [AudioRecorder.stop] 직후 [StreamingWhisper.finish]가 원본 오프셋 기준으로 조각을 읽을 때
         * 확정된 마스킹 결과를 반영하는 데 쓴다.
         */
        val maskedFull: ShortArray = samples,
        /**
         * 말소리는 있었는데 어느 창도 등록 목소리로 판정되지 않아 [samples]가 비었을 때, 그 말소리 구간의
         * 원본(앞뒤 여유 포함). 받아쓰기를 조용히 버리지 않고 Unfinished에 남기는 데 쓴다.
         */
        val rejected: ShortArray? = null,
        /** 채점한 창 중 가장 높은 유사도(창이 없으면 -2). 거른 이유를 로그로 남기는 용도. */
        val bestSimilarity: Float = -2f,
        val windows: Int = 0,
    )

    /**
     * VAD 활성 프레임(`prob >= 0.35`)을 `0.30s` 이하 간격으로 병합하고 앞뒤 `0.15s` 패딩을 붙인다.
     * `voice_bench_run.py`의 `active_segments`와 1:1 일치한다.
     */
    fun activeSegments(probs: FloatArray, nSamples: Int): List<Segment> {
        val segs = ArrayList<Segment>()
        var inSeg = false
        var start = 0
        for (i in probs.indices) {
            val active = probs[i] >= SPEECH_END_PROB
            if (active && !inSeg) {
                inSeg = true
                start = i * VAD_CHUNK
            } else if (!active && inSeg) {
                inSeg = false
                segs.add(Segment(start, i * VAD_CHUNK))
            }
        }
        if (inSeg) segs.add(Segment(start, probs.size * VAD_CHUNK))
        if (segs.isEmpty()) return emptyList()

        val merged = ArrayList<Segment>()
        var cs = max(0, segs[0].start - SEG_PAD_SAMPLES)
        var ce = min(nSamples, segs[0].end + SEG_PAD_SAMPLES)
        for (k in 1 until segs.size) {
            val s2 = max(0, segs[k].start - SEG_PAD_SAMPLES)
            val e2 = min(nSamples, segs[k].end + SEG_PAD_SAMPLES)
            if (s2 - ce <= MAX_GAP_SAMPLES) {
                ce = max(ce, e2)
            } else {
                merged.add(Segment(cs, ce))
                cs = s2
                ce = e2
            }
        }
        merged.add(Segment(cs, ce))
        return merged
    }

    /** 단일 세그먼트 `[segStart, segEnd)`를 `0.8s` 창 / `0.25s` 홉으로 채점한다. */
    fun scoreSegment(
        segStart: Int,
        segEnd: Int,
        scorer: (start: Int, length: Int) -> Float,
    ): List<ScoredWindow> {
        val len = segEnd - segStart
        if (len < MIN_SEG_SAMPLES) return emptyList()
        if (len <= WIN_SAMPLES) {
            return listOf(ScoredWindow(segStart, segEnd, scorer(segStart, len)))
        }
        val out = ArrayList<ScoredWindow>()
        var pos = segStart
        while (pos + WIN_SAMPLES <= segEnd) {
            out.add(ScoredWindow(pos, pos + WIN_SAMPLES, scorer(pos, WIN_SAMPLES)))
            pos += HOP_SAMPLES
        }
        val coveredEnd = pos - HOP_SAMPLES + WIN_SAMPLES
        if (segEnd - coveredEnd > MIN_SEG_SAMPLES / 2) {
            val tailStart = max(segStart, segEnd - WIN_SAMPLES)
            out.add(ScoredWindow(tailStart, segEnd, scorer(tailStart, segEnd - tailStart)))
        }
        return out
    }

    /**
     * 규칙 A (독립 타인 세그먼트 전체 마스킹) + 규칙 B (혼합 세그먼트 경계만 트리밍, 내부 보존) 적용.
     */
    fun computeKeepRanges(
        segments: List<Segment>,
        scored: List<ScoredWindow>,
        threshold: Float = SIM_THRESHOLD,
        fallbackSim: Float = FALLBACK_SIM,
    ): List<Segment> {
        if (segments.isEmpty()) return emptyList()

        val rawRanges = ArrayList<Segment>()
        var bestFallbackIdx = -1
        var bestFallbackSim = -2f

        for ((idx, seg) in segments.withIndex()) {
            val overlapping = scored.filter { w -> w.end > seg.start && w.start < seg.end }
            var segMaxSim = -2f
            var firstHitStart = Int.MAX_VALUE
            var lastHitEnd = -1

            for (w in overlapping) {
                if (w.similarity > segMaxSim) segMaxSim = w.similarity
                if (w.similarity >= threshold) {
                    firstHitStart = min(firstHitStart, w.start)
                    lastHitEnd = max(lastHitEnd, w.end)
                }
            }
            if (segMaxSim > bestFallbackSim) {
                bestFallbackSim = segMaxSim
                bestFallbackIdx = idx
            }

            if (lastHitEnd >= 0) {
                // 규칙 B: 혼합 세그먼트의 첫 타겟 앞/마지막 타겟 뒤만 자르고 내부는 온전히 보존한다.
                val ks = max(seg.start, firstHitStart - EDGE_PAD_SAMPLES)
                val ke = min(seg.end, lastHitEnd + EDGE_PAD_SAMPLES)
                if (ks < ke) rawRanges.add(Segment(ks, ke))
            }
        }

        // 녹음 전체에 threshold(0.35)를 넘은 세그먼트가 없지만 0.25 이상인 단독 짧은 발화가 있으면 보존
        if (rawRanges.isEmpty() && bestFallbackIdx >= 0 && bestFallbackSim >= fallbackSim) {
            for (seg in segments) {
                val segMax = scored
                    .filter { w -> w.end > seg.start && w.start < seg.end }
                    .maxOfOrNull { it.similarity } ?: -2f
                if (segMax >= fallbackSim) rawRanges.add(seg)
            }
        }

        if (rawRanges.isEmpty()) return emptyList()

        // 인접하거나 겹치는 보존 구간 정렬/병합
        rawRanges.sortBy { it.start }
        val merged = ArrayList<Segment>()
        var cs = rawRanges[0].start
        var ce = rawRanges[0].end
        for (k in 1 until rawRanges.size) {
            val next = rawRanges[k]
            if (next.start <= ce) {
                ce = max(ce, next.end)
            } else {
                merged.add(Segment(cs, ce))
                cs = next.start
                ce = next.end
            }
        }
        merged.add(Segment(cs, ce))
        return merged
    }

    /**
     * [pcm]을 직접 변형하지 않고, 비타겟 구간을 0으로 마스킹하고 경계 10ms 선형 페이드를 적용한 사본과
     * 권장 트리밍 구간을 반환한다.
     */
    fun applyGateMask(
        pcm: ShortArray,
        probs: FloatArray,
        scored: List<ScoredWindow>,
        threshold: Float = SIM_THRESHOLD,
    ): GateResult {
        if (pcm.isEmpty()) return GateResult(pcm, emptyList(), 0, 0)
        val segments = activeSegments(probs, pcm.size)
        val keepRanges = computeKeepRanges(segments, scored, threshold = threshold)
        if (keepRanges.isEmpty()) {
            // 타겟 화자가 한 마디도 안 잡혔으면 전사하지 않는다(오탐지 전사 방지). 말소리 구간은 따로 돌려줘
            // 호출부가 알리고 보관할 수 있게 한다 — 등록 목소리와 점수가 낮게 나온 내 말일 수도 있다.
            val rejected = if (segments.isEmpty()) null else pcm.copyOfRange(
                max(0, segments.first().start - PRE_PAD_SAMPLES),
                min(pcm.size, segments.last().end + POST_PAD_SAMPLES),
            )
            return GateResult(
                ShortArray(0), emptyList(), 0, 0,
                rejected = rejected,
                bestSimilarity = scored.maxOfOrNull { it.similarity } ?: -2f,
                windows = scored.size,
            )
        }

        val masked = ShortArray(pcm.size)
        for (r in keepRanges) {
            val s = r.start.coerceIn(0, pcm.size)
            val e = r.end.coerceIn(s, pcm.size)
            pcm.copyInto(masked, s, s, e)

            // 컷 경계 클릭/팝 노이즈 방지를 위한 10ms 페이드 인/아웃
            val fadeLen = min(FADE_SAMPLES, (e - s) / 4)
            if (fadeLen > 1) {
                if (s > 0) {
                    for (i in 0 until fadeLen) {
                        val g = i.toFloat() / fadeLen
                        masked[s + i] = (masked[s + i] * g).toInt().toShort()
                    }
                }
                if (e < pcm.size) {
                    for (i in 0 until fadeLen) {
                        val g = i.toFloat() / fadeLen
                        masked[e - 1 - i] = (masked[e - 1 - i] * g).toInt().toShort()
                    }
                }
            }
        }

        val trimStart = max(0, keepRanges.first().start - PRE_PAD_SAMPLES)
        val trimEnd = min(pcm.size, keepRanges.last().end + POST_PAD_SAMPLES)
        return GateResult(masked, keepRanges, trimStart, trimEnd, maskedFull = masked)
    }

    /**
     * 세그먼트별 채점 결과 요약(진단 로그용, 최대 10개 세그먼트).
     * 예: `0.3-1.5s(0.41..0.68,4/4) 2.2-3.4s(0.08..0.14,0/4)`
     */
    internal fun formatSegmentScores(
        segments: List<Segment>,
        scored: List<ScoredWindow>,
        threshold: Float = SIM_THRESHOLD,
    ): String {
        if (segments.isEmpty()) return "none"
        val sr = SR.toDouble()
        return segments.take(10).joinToString(" ") { seg ->
            val wins = scored.filter { w -> w.end > seg.start && w.start < seg.end }
            val range = "%.1f-%.1fs".format(seg.start / sr, seg.end / sr)
            if (wins.isEmpty()) "$range(no-win)"
            else {
                val minS = wins.minOf { it.similarity }
                val maxS = wins.maxOf { it.similarity }
                val hits = wins.count { it.similarity >= threshold }
                "$range(%.2f..%.2f,%d/%d)".format(minS, maxS, hits, wins.size)
            }
        } + if (segments.size > 10) " +${segments.size - 10}" else ""
    }
}

/**
 * 실시간 녹음 중 동작하는 화자 게이트 세션.
 *
 * 오디오 읽기 스레드는 512샘플마다 [onVadFrame]만 호출해 지연을 만들지 않고,
 * 백그라운드 단일 워커 스레드가 새 VAD 활성 구간을 비동기 채점해 Auto-stop 타이머를 갱신한다.
 */
class SpeakerGateSession(
    private val scorer: (start: Int, length: Int) -> Float,
    private val vadAutoStop: Boolean,
    private val hangoverSamples: Int,
    private val threshold: Float = SpeakerGate.SIM_THRESHOLD,
    /** 로그에서 어느 오디오 경로(mic / isolated / mic-fallback)의 게이트인지 구분한다. */
    private val label: String = "mic",
) {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SpeakerGateScorer").apply { isDaemon = true }
    }

    private val probs = ArrayList<Float>()
    private val scored = ArrayList<SpeakerGate.ScoredWindow>()

    private var totalSamples = 0
    private var scoringInFlight = false
    private var lastScoredSegmentsHash = 0
    private var scoredUpToSample = 0

    // 타겟 화자 기준 Auto-stop 상태
    private var targetSpeechStarted = false
    private var firstTargetSpeechSample = 0
    private var lastTargetSpeechSample = 0
    private var targetSpeechSamples = 0
    private var coveredTargetEnd = 0

    // 폴백용(등록 목소리가 전혀 감지되지 않고 무음만 흐를 때 무한 녹음 방지)
    private var rawSpeechSamples = 0
    private var rawLastSpeechSample = 0
    private var rawLatestActiveSample = 0
    private var autoStopFired = false

    @Volatile private var stopped = false

    /** 매 VAD 프레임마다 호출한다. Auto-stop 조건을 만족하면 true를 반환한다. */
    fun onVadFrame(prob: Float, endSample: Int): Boolean {
        var shouldSchedule = false
        var fireNow = false

        synchronized(lock) {
            if (stopped) return false
            probs.add(prob)
            totalSamples = endSample

            if (prob >= SpeakerGate.SPEECH_START_PROB) {
                rawSpeechSamples += SpeakerGate.VAD_CHUNK
                rawLastSpeechSample = endSample
            }
            if (prob >= SpeakerGate.SPEECH_END_PROB) {
                rawLatestActiveSample = endSample
            }

            // 아직 채점할 활성 세그먼트 여지가 있으면 백그라운드 스레드 깨움
            if (!scoringInFlight && totalSamples - scoredUpToSample >= SpeakerGate.HOP_SAMPLES) {
                if (rawLatestActiveSample > scoredUpToSample || totalSamples - rawLatestActiveSample <= SpeakerGate.SEG_PAD_SAMPLES + SpeakerGate.HOP_SAMPLES) {
                    scoringInFlight = true
                    shouldSchedule = true
                }
            }

            if (vadAutoStop && !autoStopFired && prob < SpeakerGate.SPEECH_END_PROB) {
                fireNow = checkAutoStopLocked(endSample)
                if (fireNow) {
                    autoStopFired = true
                    firedNote = "fired at %.1fs: %.1fs since my voice, %.1fs since any speech, scored up to %.1fs".format(
                        endSample / sr, (endSample - lastTargetSpeechSample) / sr,
                        (endSample - rawLatestActiveSample) / sr, scoredUpToSample / sr)
                }
            }
        }

        if (shouldSchedule) {
            executor.execute { runBackgroundScoring() }
        }
        return fireNow
    }

    private fun checkAutoStopLocked(endSample: Int): Boolean {
        if (targetSpeechSamples >= SpeakerGate.MIN_SPEECH_SAMPLES) {
            val quietWait = endSample - lastTargetSpeechSample
            if (quietWait < hangoverSamples) return false
            if (endSample - rawLatestActiveSample < min(hangoverSamples, SpeakerGate.STOP_PAUSE_SAMPLES)) return false
            // 방금 활성 말이 끝나서 아직 백그라운드 채점 중인 창이 있다면 최대 0.8s만 유예 후 확정
            val pendingSpeech = scoringInFlight && rawLatestActiveSample > scoredUpToSample &&
                quietWait < hangoverSamples + SpeakerGate.WIN_SAMPLES
            return !pendingSpeech
        }
        // 녹음 중 한 번도 내 목소리 임계치를 못 넘었지만 소리가 0.4s 이상 있었고 실제 무음이 오래 이어진 경우 폴백
        if (rawSpeechSamples >= SpeakerGate.MIN_SPEECH_SAMPLES && endSample - rawLastSpeechSample >= hangoverSamples) {
            return true
        }
        return false
    }

    private val sr = SpeakerGate.SR.toDouble()
    private var firedNote: String? = null

    /**
     * 받아쓰기가 왜 그때 끝났는지 로그로 남기는 용도: 자동 중지가 걸린 순간의 상태와, 창별 유사도.
     * 말하는 도중 끊겼다면 "since my voice"는 길고 "since any speech"는 짧게 나온다.
     */
    fun summary(): String = synchronized(lock) {
        val sims = scored.map { it.similarity }
        "gate($label): %s; windows %d, mine %d, similarity %.2f..%.2f, last five %s".format(
            firedNote ?: "not fired", sims.size, sims.count { it >= threshold },
            sims.minOrNull() ?: 0f, sims.maxOrNull() ?: 0f,
            scored.takeLast(5).joinToString(" ") { "%.2f".format(it.similarity) })
    }

    /** UI 무음 링 표시용(0..1). */
    fun silenceProgress(): Float = synchronized(lock) {
        if (!vadAutoStop) return 0f
        return when {
            targetSpeechSamples >= SpeakerGate.MIN_SPEECH_SAMPLES ->
                ((totalSamples - lastTargetSpeechSample).toFloat() / hangoverSamples).coerceIn(0f, 1f)
            rawSpeechSamples >= SpeakerGate.MIN_SPEECH_SAMPLES && !scoringInFlight ->
                ((totalSamples - rawLastSpeechSample).toFloat() / hangoverSamples).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    /** 지금까지 등록된 내 목소리(유사도 >= [threshold])가 한 창 이상 확인됐는지 여부. */
    fun hasTargetVoice(): Boolean = synchronized(lock) { targetSpeechStarted }

    /**
     * 지금 이 순간 원음에서 등록된 내 목소리가 이어지고 있는지 여부.
     * VoxLock이 말하는 도중 내 목소리를 지워 분리 출력만 무음이 됐을 때, 분리 출력 VAD가
     * 조기 자동 중지를 걸지 못하게 막고 원음 스트리밍으로 즉시 전환하는 데 쓴다(2026-11-28 로그).
     */
    fun isTargetActiveNow(nowSample: Int): Boolean = synchronized(lock) {
        targetSpeechStarted &&
            nowSample - rawLatestActiveSample < SpeakerGate.STOP_PAUSE_SAMPLES &&
            nowSample - lastTargetSpeechSample < hangoverSamples
    }

    /** [StreamingWhisper]가 녹음 중 조각 `[from, to)`를 읽을 때 확정된 비타겟 세그먼트만 0 마스킹한다. */
    fun maskStreamingChunk(from: Int, to: Int, rawSlice: ShortArray): ShortArray {
        val snapshotProbs: FloatArray
        val snapshotScored: List<SpeakerGate.ScoredWindow>
        val curTotal: Int
        synchronized(lock) {
            snapshotProbs = probs.toFloatArray()
            snapshotScored = scored.toList()
            curTotal = totalSamples
        }
        if (rawSlice.isEmpty() || snapshotScored.isEmpty()) return rawSlice

        val segments = SpeakerGate.activeSegments(snapshotProbs, curTotal)
        // 아직 끝나가는 진행 중인 마지막 세그먼트는 미확정 창이 있을 수 있으므로
        // 확실히 끝난 세그먼트(curTotal과 0.35s 이상 떨어진 세그먼트) 중 비타겟 세그먼트만 마스킹한다.
        val settledMargin = (0.35 * SpeakerGate.SR).toInt()
        val zeroRanges = ArrayList<SpeakerGate.Segment>()
        for (seg in segments) {
            if (seg.end > curTotal - settledMargin) continue
            if (seg.end <= from || seg.start >= to) continue
            val wins = snapshotScored.filter { w -> w.end > seg.start && w.start < seg.end }
            if (wins.isNotEmpty() && wins.none { it.similarity >= threshold }) {
                zeroRanges.add(SpeakerGate.Segment(max(seg.start, from), min(seg.end, to)))
            }
        }
        if (zeroRanges.isEmpty()) return rawSlice

        val copy = rawSlice.copyOf()
        for (zr in zeroRanges) {
            val rs = zr.start - from
            val re = zr.end - from
            for (i in rs until re) copy[i] = 0
        }
        return copy
    }

    private fun runBackgroundScoring() {
        try {
            while (!stopped) {
                val task = nextWindowTask() ?: break
                val sim = runCatching { scorer(task.start, task.end - task.start) }.getOrDefault(-1f)
                synchronized(lock) {
                    scored.add(SpeakerGate.ScoredWindow(task.start, task.end, sim))
                    scoredUpToSample = max(scoredUpToSample, task.end)
                    if (sim >= threshold) {
                        if (!targetSpeechStarted) {
                            targetSpeechStarted = true
                            firstTargetSpeechSample = max(0, task.start - SpeakerGate.PRE_PAD_SAMPLES)
                        }
                        val effectiveStart = max(task.start, coveredTargetEnd)
                        if (task.end > effectiveStart) {
                            targetSpeechSamples += (task.end - effectiveStart)
                            coveredTargetEnd = task.end
                        }
                        lastTargetSpeechSample = max(lastTargetSpeechSample, task.end)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("SpeakerGate", "background scoring error", t)
        } finally {
            synchronized(lock) { scoringInFlight = false }
        }
    }

    private fun nextWindowTask(): SpeakerGate.Segment? = synchronized(lock) {
        if (stopped) return null
        val nSamples = totalSamples
        val segs = SpeakerGate.activeSegments(probs.toFloatArray(), nSamples)
        for (seg in segs) {
            val len = seg.end - seg.start
            if (len < SpeakerGate.MIN_SEG_SAMPLES) continue

            // 진행 중 아직 0.8s에 도달하지 않은 마지막 세그먼트는 녹음 중에는 기다렸다가 채점
            val segSettled = seg.end <= nSamples - SpeakerGate.SEG_PAD_SAMPLES
            if (len <= SpeakerGate.WIN_SAMPLES) {
                if (segSettled && !alreadyScoredLocked(seg.start, seg.end)) {
                    return SpeakerGate.Segment(seg.start, seg.end)
                }
                continue
            }

            var pos = seg.start
            while (pos + SpeakerGate.WIN_SAMPLES <= seg.end) {
                val wEnd = pos + SpeakerGate.WIN_SAMPLES
                if (!alreadyScoredLocked(pos, wEnd)) {
                    return SpeakerGate.Segment(pos, wEnd)
                }
                pos += SpeakerGate.HOP_SAMPLES
            }
            val coveredEnd = pos - SpeakerGate.HOP_SAMPLES + SpeakerGate.WIN_SAMPLES
            if (segSettled && seg.end - coveredEnd > SpeakerGate.MIN_SEG_SAMPLES / 2) {
                val tailStart = max(seg.start, seg.end - SpeakerGate.WIN_SAMPLES)
                if (!alreadyScoredLocked(tailStart, seg.end)) {
                    return SpeakerGate.Segment(tailStart, seg.end)
                }
            }
        }
        null
    }

    private fun alreadyScoredLocked(s: Int, e: Int): Boolean =
        scored.any { it.start == s && it.end == e }

    /** Stops the scorer without applying the gate: a take that is cancelled, too short, or not transcribed through it. */
    fun discard() {
        stopped = true
        executor.shutdownNow()
    }

    /**
     * 녹음 종료 시 백그라운드 채점기를 멈추고 남은 미채점 창을 모두 마무리한 뒤 마스킹/트리밍된 결과를 반환한다.
     */
    fun finishAndApply(rawFull: ShortArray): SpeakerGate.GateResult {
        stopped = true
        executor.shutdown()
        runCatching { executor.awaitTermination(1500, TimeUnit.MILLISECONDS) }

        val finalProbs: FloatArray
        val existingScored: ArrayList<SpeakerGate.ScoredWindow>
        synchronized(lock) {
            finalProbs = probs.toFloatArray()
            existingScored = ArrayList(scored)
        }

        // 아직 채점되지 않은 끝부분 세그먼트/꼬리 창을 빠짐없이 채점
        val segs = SpeakerGate.activeSegments(finalProbs, rawFull.size)
        for (seg in segs) {
            val len = seg.end - seg.start
            if (len < SpeakerGate.MIN_SEG_SAMPLES) continue
            val expected = SpeakerGate.scoreSegment(seg.start, seg.end) { ws, wlen ->
                val found = existingScored.firstOrNull { it.start == ws && it.end == ws + wlen }
                if (found != null) found.similarity else runCatching { scorer(ws, wlen) }.getOrDefault(-1f)
            }
            for (w in expected) {
                if (existingScored.none { it.start == w.start && it.end == w.end }) {
                    existingScored.add(w)
                    if (w.similarity >= threshold) synchronized(lock) { targetSpeechStarted = true }
                }
            }
        }

        val res = SpeakerGate.applyGateMask(rawFull, finalProbs, existingScored, threshold)
        // 게이트가 받아쓰기에서 지운 말소리 및 세그먼트별 유사도 분포(진단용).
        val keepStr = if (res.keepRanges.isEmpty()) "none" else res.keepRanges.take(8).joinToString(" ") {
            "%.1f-%.1fs".format(it.start / sr, it.end / sr)
        }
        Log.i(
            "SpeakerGate",
            "[$label] kept %.1fs of %.1fs of speech (keep: %s); segs: %s".format(
                res.keepRanges.sumOf { it.end - it.start } / sr,
                segs.sumOf { it.end - it.start } / sr,
                keepStr,
                SpeakerGate.formatSegmentScores(segs, existingScored, threshold),
            ),
        )
        if (res.samples.isEmpty() || res.keepRanges.isEmpty()) {
            // VAD는 있으나 타겟 창이 끝내 나오지 않았을 때: 무음이 너무 짧았거나 속삭임에 가까웠다면 원본 유지가 안전할지 판단
            return res
        }
        val start = res.trimStart.coerceIn(0, res.samples.size)
        val end = res.trimEnd.coerceIn(start, res.samples.size)
        return if (end - start >= SpeakerGate.SR / 4) {
            res.copy(samples = res.samples.copyOfRange(start, end))
        } else {
            res
        }
    }
}
