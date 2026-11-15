#!/usr/bin/env python3
"""Run preprocessing pipelines (raw, speaker-embedding gating, VoxLock) and Whisper decoding.

Reproduces AudioRecorder.kt's Silero VAD v5 state machine (64-sample context, start=0.5,
end=0.35, min_speech=0.4s, hangover=1.6s, pre_pad=0.2s, post_pad=0.3s) and decodes with
whisper-cli (v1.7.4) in parallel across 4 workers (-t 4 each).

Usage:
  voice_bench_run.py <audio_dir> <models_dir> <whisper_cli> <vad_onnx> <work_dir> <out_dir>
"""
import argparse
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor
import hashlib
import json
import multiprocessing as mp
import os
import re
import subprocess
import time
import unicodedata

import numpy as np
import onnxruntime as ort
import sherpa_onnx
import soundfile as sf
import voxlock

SR = 16_000
STYLE = "GitLab에 MR 올리고, worktree 정리한 다음 Claude Code로 테스트 돌려줘."

# AudioRecorder.kt / SileroVad.kt constants
VAD_CHUNK = 512
VAD_CONTEXT = 64
SPEECH_START_PROB = 0.5
SPEECH_END_PROB = 0.35
MIN_SPEECH_SAMPLES = int(0.4 * SR)
HANGOVER_SAMPLES = int(1.6 * SR)
PRE_PAD_SAMPLES = int(0.2 * SR)
POST_PAD_SAMPLES = int(0.3 * SR)

# Process-local worker globals (populated by _init_voxlock_worker)
_W_VAD = None
_W_VL = None
_W_AUDIO_DIR = None


class SileroVad:
    """Silero VAD v5 ONNX runner matching SileroVad.kt (requires 64-sample overlap context)."""

    def __init__(self, onnx_path: str):
        so = ort.SessionOptions()
        so.intra_op_num_threads = 1
        so.inter_op_num_threads = 1
        self.sess = ort.InferenceSession(onnx_path, so, providers=["CPUExecutionProvider"])
        self.sr_arr = np.array(SR, dtype=np.int64)
        self.reset()

    def reset(self) -> None:
        self.state = np.zeros((2, 1, 128), dtype=np.float32)
        self.context = np.zeros(VAD_CONTEXT, dtype=np.float32)

    def frame_probs(self, audio: np.ndarray) -> np.ndarray:
        self.reset()
        n_chunks = len(audio) // VAD_CHUNK
        probs = np.zeros(n_chunks, dtype=np.float32)
        buf = np.zeros((1, VAD_CONTEXT + VAD_CHUNK), dtype=np.float32)
        for i in range(n_chunks):
            chunk = audio[i * VAD_CHUNK : (i + 1) * VAD_CHUNK]
            buf[0, :VAD_CONTEXT] = self.context
            buf[0, VAD_CONTEXT:] = chunk
            out, self.state = self.sess.run(None, {"input": buf, "state": self.state, "sr": self.sr_arr})
            probs[i] = float(out[0][0])
            self.context[:] = chunk[-VAD_CONTEXT:]
        return probs


def simulate_recorder(
    audio: np.ndarray,
    probs: np.ndarray,
    target_mask: np.ndarray | None = None,
) -> tuple[np.ndarray, bool, float]:
    """Simulate AudioRecorder.kt's VAD auto-stop and speech trimming."""
    speech_started = False
    first_speech = 0
    last_speech = 0
    speech_samples = 0
    auto_stopped = False
    stop_sample = len(audio)

    for i, raw_p in enumerate(probs):
        s0 = i * VAD_CHUNK
        s1 = (i + 1) * VAD_CHUNK
        p = float(raw_p)
        if target_mask is not None and not np.any(target_mask[s0:s1]):
            p = 0.0

        if p >= SPEECH_START_PROB:
            if not speech_started:
                speech_started = True
                first_speech = max(0, s1 - VAD_CHUNK - PRE_PAD_SAMPLES)
            last_speech = s1
            speech_samples += VAD_CHUNK

        if speech_samples >= MIN_SPEECH_SAMPLES and not auto_stopped and p < SPEECH_END_PROB:
            if s1 - last_speech >= HANGOVER_SAMPLES:
                auto_stopped = True
                stop_sample = s1
                break

    if stop_sample < SR // 4:
        return np.zeros(0, dtype=np.float32), auto_stopped, stop_sample / SR

    out_audio = audio[:stop_sample].copy()
    if target_mask is not None:
        out_audio[~target_mask[:stop_sample]] = 0.0

    if speech_started:
        start = max(0, min(first_speech, stop_sample))
        end = min(stop_sample, last_speech + POST_PAD_SAMPLES)
        if end - start >= SR // 4:
            return out_audio[start:end], auto_stopped, stop_sample / SR

    return out_audio, auto_stopped, stop_sample / SR


