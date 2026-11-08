#!/usr/bin/env python3
"""NPU candidates vs the app's models on the routing-benchmark clips (scripts/route_bench_data.py).

The Qualcomm NPU runs sherpa-onnx's Whisper exports (fixed 30s input, float, greedy, no prompt).
Their accuracy is the same as the ONNX export on CPU, which is what runs here for medium,
small-orig (OpenAI small) and turbo-orig (OpenAI large-v3-turbo). sensevoice / small (ACFT) /
turbo (ACFT q4) run exactly as in the app, for reference. CPU time here says nothing about the NPU.
Usage: npu_bench_run.py <data dir> <models dir> <whisper-cli> <model key> <cond> <out.json>
"""
import glob, json, re, subprocess, sys, time
import numpy as np, soundfile as sf

D, M, CLI, KEY, COND, OUTP = sys.argv[1:7]
SR, THREADS = 16000, 4
STYLE = "GitLab에 MR 올리고, worktree 정리한 다음 Claude Code로 테스트 돌려줘."
SHERPA_WHISPER = {"medium": "medium", "small-orig": "small", "turbo-orig": "turbo"}

def one(pattern):
    hits = [p for p in glob.glob(pattern) if ".int8." not in p]
    assert len(hits) == 1, (pattern, glob.glob(pattern))
    return hits[0]

if KEY == "sensevoice" or KEY in SHERPA_WHISPER:
    import sherpa_onnx
    if KEY == "sensevoice":
        d = f"{M}/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        rec = sherpa_onnx.OfflineRecognizer.from_sense_voice(model=f"{d}/model.int8.onnx", tokens=f"{d}/tokens.txt",
              language="ko", use_itn=True, num_threads=THREADS)
    else:
        d = f"{M}/sherpa-onnx-whisper-{SHERPA_WHISPER[KEY]}"
        rec = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=one(f"{d}/*encoder.onnx"), decoder=one(f"{d}/*decoder.onnx"), tokens=one(f"{d}/*tokens.txt"),
            language="ko", task="transcribe", num_threads=THREADS)
    def run(a, path):
        s = rec.create_stream(); s.accept_waveform(SR, a)
        t = time.time(); rec.decode_stream(s)
        return s.result.text.replace("▁", " ").strip(), time.time() - t
else:
    model = f"{M}/" + ("small_acft_q8_0.bin" if KEY == "small" else "ggml-acft-large-v3-turbo-q4_0.bin")
    def run(a, path):
        ctx = (len(a) + 319) // 320 + 16
        ctx = min(1500, max(128, (ctx + 63) // 64 * 64))
        out = subprocess.run([CLI, "-m", model, "-f", path, "-l", "ko", "-bs", "5", "-t", str(THREADS),
                              "-ac", str(ctx), "--prompt", STYLE, "-nt", "-sns"], capture_output=True)
        err = out.stderr.decode("utf-8", "replace")
        tot = float(re.search(r"total time\s*=\s*([\d.]+) ms", err).group(1))
        load = float(re.search(r"load time\s*=\s*([\d.]+) ms", err).group(1))
        return " ".join(out.stdout.decode("utf-8", "replace").split()), (tot - load) / 1000

res = []
for c in json.load(open(f"{D}/clips.json")):
    if c["cond"] != COND: continue
    path = f"{D}/{c['name']}.wav"
    a, _ = sf.read(path, dtype="float32")
    text, t = run(a, path)
    res.append({**c, "text": text, "sec_decode": t})
    print(f"{c['name']} {t:.2f}s | {text[:70]}", flush=True)
json.dump(res, open(OUTP, "w"), ensure_ascii=False, indent=1)
