#!/usr/bin/env python3
"""Score single models and candidate Auto routers on the routing benchmark.

Cost is decode time on the runner (only ratios carry over to the phone). Routers:
- rule(level, snr): quiet (speech level under L dBFS) -> Small ACFT; noisy (SNR under S dB) ->
  Turbo; otherwise SenseVoice, with its no-Hangul miss redone by Small ACFT.
- oracle: best model per clip (upper bound).
Moonshine Base was dropped from this bench (2026-11-10): the app no longer ships it and its
Community License bundle is no longer hosted here.
Usage: route_bench_report.py <results dir>
"""
import glob, json, os, re, sys, unicodedata
from collections import defaultdict

R = sys.argv[1]
MODELS = ("sensevoice", "small", "turbo")
def norm(s): return re.sub(r"[\s\W_]+", "", unicodedata.normalize("NFKC", s).lower())
def ed(r, h):
    d = list(range(len(h) + 1))
    for i, rc in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, hc in enumerate(h, 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (rc != hc)); prev, d[j] = d[j], cur
    return d[len(h)]
def miss(t): return not any("가" <= ch <= "힣" for ch in t)

runs = defaultdict(dict)  # (clip) -> model -> rec
for f in glob.glob(f"{R}/**/*.json", recursive=True):
    m = re.search(r"(sensevoice|small|turbo)-(\w+)\.json$", f)
    if not m: continue
    for x in json.load(open(f)):
        runs[x["name"]][m.group(1)] = x
clips = [k for k, v in runs.items() if len(v) == len(MODELS)]
conds = sorted({runs[k]["turbo"]["cond"] for k in clips})

def score(pick):
    """pick(clip models) -> (text, cost). Returns {cond: (CER%, cost)} plus 'all'."""
    out = {}
    for c in conds + ["all"]:
        e = n = cost = 0
        for k in clips:
            r = runs[k]
            if c != "all" and r["turbo"]["cond"] != c: continue
            text, t = pick(r)
            ref = norm(r["turbo"]["ref"]); e += ed(ref, norm(text)); n += len(ref); cost += t
        out[c] = (100 * e / max(1, n), cost)
    return out

def single(m): return lambda r: (r[m]["text"], r[m]["sec_decode"])
def sv_fb(r):
    t, c = r["sensevoice"]["text"], r["sensevoice"]["sec_decode"]
    if miss(t): return r["small"]["text"], c + r["small"]["sec_decode"]
    return t, c
def rule(L, S, quiet="small", noisy="turbo"):
    def f(r):
        x = r["turbo"]
        if x["level_db"] < L: return single(quiet)(r)
        if x["snr_db"] < S: return single(noisy)(r)
        return sv_fb(r)
    return f
def oracle(r):
    ref = norm(r["turbo"]["ref"])
    return min(((r[m]["text"], r[m]["sec_decode"]) for m in r), key=lambda p: ed(ref, norm(p[0])))

policies = [("SenseVoice", single("sensevoice")), ("SenseVoice + miss fallback", sv_fb),
            ("Small ACFT", single("small")), ("Turbo", single("turbo"))]
for L in (-40, -45):
    for S in (8, 12):
        policies.append((f"rule: level<{L}dB→Small, SNR<{S}dB→Turbo, else SenseVoice", rule(L, S)))
        policies.append((f"rule: level<{L}dB→Small, SNR<{S}dB→Small, else SenseVoice", rule(L, S, noisy="small")))
policies.append(("oracle (best per clip)", oracle))

hdr = "| Policy | " + " | ".join(conds) + " | all CER | all time |"
lines = [hdr, "|" + "---|" * (len(conds) + 3)]
for name, p in policies:
    s = score(p)
    lines.append(f"| {name} | " + " | ".join(f"{s[c][0]:.1f}%" for c in conds) + f" | **{s['all'][0]:.1f}%** | {s['all'][1]:.0f}s |")
feat = defaultdict(list)
for k in clips:
    x = runs[k]["turbo"]; feat[x["cond"]].append((x["level_db"], x["snr_db"]))
lines += ["", "Audio features (median level dBFS / SNR dB): " + ", ".join(
    f"{c} {sorted(v)[len(v)//2][0]:.0f}/{sorted(v, key=lambda t: t[1])[len(v)//2][1]:.0f}" for c, v in feat.items()),
    f"{len(clips)} clips. Time = decode seconds on the x86 runner (4 threads), summed."]
md = "\n".join(lines); print(md)
with open(os.environ.get("GITHUB_STEP_SUMMARY", "/dev/null"), "a") as f: f.write(md + "\n")
