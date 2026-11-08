#!/usr/bin/env python3
"""Transcribe the benchmark clips with Turbo ACFT, whole or cut at pauses, as the app would.

whole:   one whisper-cli run over the clip (what the app does today).
chunked: what the planned pipelined mode would do while the user speaks. A cut is made at the
         middle of the first pause of >= PAUSE s once the pending chunk is >= MIN_CHUNK s long;
         each chunk is decoded with the style prompt plus the tail of the text so far as context.
Settings otherwise match the app (whisper.cpp 1.7.4, ko, beam 5, clip-sized audio_ctx, ACFT
floor 128, non-speech tokens suppressed).

Usage: chunk_bench_run.py <data dir> <whisper-cli> <model> <whole|chunked> <set> <out.json>
"""
import json, re, subprocess, sys
import numpy as np, soundfile as sf

D, CLI, MODEL, METHOD, SET, OUTP = sys.argv[1:7]
SR, THREADS = 16000, 4
STYLE = "GitLab에 MR 올리고, worktree 정리한 다음 Claude Code로 테스트 돌려줘."
PAUSE, MIN_CHUNK, FRAME = 0.5, 5.0, 320

def decode(a, prompt):
    sf.write(f"{D}/_c.wav", a, SR, subtype="PCM_16")
    ctx = (len(a) + 319) // 320 + 16
    ctx = min(1500, max(128, (ctx + 63) // 64 * 64))
    out = subprocess.run([CLI, "-m", MODEL, "-f", f"{D}/_c.wav", "-l", "ko", "-bs", "5", "-t", str(THREADS),
                          "-ac", str(ctx), "--prompt", prompt, "-nt", "-sns"], capture_output=True)
    # whisper-cli can print a token split mid-way through a UTF-8 sequence.
    out.stdout = out.stdout.decode("utf-8", "replace"); out.stderr = out.stderr.decode("utf-8", "replace")
    tot = float(re.search(r"total time\s*=\s*([\d.]+) ms", out.stderr).group(1))
    load = float(re.search(r"load time\s*=\s*([\d.]+) ms", out.stderr).group(1))
    return " ".join(out.stdout.split()), (tot - load) / 1000

def cuts(a):
    """Sample offsets where the streaming cutter would split, judged only on audio seen so far."""
    n = len(a) // FRAME
    e = np.array([np.sqrt(np.mean(a[i * FRAME:(i + 1) * FRAME] ** 2)) for i in range(n)])
    loud = np.percentile(e, 95)
    quiet = e < max(loud * 0.05, 1e-4)          # ~-26 dB under speech level
    need = int(PAUSE * SR / FRAME)
    out, start, run = [], 0, 0
    for i in range(n):
        run = run + 1 if quiet[i] else 0
        if run == need and (i - run + 1) * FRAME - start >= MIN_CHUNK * SR:
            cut = (i - run // 2) * FRAME
            out.append(cut); start = cut
    return out

def voiced(a):
    n = len(a) // FRAME
    if n == 0: return 0.0
    e = np.array([np.sqrt(np.mean(a[i * FRAME:(i + 1) * FRAME] ** 2)) for i in range(n)])
    return float((e > 0.01).sum()) * FRAME / SR

clips = [c for c in json.load(open(f"{D}/clips.json")) if c["set"] == SET]
results = []
for c in clips:
    a, _ = sf.read(f"{D}/{c['name']}.wav", dtype="float32")
    if METHOD == "whole":
        text, t = decode(a, STYLE)
        res = {"text": text, "proc": t, "chunks": [len(a) / SR], "times": [t]}
    else:
        bounds = [0] + cuts(a) + [len(a)]
        texts, times = [], []
        for s, e in zip(bounds, bounds[1:]):
            # The tail after the last cut is often just the trailing silence that ends a
            # recording (the app auto-stops after 1.6s of it). Whisper invents text for silence,
            # so a piece with under 0.3s of sound is skipped, as the app would.
            if voiced(a[s:e]) < 0.3:
                times.append(0.0)
                continue
            ctx = " ".join(texts)[-200:]
            prompt = (STYLE + " " + ctx).strip()
            t_text, t = decode(a[s:e], prompt)
            texts.append(t_text); times.append(t)
        res = {"text": " ".join(texts), "proc": sum(times),
               "chunks": [(e - s) / SR for s, e in zip(bounds, bounds[1:])], "times": times}
    res.update(name=c["name"], ref=c["ref"], sec=c["sec"])
    results.append(res)
    print(f"{c['name']} {c['sec']:.1f}s chunks={[round(x,1) for x in res['chunks']]} proc={res['proc']:.1f}s | {res['text'][:80]}", flush=True)
json.dump(results, open(OUTP, "w"), ensure_ascii=False, indent=1)
