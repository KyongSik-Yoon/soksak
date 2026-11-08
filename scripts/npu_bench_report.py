#!/usr/bin/env python3
"""Single-model CER table for npu-bench: per condition, and FLEURS vs code-switched TTS.
Usage: npu_bench_report.py <results dir>"""
import glob, json, os, re, sys, unicodedata
from collections import defaultdict

R = sys.argv[1]
def norm(s): return re.sub(r"[\s\W_]+", "", unicodedata.normalize("NFKC", s).lower())
def ed(r, h):
    d = list(range(len(h) + 1))
    for i, rc in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, hc in enumerate(h, 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (rc != hc)); prev, d[j] = d[j], cur
    return d[len(h)]

ORDER = ["turbo", "turbo-orig", "medium", "small", "small-orig", "sensevoice"]
NAMES = {"turbo": "Turbo ACFT q4 (app, CPU)", "turbo-orig": "Turbo original (NPU candidate)",
         "medium": "Medium (NPU candidate)", "small": "Small ACFT (app, CPU)",
         "small-orig": "Small original (NPU candidate)", "sensevoice": "SenseVoice (app)"}
recs = defaultdict(list)
for f in glob.glob(f"{R}/**/*.json", recursive=True):
    m = re.search(r"/([\w-]+?)-(clean|babble10|babble0|quiet)\.json$", f)
    if m: recs[m.group(1)] += json.load(open(f))
conds = ["clean", "babble10", "babble0", "quiet"]
def cer(rs):
    e = n = 0
    for x in rs:
        r = norm(x["ref"]); e += ed(r, norm(x["text"])); n += len(r)
    return 100 * e / max(1, n)
srcs = sorted({x["src"] for v in recs.values() for x in v})
hdr = "| Model | " + " | ".join(conds) + " | " + " | ".join(f"clean {s}" for s in srcs) + " | CPU time |"
lines = [hdr, "|" + "---|" * (len(conds) + len(srcs) + 2)]
for k in [k for k in ORDER if k in recs]:
    rs = recs[k]
    row = [f"{cer([x for x in rs if x['cond'] == c]):.1f}%" for c in conds]
    row += [f"{cer([x for x in rs if x['cond'] == 'clean' and x['src'] == s]):.1f}%" for s in srcs]
    lines.append(f"| {NAMES[k]} | " + " | ".join(row) + f" | {sum(x['sec_decode'] for x in rs):.0f}s |")
lines += ["", "CER. NPU candidates run their ONNX export on CPU here (same accuracy as on the NPU, no prompt,",
          "greedy). CPU time is the x86 runner, 4 threads, all conditions summed — not NPU speed."]
# A few code-switched examples side by side.
ex = defaultdict(dict)
for k, rs in recs.items():
    for x in rs:
        if x["cond"] == "clean" and x["src"] != "fl": ex[x["name"]][k] = x
for name in sorted(ex)[:3]:
    lines += ["", f"**{name}** ref: {next(iter(ex[name].values()))['ref']}"]
    lines += [f"- {k}: {ex[name][k]['text']}" for k in ORDER if k in ex[name]]
md = "\n".join(lines); print(md)
with open(os.environ.get("GITHUB_STEP_SUMMARY", "/dev/null"), "a") as f: f.write(md + "\n")
