#!/usr/bin/env python3
"""Generate audio clips for the overlapping-speech & TV noise benchmark.

Creates enrollment samples and 20 target clips (10 code-switched Edge TTS + 10 real Korean
speakers from kresnik/zeroth_korean test) across 5 conditions:
  clean      target sentences with 0.5s mid-pause and 2.5s trailing silence
  pause_tv   single distractor Korean speech (-3 dB vs target) inserted during mid-pause (2.5s)
             and trailing wait (3.5s) to test interruption + Auto-stop
  overlap10  single distractor Korean speech mixed over the entire utterance at 10 dB SNR (+0.5s tail)
  overlap5   same at 5 dB SNR
  overlap0   same at 0 dB SNR

Usage:
  voice_bench_data.py <out dir> [zeroth_test.parquet]
"""
import asyncio
import io
import json
import os
import random
import subprocess
import sys
import urllib.request

import numpy as np
import pyarrow.parquet as pq
import soundfile as sf

OUT = sys.argv[1]
PARQUET_ARG = sys.argv[2] if len(sys.argv) > 2 else os.path.join(OUT, "zeroth_test.parquet")
os.makedirs(OUT, exist_ok=True)

SR = 16_000
PARQUET_URL = "https://huggingface.co/api/datasets/kresnik/zeroth_korean/parquet/default/test/0.parquet"
rng = random.Random(11)

SENTENCES = [
    "GitLab에 MR 올리고, worktree 정리한 다음 Claude Code로 테스트 돌려줘.",
    "Kubernetes pod가 계속 CrashLoopBackOff 나는데 로그 좀 확인해줄래?",
    "ClickHouse 쿼리가 느린데 materialized view로 바꾸면 얼마나 빨라질지 비교해줘.",
    "이번 PR은 리뷰 코멘트 반영해서 force push 하지 말고 새 커밋으로 올려줘.",
    "README에 설치 방법이랑 환경 변수 설명 추가해줘.",
    "API 응답이 너무 느리니까 Redis 캐시를 앞에 두는 방향으로 설계해봐.",
    "Docker 이미지 크기가 2기가가 넘는데 multi-stage build로 줄여볼 수 있을까?",
    "JVM 힙 메모리가 계속 올라가는데 메모리 릭인지 heap dump 떠서 분석해봐.",
    "OpenTelemetry collector 설정에서 sampling 비율을 10퍼센트로 낮춰줘.",
    "테스트 커버리지 리포트를 뽑아서 80퍼센트 안 되는 모듈만 알려줘.",
    "nginx 설정에서 타임아웃을 60초로 늘리고 재시작해줘.",
    "Gradle 빌드가 너무 오래 걸리는데 캐시 설정 좀 손봐줘.",
    "TypeScript 타입 에러가 스무 개쯤 나오는데 하나씩 고쳐줘.",
    "브랜치 이름은 feature 슬래시 settings UI로 해서 새로 만들어줘.",
    "로그인 페이지에서 OAuth 콜백이 404가 나는데 라우팅 설정을 확인해줘.",
    "unit test가 CI에서만 실패하는데 로컬에서는 통과하거든, 타임존 문제인지 봐줘.",
    "이 함수 이름이 너무 길어서 좀 더 짧고 명확하게 리팩토링해줘.",
    "지금 열려있는 이슈 중에서 버그 라벨 붙은 것만 정리해서 보여줘.",
    "Slack 알림 메시지 포맷을 좀 더 읽기 쉽게 바꿔줄래?",
    "어제 머지한 커밋 때문에 대시보드 차트가 안 그려지는 것 같아, revert 해줘.",
    "이 스크립트를 cron으로 매일 새벽 세 시에 돌게 등록해줘.",
    "앱에서 플로팅 버튼이 키보드를 가리는데 위치를 조금 올려줘.",
    "배포 서버 12번에서 nats-server CPU 사용률이 왜 높은지 원인 파악해봐.",
    "이건 급한 거 아니니까 내일 오전에 다시 확인하자.",
]