def active_segments(probs: np.ndarray, n_samples: int) -> list[tuple[int, int]]:
    """Merge VAD active chunks (prob >= 0.35) separated by <= 0.3s into speech intervals."""
    flags = probs >= SPEECH_END_PROB
    segs: list[tuple[int, int]] = []
    in_seg = False
    start = 0
    for i, f in enumerate(flags):
        if f and not in_seg:
            in_seg = True
            start = i * VAD_CHUNK
        elif not f and in_seg:
            in_seg = False
            segs.append((start, i * VAD_CHUNK))
    if in_seg:
        segs.append((start, len(flags) * VAD_CHUNK))
    if not segs:
        return []

    max_gap = int(0.30 * SR)
    pad = int(0.15 * SR)
    merged: list[tuple[int, int]] = []
    cs, ce = max(0, segs[0][0] - pad), min(n_samples, segs[0][1] + pad)
    for s, e in segs[1:]:
        s2, e2 = max(0, s - pad), min(n_samples, e + pad)
        if s2 - ce <= max_gap:
            ce = max(ce, e2)
        else:
            merged.append((cs, ce))
            cs, ce = s2, e2
    merged.append((cs, ce))
    return merged


def compute_spk_sims(
    audio: np.ndarray,
    probs: np.ndarray,
    ext: sherpa_onnx.SpeakerEmbeddingExtractor,
    enroll_vec: np.ndarray,
) -> list[tuple[int, int, float]]:
    """Scan VAD segments with a 0.8s window / 0.25s hop and score cosine similarity."""
    win = int(0.8 * SR)
    hop = int(0.25 * SR)
    min_seg = int(0.25 * SR)
    scored: list[tuple[int, int, float]] = []

    def score_slice(a0: int, a1: int) -> float:
        seg = audio[a0:a1]
        if float(np.sqrt(np.mean(seg**2))) < 1e-4:
            return -1.0
        st = ext.create_stream()
        st.accept_waveform(SR, seg.astype(np.float32))
        st.input_finished()
        if not ext.is_ready(st):
            return -1.0
        v = np.array(ext.compute(st), dtype=np.float32)
        nrm = float(np.linalg.norm(v))
        if nrm < 1e-8:
            return -1.0
        return float(np.dot(enroll_vec, v / nrm))

    for seg_s, seg_e in active_segments(probs, len(audio)):
        length = seg_e - seg_s
        if length < min_seg:
            continue
        if length <= win:
            scored.append((seg_s, seg_e, score_slice(seg_s, seg_e)))
            continue
        pos = seg_s
        while pos + win <= seg_e:
            scored.append((pos, pos + win, score_slice(pos, pos + win)))
            pos += hop
        if seg_e - (pos - hop + win) > min_seg // 2:
            tail_s = max(seg_s, seg_e - win)
            scored.append((tail_s, seg_e, score_slice(tail_s, seg_e)))

    return scored


def _init_voxlock_worker(vad_onnx: str, audio_dir: str) -> None:
    global _W_VAD, _W_VL, _W_AUDIO_DIR
    _W_VAD = SileroVad(vad_onnx)
    _W_VL = voxlock.VoxLock(precision="int8", threads=1)
    _W_AUDIO_DIR = audio_dir


