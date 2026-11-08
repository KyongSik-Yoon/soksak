#!/usr/bin/env python3
"""Run one model over every routing-benchmark clip of one condition, as the app would.

Writes per clip: text, decode seconds, and the audio features a router could use (duration,
speech level, noise floor). Usage:
  route_bench_run.py <data dir> <models dir> <whisper-cli> <model key> <cond> <out.json>
model key: moonshine | sensevoice | small | turbo
"""
import json, re, subprocess, sys, time
import numpy as np, soundfile as sf

D, M, CLI, KEY, COND, OUTP = sys.argv[1:7]
SR, THREADS = 16000, 4
STYLE = "GitLab에 MR 올리고, worktree 정리한 다음 Claude Code로 테스트 돌려줘."

def features(a):
    f = 320
    e = np.array([np.sqrt(np.mean(a[i * f:(i + 1) * f] ** 2)) + 1e-9 for i in range(len(a) // f)])
    level = float(20 * np.log10(np.percentile(e, 95)))
    floor = float(20 * np.log10(np.percentile(e, 10)))
    return {"level_db": level, "floor_db": floor, "snr_db": level - floor}

if KEY in ("moonshine", "sensevoice"):
    import sherpa_onnx
    if KEY == "moonshine":
        d = f"{M}/sherpa-onnx-moonshine-base-ko-quantized"
        rec = sherpa_onnx.OfflineRecognizer.from_moonshine_v2(encoder=f"{d}/encoder_model.ort",
              decoder=f"{d}/decoder_model_merged.ort", tokens=f"{d}/tokens.txt", num_threads=THREADS)
    else:
        d = f"{M}/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        rec = sherpa_onnx.OfflineRecognizer.from_sense_voice(model=f"{d}/model.int8.onnx", tokens=f"{d}/tokens.txt",
              language="ko", use_itn=True, num_threads=THREADS)
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
    res.append({**c, **features(a), "text": text, "sec_decode": t})
    print(f"{c['name']} {t:.2f}s | {text[:70]}", flush=True)
json.dump(res, open(OUTP, "w"), ensure_ascii=False, indent=1)