def load_mp3(path: str) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-i", path, "-f", "s16le", "-ac", "1", "-ar", str(SR), "-"],
        capture_output=True,
        check=True,
    ).stdout
    return np.frombuffer(raw, np.int16).astype(np.float32) / 32768.0


async def tts_batch(jobs: list[tuple[str, str, str]]) -> None:
    import edge_tts

    for text, voice, path in jobs:
        await edge_tts.Communicate(text, voice).save(path)


def trim_edges(x: np.ndarray, pad_sec: float = 0.08, thr_ratio: float = 0.02) -> np.ndarray:
    """Trim leading/trailing near-silence using 20ms frame RMS."""
    frame = 320
    n_frames = len(x) // frame
    if n_frames == 0:
        return x
    rms_frames = np.array([np.sqrt(np.mean(x[i * frame : (i + 1) * frame] ** 2)) for i in range(n_frames)])
    thr = max(float(rms_frames.max()) * thr_ratio, 1e-4)
    active = np.where(rms_frames >= thr)[0]
    if len(active) == 0:
        return x
    pad = int(pad_sec * SR)
    start = max(0, int(active[0]) * frame - pad)
    end = min(len(x), int(active[-1] + 1) * frame + pad)
    return x[start:end].astype(np.float32)


def active_rms(x: np.ndarray, thr_ratio: float = 0.05) -> float:
    """RMS over non-silent 20ms frames so pauses don't skew SNR."""
    frame = 320
    n_frames = len(x) // frame
    if n_frames == 0:
        return float(np.sqrt(np.mean(x**2)) + 1e-12)
    e = np.array([np.sqrt(np.mean(x[i * frame : (i + 1) * frame] ** 2)) for i in range(n_frames)])
    thr = max(float(e.max()) * thr_ratio, 1e-4)
    act = e[e >= thr]
    if len(act) == 0:
        return float(np.sqrt(np.mean(x**2)) + 1e-12)
    return float(np.sqrt(np.mean(act**2)) + 1e-12)


def norm_peak(x: np.ndarray, peak: float = 0.5) -> np.ndarray:
    m = float(np.abs(x).max())
    return (x / max(m, 1e-6) * peak).astype(np.float32)


