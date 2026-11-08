#!/usr/bin/env python3
"""Compare the app's speech models on real Korean speech: accuracy (CER) and speed.

Run by .github/workflows/bench-models.yml on a GitHub runner (x86, 4 vCPU). Speed there is not
phone speed, but the ratios between models carry over. Settings mirror the app:
- Whisper: whisper.cpp v1.7.4 (the version vendored in the app), Korean, beam 5, no single
  segment, the app's default style-example prompt, audio_ctx sized to the clip exactly as jni.c
  does (ACFT floor 128, stock 768), non-speech tokens suppressed.
- Moonshine / SenseVoice: sherpa-onnx 2.0.1 (the app's AAR version), same model bundles.

Usage: bench_models.py <work dir with models/ and data/> <n utterances>
"""
import csv, json, os, re, subprocess, sys, time, unicodedata
import numpy as np, soundfile as sf, sherpa_onnx

W, N = sys.argv[1], int(sys.argv[2])
THREADS = 4
PROMPT = "GitLab에 MR 올리고, worktree 정리한 다음 Claude Code로 테스트 돌려줘."

# --- data: FLEURS ko_kr test, first N by id ----------------------------------------------
rows = []
with open(f"{W}/data/test.tsv", encoding="utf-8") as f:
    for r in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
        rows.append((r[1], r[2]))  # file name, raw transcription
seen, items = set(), []
for fn, text in rows:
    p = f"{W}/data/test/{fn}"
    if fn in seen or not os.path.exists(p):
        continue
    seen.add(fn)
    items.append((p, text))
items = items[:N]
audio = {}
for p, _ in items:
    a, sr = sf.read(p, dtype="float32")
    if a.ndim > 1: a = a.mean(axis=1)
    assert sr == 16000, sr
    audio[p] = a
total_sec = sum(len(a) for a in audio.values()) / 16000
print(f"{len(items)} utterances, {total_sec:.0f}s of audio")

def norm(s):
    s = unicodedata.normalize("NFKC", s).lower().replace("▁", " ")
    return re.sub(r"[\s\W_]+", "", s)

def cer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i, rc in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, hc in enumerate(h, 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (rc != hc))
            prev, d[j] = d[j], cur
    return d[len(h)], max(1, len(r))

# --- engines ---------------------------------------------------------------------------
def whisper(model, acft):
    def run(a, p):
        n = len(a)
        ctx = (n + 319) // 320 + 16
        ctx = (ctx + 63) // 64 * 64
        ctx = min(1500, max(128 if acft else 768, ctx))
        out = subprocess.run(
            [f"{W}/whisper-cli", "-m", model, "-f", p, "-l", "ko", "-bs", "5", "-t", str(THREADS),
             "-ac", str(ctx), "--prompt", PROMPT, "-nt", "-sns"],
            capture_output=True, text=True)
        timings = out.stderr
        tot = float(re.search(r"total time\s*=\s*([\d.]+) ms", timings).group(1))
        load = float(re.search(r"load time\s*=\s*([\d.]+) ms", timings).group(1))
        return out.stdout.strip(), (tot - load) / 1000
    return run

def sherpa(make):
    rec = make()
    def run(a, p):
        s = rec.create_stream(); s.accept_waveform(16000, a)
        t = time.time(); rec.decode_stream(s)
        return s.result.text, time.time() - t
    return run

M = f"{W}/models"
def moon(d): return lambda: sherpa_onnx.OfflineRecognizer.from_moonshine_v2(
    encoder=f"{M}/{d}/encoder_model.ort", decoder=f"{M}/{d}/decoder_model_merged.ort",
    tokens=f"{M}/{d}/tokens.txt", num_threads=THREADS)
def sv(d, f): return lambda: sherpa_onnx.OfflineRecognizer.from_sense_voice(
    model=f"{M}/{d}/{f}", tokens=f"{M}/{d}/tokens.txt", language="ko", use_itn=True, num_threads=THREADS)

engines = [
    ("Moonshine Korean (tiny, old)", sherpa(moon("sherpa-onnx-moonshine-tiny-ko-quantized-2026-02-27"))),
    ("Moonshine Base Korean", sherpa(moon("sherpa-onnx-moonshine-base-ko-quantized"))),
    ("SenseVoice Small (int8)", sherpa(sv("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17", "model.int8.onnx"))),
    ("SenseVoice Small (full)", sherpa(sv("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17", "model.onnx"))),
    ("Small ACFT (FUTO)", whisper(f"{M}/small_acft_q8_0.bin", True)),
    ("Large v3 Turbo ACFT q4", whisper(f"{M}/ggml-acft-large-v3-turbo-q4_0.bin", True)),
]

results, samples = [], {}
for name, run in engines:
    errs = chars = 0; secs = 0.0; outs = []
    for p, ref in items:
        hyp, dt = run(audio[p], p)
        e, c = cer(ref, hyp); errs += e; chars += c; secs += dt
        outs.append(hyp)
    results.append((name, 100 * errs / chars, secs, secs / total_sec))
    samples[name] = outs[:3]
    print(f"{name:32s} CER {100*errs/chars:5.1f}%  decode {secs:6.1f}s  RTF {secs/total_sec:.3f}", flush=True)

lines = ["| Model | CER (lower is better) | Decode time | Real-time factor |", "|---|---|---|---|"]
lines += [f"| {n} | {c:.1f}% | {s:.1f}s | {r:.3f} |" for n, c, s, r in results]
lines += ["", f"{len(items)} FLEURS ko_kr test utterances, {total_sec:.0f}s audio, {THREADS} threads, x86 runner.", ""]
for i, (p, ref) in enumerate(items[:3]):
    lines.append(f"**Ref {i+1}:** {ref}  ")
    for n in samples:
        lines.append(f"- {n}: {samples[n][i]}  ")
    lines.append("")
md = "\n".join(lines)
print(md)
with open(os.environ.get("GITHUB_STEP_SUMMARY", "/dev/null"), "a") as f:
    f.write(md + "\n")
json.dump({"results": results}, open(f"{W}/bench.json", "w"), ensure_ascii=False, indent=1)
