#!/usr/bin/env python3
"""Score single models and candidate Auto routers on the routing benchmark.

Cost is decode time on the runner (only ratios carry over to the phone). Routers:
- rule(level, snr): quiet (speech level under L dBFS) -> Small ACFT; noisy (SNR under S dB) ->
  Turbo; otherwise Moonshine Base, with its 'audiotext'/no-Hangul miss redone by SenseVoice.
- agree(T): run Moonshine Base and SenseVoice; if they differ by < T CER take Moonshine,
  else Small ACFT. agree+turbo: same but Turbo as the tie-breaker.
- oracle: best model per clip (upper bound).
Usage: route_bench_report.py <results dir>
"""
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
def cer_pair(a, b):
    a, b = norm(a), norm(b); return ed(a, b) / max(1, len(a))
def miss(t): return "audiotext" in t.lower() or not any("가" <= ch <= "힣" for ch in t)

runs = defaultdict(dict)  # (clip) -> model -> rec
for f in glob.glob(f"{R}/**/*.json", recursive=True):
    m = re.search(r"(moonshine|sensevoice|small|turbo)-(\w+)\.json$", f)
    if not m: continue
    for x in json.load(open(f)):
        runs[x["name"]][m.group(1)] = x
clips = [k for k, v in runs.items() if len(v) == 4]
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
def moon_fb(r):
    t, c = r["moonshine"]["text"], r["moonshine"]["sec_decode"]
    if miss(t): return r["sensevoice"]["text"], c + r["sensevoice"]["sec_decode"]
    return t, c
def rule(L, S, quiet="small", noisy="turbo"):
    def f(r):
        x = r["turbo"]
        if x["level_db"] < L: return single(quiet)(r)
        if x["snr_db"] < S: return single(noisy)(r)
        return moon_fb(r)
    return f
def agree(T, tie="small"):
    def f(r):
        a, b = r["moonshine"], r["sensevoice"]
        base = max(a["sec_decode"], b["sec_decode"])  # run side by side
        if not miss(a["text"]) and cer_pair(a["text"], b["text"]) < T: return a["text"], base
        return r[tie]["text"], base + r[tie]["sec_decode"]
    return f
def oracle(r):
    ref = norm(r["turbo"]["ref"])
    return min(((r[m]["text"], r[m]["sec_decode"]) for m in r), key=lambda p: ed(ref, norm(p[0])))

policies = [("Moonshine Base", single("moonshine")), ("Moonshine Base + miss fallback", moon_fb),
            ("SenseVoice", single("sensevoice")), ("Small ACFT", single("small")), ("Turbo", single("turbo"))]
for L in (-40, -45):
    for S in (8, 12):
        policies.append((f"rule: level<{L}dB→Small, SNR<{S}dB→Turbo, else Moonshine", rule(L, S)))
        policies.append((f"rule: level<{L}dB→Small, SNR<{S}dB→Small, else Moonshine", rule(L, S, noisy="small")))
for T in (0.10, 0.20, 0.30):
    policies.append((f"agree<{T:.2f} else Small", agree(T)))
    policies.append((f"agree<{T:.2f} else Turbo", agree(T, "turbo")))
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