def apply_fade(x: np.ndarray, ms: int = 10) -> np.ndarray:
    n = min(len(x) // 2, int(SR * ms / 1000))
    if n <= 0:
        return x
    y = x.copy()
    ramp = np.linspace(0.0, 1.0, n, dtype=np.float32)
    y[:n] *= ramp
    y[-n:] *= ramp[::-1]
    return y


def save_wav(path: str, x: np.ndarray) -> None:
    sf.write(path, np.clip(x, -1.0, 1.0).astype(np.float32), SR, subtype="PCM_16")


def build_tts_base() -> tuple[list[dict], dict[str, str]]:
    order = SENTENCES[:]
    rng.shuffle(order)
    # 20 sentences -> 10 clips (5 female, 5 male), 4 sentences -> 2 per voice for enrollment
    voices = [("f", "ko-KR-SunHiNeural"), ("m", "ko-KR-InJoonNeural")]
    f_sents = [order[i] for i in range(0, 24, 2)]  # 12
    m_sents = [order[i] for i in range(1, 24, 2)]  # 12
    by_voice = {"f": f_sents, "m": m_sents}

    tmp_jobs = []
    for vk, vname in voices:
        for idx, sent in enumerate(by_voice[vk]):
            tmp_jobs.append((sent, vname, os.path.join(OUT, f"_tts_{vk}_{idx:02d}.mp3")))
    asyncio.run(tts_batch(tmp_jobs))

    enroll_paths: dict[str, str] = {}
    base: list[dict] = []

    for vk, _ in voices:
        e_parts = []
        for idx in range(2):
            mp3 = os.path.join(OUT, f"_tts_{vk}_{idx:02d}.mp3")
            wav = trim_edges(load_mp3(mp3))
            e_parts.append(wav)
            e_parts.append(np.zeros(int(0.3 * SR), dtype=np.float32))
        e_audio = norm_peak(np.concatenate(e_parts))
        ep = os.path.join(OUT, f"enroll_cs_{vk}.wav")
        save_wav(ep, e_audio)
        enroll_paths[f"cs_{vk}"] = os.path.basename(ep)

    for i in range(10):
        vk = "f" if i % 2 == 0 else "m"
        j = 2 + (i // 2) * 2
        s1 = trim_edges(load_mp3(os.path.join(OUT, f"_tts_{vk}_{j:02d}.mp3")))
        s2 = trim_edges(load_mp3(os.path.join(OUT, f"_tts_{vk}_{j + 1:02d}.mp3")))
        ref = f"{by_voice[vk][j]} {by_voice[vk][j + 1]}"
        base.append({"name": f"cs{i:02d}", "src": "cs", "enroll": enroll_paths[f"cs_{vk}"], "s1": s1, "s2": s2, "ref": ref})

    for _, _, mp3 in tmp_jobs:
        if os.path.exists(mp3):
            os.remove(mp3)

    return base, enroll_paths


def build_zeroth_base() -> tuple[list[dict], list[tuple[np.ndarray, str]]]:
    if not os.path.exists(PARQUET_ARG):
        print(f"Downloading zeroth_korean test parquet to {PARQUET_ARG}...")
        urllib.request.urlretrieve(PARQUET_URL, PARQUET_ARG)
    table = pq.read_table(PARQUET_ARG)
    by_spk: dict[int, list[tuple[np.ndarray, str]]] = {}
    for row in range(table.num_rows):
        spk = int(table["speaker_id"][row].as_py())
        text = str(table["text"][row].as_py()).strip()
        raw_bytes = table["audio"][row]["bytes"].as_py()
        audio, sr = sf.read(io.BytesIO(raw_bytes), dtype="float32")
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        if sr != SR:
            import soxr
            audio = soxr.resample(audio, sr, SR)
        audio = trim_edges(audio)
        if 2.0 <= len(audio) / SR <= 12.0:
            by_spk.setdefault(spk, []).append((audio, text))

    # 5 target speakers with >= 6 utterances, 5 remaining speakers as distractor pool
    spks_sorted = sorted(by_spk.keys(), key=lambda k: len(by_spk[k]), reverse=True)
    target_spks = spks_sorted[:5]
    bg_spks = spks_sorted[5:]

    base: list[dict] = []
    clip_idx = 0
    for spk in target_spks:
        utts = by_spk[spk][:]
        rng.shuffle(utts)
        # First 2 utterances (~8-14s) for enrollment
        e_parts = []
        for a, _ in utts[:2]:
            e_parts.append(a)
            e_parts.append(np.zeros(int(0.3 * SR), dtype=np.float32))
        ep = os.path.join(OUT, f"enroll_zk_{spk}.wav")
        save_wav(ep, norm_peak(np.concatenate(e_parts)))

        # Next 4 utterances -> 2 clips of 2 sentences
        for pair_i in range(2):
            a1, t1 = utts[2 + pair_i * 2]
            a2, t2 = utts[2 + pair_i * 2 + 1]
            base.append(
                {
                    "name": f"zk{clip_idx:02d}",
                    "src": "zk",
                    "enroll": os.path.basename(ep),
                    "s1": a1,
                    "s2": a2,
                    "ref": f"{t1} {t2}",
                }
            )
            clip_idx += 1

    bg_pool: list[tuple[np.ndarray, str]] = []
    for spk in bg_spks:
        for a, t in by_spk[spk]:
            # Tighten internal pauses for dense distractor TV speech
            bg_pool.append((norm_peak(a), t))
    rng.shuffle(bg_pool)
    return base, bg_pool


def pick_distractor(bg_pool: list[tuple[np.ndarray, str]], need_samples: int, idx_start: int) -> tuple[np.ndarray, str, int]:
    """Slice continuous single-speaker-at-a-time distractor audio of length need_samples."""
    chunks = []
    texts = []
    cur = 0
    idx = idx_start
    while cur < need_samples:
        a, t = bg_pool[idx % len(bg_pool)]
        idx += 1
        chunks.append(a)
        texts.append(t)
        cur += len(a)
    cat = np.concatenate(chunks)[:need_samples]
    return apply_fade(cat, ms=10), " | ".join(texts), idx


def main() -> None:
    tts_base, _ = build_tts_base()
    zk_base, bg_pool = build_zeroth_base()
    all_base = tts_base + zk_base

    clips = []
    bg_cursor = 0
    for item in all_base:
        name = item["name"]
        # Normalize each sentence so loudness matches across s1 and s2
        s1 = norm_peak(item["s1"], peak=0.5)
        s2 = norm_peak(item["s2"], peak=0.5)
        pause_clean = np.zeros(int(0.5 * SR), dtype=np.float32)
        tail_25 = np.zeros(int(2.5 * SR), dtype=np.float32)
        tail_05 = np.zeros(int(0.5 * SR), dtype=np.float32)

        utter_clean = np.concatenate([s1, pause_clean, s2])
        tgt_rms = active_rms(utter_clean)

        # 1. clean
        clean = np.concatenate([utter_clean, tail_25])
        clean_end_sec = len(utter_clean) / SR

        # 2. pause_tv: distractor at -3 dB vs target RMS during 1.1s mid-pause and 3.5s tail
        d_mid, bg1, bg_cursor = pick_distractor(bg_pool, int(1.1 * SR), bg_cursor)
        d_tail, bg2, bg_cursor = pick_distractor(bg_pool, int(3.5 * SR), bg_cursor)
        tv_scale = 10 ** (-3.0 / 20.0)
        d_mid = d_mid * (tgt_rms * tv_scale / active_rms(d_mid))
        d_tail = d_tail * (tgt_rms * tv_scale / active_rms(d_tail))
        pause_tv = np.concatenate([s1, d_mid, s2, d_tail])
        pause_tv_end_sec = (len(s1) + len(d_mid) + len(s2)) / SR
        pause_tv_bg_ref = f"{bg1} || {bg2}"

        conds = [
            ("clean", clean, clean_end_sec, ""),
            ("pause_tv", pause_tv, pause_tv_end_sec, pause_tv_bg_ref),
        ]

        # 3-5. overlap10, overlap5, overlap0
        for snr_db in (10, 5, 0):
            d_ov, bg_ov, bg_cursor = pick_distractor(bg_pool, len(utter_clean), bg_cursor)
            d_ov = d_ov * (tgt_rms / active_rms(d_ov)) / (10 ** (snr_db / 20.0))
            ov_mix = np.concatenate([utter_clean + d_ov, tail_05])
            conds.append((f"overlap{snr_db}", ov_mix, clean_end_sec, bg_ov))

        for cond_name, wav, end_sec, bg_ref in conds:
            clip_name = f"{name}_{cond_name}"
            save_wav(os.path.join(OUT, f"{clip_name}.wav"), wav)
            clips.append(
                {
                    "name": clip_name,
                    "base": name,
                    "cond": cond_name,
                    "src": item["src"],
                    "enroll": item["enroll"],
                    "ref": item["ref"],
                    "bg_ref": bg_ref,
                    "target_end_sec": round(end_sec, 3),
                    "sec": round(len(wav) / SR, 3),
                }
            )

    with open(os.path.join(OUT, "clips.json"), "w", encoding="utf-8") as f:
        json.dump(clips, f, ensure_ascii=False, indent=1)
    print(f"Generated {len(clips)} clips in {OUT} ({len(all_base)} base x 5 conds)")


if __name__ == "__main__":
    main()