def _run_voxlock_clip(task: tuple[dict, str, str]) -> tuple[str, dict]:
    c, pipe, out_wav = task
    name = c["name"]
    audio, _ = sf.read(os.path.join(_W_AUDIO_DIR, f"{name}.wav"), dtype="float32")
    t0 = time.perf_counter()

    if pipe == "voxlock_zero":
        st = _W_VL.live(sec=3.0)
    else:
        ea, _ = sf.read(os.path.join(_W_AUDIO_DIR, c["enroll"]), dtype="float32")
        st = _W_VL.stream(ea)

    chunks_out = []
    step = 640  # 40ms
    for pos in range(0, len(audio), step):
        y_c = st.push(audio[pos : pos + step])
        if len(y_c):
            chunks_out.append(y_c)
    y_f = st.flush()
    if len(y_f):
        chunks_out.append(y_f)

    isolated = np.concatenate(chunks_out) if chunks_out else np.zeros(0, dtype=np.float32)
    probs = _W_VAD.frame_probs(isolated)
    trimmed, auto_stopped, stop_sec = simulate_recorder(isolated, probs, None)
    sec_prep = time.perf_counter() - t0

    if len(trimmed) < SR // 4:
        sf.write(out_wav, np.zeros(int(0.3 * SR), dtype=np.float32), SR, subtype="PCM_16")
    else:
        sf.write(out_wav, np.clip(trimmed, -1.0, 1.0).astype(np.float32), SR, subtype="PCM_16")

    auto_stop_ok = bool(auto_stopped and (c["target_end_sec"] <= stop_sec <= c["target_end_sec"] + 2.6))
    return name, {
        "wav": out_wav,
        "trimmed_sec": round(len(trimmed) / SR, 3),
        "sec_prep": round(sec_prep, 4),
        "auto_stopped": bool(auto_stopped),
        "stop_sec": round(stop_sec, 3),
        "auto_stop_ok": auto_stop_ok,
    }


def prep_all_pipelines(
    clips: list[dict],
    audio_dir: str,
    models_dir: str,
    vad_onnx: str,
    work_dir: str,
    pipelines: list[str],
    prep_workers: int = 8,
) -> dict[str, dict[str, dict]]:
    """Generate and cache preprocessed WAVs; each clip is measured on 1 thread."""
    vad = SileroVad(vad_onnx)
    os.makedirs(work_dir, exist_ok=True)
    meta_out: dict[str, dict[str, dict]] = {}

    enroll_audio: dict[str, np.ndarray] = {}
    for c in clips:
        ep = c["enroll"]
        if ep not in enroll_audio:
            ea, _ = sf.read(os.path.join(audio_dir, ep), dtype="float32")
            enroll_audio[ep] = ea

    spk_pipes = [p for p in pipelines if p.startswith("spk_gate_")]
    ext = None
    enroll_vecs: dict[str, np.ndarray] = {}

    for pipe in pipelines:
        pdir = os.path.join(work_dir, pipe)
        os.makedirs(pdir, exist_ok=True)
        meta_path = os.path.join(work_dir, f"{pipe}_meta.json")
        if os.path.exists(meta_path):
            cached = json.load(open(meta_path, encoding="utf-8"))
            if len(cached) == len(clips):
                print(f"[prep] reusing cached {pipe} ({len(cached)} clips)")
                meta_out[pipe] = cached
                continue

        t_pipe = time.perf_counter()
        if pipe in ("voxlock_zero", "voxlock_enroll"):
            print(f"[prep] running {pipe} on {len(clips)} clips ({prep_workers} procs x 1T)...")
            tasks = [(c, pipe, os.path.join(pdir, f"{c['name']}.wav")) for c in clips]
            ctx = mp.get_context("spawn")
            with ProcessPoolExecutor(
                max_workers=prep_workers,
                mp_context=ctx,
                initializer=_init_voxlock_worker,
                initargs=(vad_onnx, audio_dir),
            ) as ex:
                pipe_meta = dict(ex.map(_run_voxlock_clip, tasks))
        else:
            if pipe.startswith("spk_gate_") and ext is None:
                spk_model = os.path.join(models_dir, "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx")
                cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
                    model=spk_model, num_threads=1, debug=False, provider="cpu"
                )
                ext = sherpa_onnx.SpeakerEmbeddingExtractor(cfg)
                for ep, ea in enroll_audio.items():
                    st = ext.create_stream()
                    st.accept_waveform(SR, ea.astype(np.float32))
                    st.input_finished()
                    v = np.array(ext.compute(st), dtype=np.float32)
                    enroll_vecs[ep] = v / max(float(np.linalg.norm(v)), 1e-8)

            print(f"[prep] running {pipe} on {len(clips)} clips (1 thread)...")
            pipe_meta = {}
            for c in clips:
                name = c["name"]
                audio, _ = sf.read(os.path.join(audio_dir, f"{name}.wav"), dtype="float32")
                t0 = time.perf_counter()
                if pipe == "raw":
                    probs = vad.frame_probs(audio)
                    trimmed, auto_stopped, stop_sec = simulate_recorder(audio, probs, None)
                elif pipe.startswith("spk_gate_"):
                    thr = float(pipe.split("_")[-1])
                    probs = vad.frame_probs(audio)
                    scored = compute_spk_sims(audio, probs, ext, enroll_vecs[c["enroll"]])
                    mask = np.zeros(len(audio), dtype=bool)
                    pad = int(0.10 * SR)
                    for s0, s1, sim in scored:
                        if sim >= thr:
                            mask[max(0, s0 - pad) : min(len(audio), s1 + pad)] = True
                    trimmed, auto_stopped, stop_sec = simulate_recorder(audio, probs, mask)
                else:
                    raise ValueError(f"Unknown pipeline: {pipe}")
                sec_prep = time.perf_counter() - t0

                out_wav = os.path.join(pdir, f"{name}.wav")
                if len(trimmed) < SR // 4:
                    sf.write(out_wav, np.zeros(int(0.3 * SR), dtype=np.float32), SR, subtype="PCM_16")
                else:
                    sf.write(out_wav, np.clip(trimmed, -1.0, 1.0).astype(np.float32), SR, subtype="PCM_16")

                auto_stop_ok = bool(auto_stopped and (c["target_end_sec"] <= stop_sec <= c["target_end_sec"] + 2.6))
                pipe_meta[name] = {
                    "wav": out_wav,
                    "trimmed_sec": round(len(trimmed) / SR, 3),
                    "sec_prep": round(sec_prep, 4),
                    "auto_stopped": bool(auto_stopped),
                    "stop_sec": round(stop_sec, 3),
                    "auto_stop_ok": auto_stop_ok,
                }

        tot_sec = sum(c["sec"] for c in clips)
        tot_prep = sum(v["sec_prep"] for v in pipe_meta.values())
        print(f"[prep] {pipe} done in {time.perf_counter() - t_pipe:.1f}s (1T RTF={tot_prep / tot_sec:.3f})")
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(pipe_meta, f, ensure_ascii=False, indent=1)
        meta_out[pipe] = pipe_meta

    return meta_out


