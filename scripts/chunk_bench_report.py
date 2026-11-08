#!/usr/bin/env python3
"""Compare whole vs chunked results: CER and the wait after the user stops talking.

Wait after stop:
- whole: the whole clip's decode time (decoding only starts when recording ends).
- chunked: chunks are queued as soon as their audio exists; the wait is when the last one
  finishes minus the end of the audio. Reported at runner speed and at phone speed, the latter
  scaling runner times by PHONE (Turbo ACFT took 10.0s for a 15s clip on the user's phone,
  ~21s on this runner, so ~0.5).
Usage: chunk_bench_report.py <results dir>
"""
import glob, json, os, re, sys, unicodedata

PHONE = 0.5
R = sys.argv[1]

def norm(s):
    return re.sub(r"[\s\W_]+", "", unicodedata.normalize("NFKC", s).lower())

def cer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i, rc in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, hc in enumerate(h, 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (rc != hc)); prev, d[j] = d[j], cur
    return d[len(h)], len(r)

def wait(res, speed):
    if len(res["chunks"]) == 1:
        return res["times"][0] * speed
    t, finish = 0.0, 0.0
    for dur, proc in zip(res["chunks"], res["times"]):
        t += dur                       # this chunk's audio ends at t
        finish = max(finish, t) + proc * speed
    return max(0.0, finish - t)

rows = []
lines = ["| Set | Method | CER | Avg wait after stop (phone est.) | Avg wait (runner) | Avg clip |", "|---|---|---|---|---|---|"]
for f in sorted(glob.glob(f"{R}/**/*.json", recursive=True)):
    m = re.search(r"(codeswitch|fleurs)-(whole|chunked)", f)
    if not m: continue
    res = json.load(open(f))
    e = c = 0
    for x in res:
        a, b = cer(x["ref"], x["text"]); e += a; c += b
    n = len(res)
    lines.append(f"| {m.group(1)} | {m.group(2)} | {100*e/c:.1f}% | {sum(wait(x, PHONE) for x in res)/n:.1f}s | "
                 f"{sum(wait(x, 1.0) for x in res)/n:.1f}s | {sum(x['sec'] for x in res)/n:.0f}s |")
    rows.append((m.group(1), m.group(2), res))
lines.append("")
for s, meth, res in rows:
    if meth != "chunked": continue
    whole = next((r for s2, m2, r in rows if s2 == s and m2 == "whole"), None)
    for i, x in enumerate(res[:3]):
        lines.append(f"**{s} {x['name']}** ({x['sec']:.0f}s, chunks {[round(c,1) for c in x['chunks']]})  ")
        lines.append(f"- ref: {x['ref']}  ")
        if whole: lines.append(f"- whole: {whole[i]['text']}  ")
        lines.append(f"- chunked: {x['text']}  ")
        lines.append("")
md = "\n".join(lines)
print(md)
with open(os.environ.get("GITHUB_STEP_SUMMARY", "/dev/null"), "a") as f:
    f.write(md + "\n")