def decode_one(whisper_cli: str, model_path: str, wav_path: str, threads: int = 4) -> tuple[str, float]:
    info = sf.info(wav_path)
    n_samples = info.frames
    if n_samples < int(0.35 * SR):
        return "", 0.0
    ctx = (n_samples + 319) // 320 + 16
    ctx = min(1500, max(128, (ctx + 63) // 64 * 64))
    out = subprocess.run(
        [
            whisper_cli,
            "-m",
            model_path,
            "-f",
            wav_path,
            "-l",
            "ko",
            "-bs",
            "5",
            "-t",
            str(threads),
            "-ac",
            str(ctx),
            "--prompt",
            STYLE,
            "-nt",
            "-sns",
        ],
        capture_output=True,
        check=True,
    )
    err = out.stderr.decode("utf-8", "replace")
    tot_m = re.search(r"total time\s*=\s*([\d.]+)\s*ms", err)
    load_m = re.search(r"load time\s*=\s*([\d.]+)\s*ms", err)
    dec_s = (float(tot_m.group(1)) - float(load_m.group(1))) / 1000.0 if (tot_m and load_m) else 0.0
    txt = " ".join(out.stdout.decode("utf-8", "replace").split())
    return txt, dec_s


def wav_sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def decode_suite(
    clips: list[dict],
    prep_meta: dict[str, dict[str, dict]],
    whisper_cli: str,
    model_key: str,
    model_path: str,
    pipelines: list[str],
    out_dir: str,
    workers: int = 4,
    threads: int = 4,
) -> dict[str, list[dict]]:
    os.makedirs(out_dir, exist_ok=True)
    all_rows: dict[str, list[dict]] = {}
    sha_cache: dict[str, tuple[str, float]] = {}

    # Seed SHA cache from any already-completed JSONs for this model
    for pipe in pipelines:
        out_json = os.path.join(out_dir, f"{model_key}__{pipe}.json")
        if os.path.exists(out_json):
            rows = json.load(open(out_json, encoding="utf-8"))
            if len(rows) == len(clips):
                all_rows[pipe] = rows
                pm = prep_meta[pipe]
                for r in rows:
                    sha = wav_sha256(pm[r["name"]]["wav"])
                    sha_cache[sha] = (r["text"], r["sec_decode"])

    for pipe in pipelines:
        out_json = os.path.join(out_dir, f"{model_key}__{pipe}.json")
        if pipe in all_rows:
            print(f"[decode] reusing {out_json}")
            continue

        pm = prep_meta[pipe]
        clip_shas = {c["name"]: wav_sha256(pm[c["name"]]["wav"]) for c in clips}
        unique_missing: dict[str, str] = {}
        for c in clips:
            sha = clip_shas[c["name"]]
            if sha not in sha_cache and sha not in unique_missing:
                unique_missing[sha] = pm[c["name"]]["wav"]

        reused = len(clips) - len(unique_missing)
        print(
            f"[decode] {model_key} x {pipe}: {len(unique_missing)} unique to decode, "
            f"{reused} cached by wav sha256 ({workers} workers x {threads}T)..."
        )
        t0 = time.perf_counter()

        if unique_missing:
            items = list(unique_missing.items())
            with ThreadPoolExecutor(max_workers=workers) as ex:
                results = list(ex.map(lambda it: decode_one(whisper_cli, model_path, it[1], threads=threads), items))
            for (sha, _), res in zip(items, results):
                sha_cache[sha] = res

        rows = []
        for c in clips:
            m = pm[c["name"]]
            txt, dec_s = sha_cache[clip_shas[c["name"]]]
            rows.append(
                {
                    **c,
                    "whisper_model": model_key,
                    "pipeline": pipe,
                    **{k: v for k, v in m.items() if k != "wav"},
                    "text": txt,
                    "sec_decode": round(dec_s, 3),
                }
            )

        with open(out_json, "w", encoding="utf-8") as f:
            json.dump(rows, f, ensure_ascii=False, indent=1)
        print(f"[decode] {model_key} x {pipe} finished in {time.perf_counter() - t0:.1f}s")
        all_rows[pipe] = rows

    return all_rows


def quick_cer(rows: list[dict]) -> float:
    def nrm(s: str) -> str:
        return re.sub(r"[\s\W_]+", "", unicodedata.normalize("NFKC", s).lower())

    def ed(r: str, h: str) -> int:
        d = list(range(len(h) + 1))
        for i, rc in enumerate(r, 1):
            prev, d[0] = d[0], i
            for j, hc in enumerate(h, 1):
                cur = min(d[j] + 1, d[j - 1] + 1, prev + (rc != hc))
                prev, d[j] = d[j], cur
        return d[len(h)]

    e = n = 0
    for r in rows:
        rf, hp = nrm(r["ref"]), nrm(r["text"])
        e += ed(rf, hp)
        n += len(rf)
    return 100.0 * e / max(1, n)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("audio_dir")
    ap.add_argument("models_dir")
    ap.add_argument("whisper_cli")
    ap.add_argument("vad_onnx")
    ap.add_argument("work_dir")
    ap.add_argument("out_dir")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--prep-workers", type=int, default=8)
    args = ap.parse_args()

    clips = json.load(open(os.path.join(args.audio_dir, "clips.json"), encoding="utf-8"))
    small_pipes = ["raw", "spk_gate_0.35", "spk_gate_0.42", "voxlock_zero", "voxlock_enroll"]

    prep_meta = prep_all_pipelines(
        clips, args.audio_dir, args.models_dir, args.vad_onnx, args.work_dir, small_pipes, prep_workers=args.prep_workers
    )

    small_model = os.path.join(args.models_dir, "small_acft_q8_0.bin")
    small_res = decode_suite(
        clips, prep_meta, args.whisper_cli, "small_acft", small_model, small_pipes, args.out_dir, args.workers, args.threads
    )

    best_spk = min(["spk_gate_0.35", "spk_gate_0.42"], key=lambda p: quick_cer(small_res[p]))
    print(f"[select] best spk_gate for Turbo cross-check: {best_spk}")

    turbo_bases = {"cs00", "cs01", "cs02", "cs03", "zk00", "zk01", "zk02", "zk03"}
    turbo_clips = [c for c in clips if c["base"] in turbo_bases]
    turbo_pipes = ["raw", best_spk, "voxlock_enroll"]
    turbo_model = os.path.join(args.models_dir, "ggml-acft-large-v3-turbo-q4_0.bin")
    decode_suite(
        turbo_clips, prep_meta, args.whisper_cli, "turbo_acft", turbo_model, turbo_pipes, args.out_dir, args.workers, args.threads
    )


if __name__ == "__main__":
    main()
