#!/usr/bin/env python3
"""Aggregate and print the overlapping-speech & TV noise benchmark report.

Usage:
  voice_bench_report.py <results_dir>
"""
import glob
import json
import os
import re
import sys
import unicodedata
from collections import defaultdict

R = sys.argv[1]
CONDS = ["clean", "pause_tv", "overlap10", "overlap5", "overlap0"]

META = {
    "raw": ("없음 (원본)", "-", "-"),
    "spk_gate_0.30": ("3dspeaker CAM++ (27 MB)", "Apache-2.0", "임계치 0.30"),
    "spk_gate_0.35": ("3dspeaker CAM++ (27 MB)", "Apache-2.0", "임계치 0.35"),
    "spk_gate_0.42": ("3dspeaker CAM++ (27 MB)", "Apache-2.0", "임계치 0.42"),
    "voxlock_zero": ("VoxLock v2 int8 (74 MB)", "Apache-2.0", "무등록 3초 자동잠금"),
    "voxlock_enroll": ("VoxLock v2 int8 (74 MB)", "Apache-2.0", "등록 화자 기반"),
    "voxlock_enroll_fp16": ("VoxLock v2 fp16 (47 MB)", "Apache-2.0", "등록 화자 기반"),
}


def norm(s: str) -> str:
    return re.sub(r"[\s\W_]+", "", unicodedata.normalize("NFKC", s).lower())


def ed(r: str, h: str) -> int:
    d = list(range(len(h) + 1))
    for i, rc in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, hc in enumerate(h, 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (rc != hc))
            prev, d[j] = d[j], cur
    return d[len(h)]


def cer_stats(rows: list[dict]) -> float:
    e = n = 0
    for r in rows:
        ref = norm(r["ref"])
        hyp = norm(r["text"])
        e += ed(ref, hyp)
        n += len(ref)
    return 100.0 * e / max(1, n)


def main() -> None:
    files = sorted(glob.glob(os.path.join(R, "*.json")))
    by_model: dict[str, dict[str, list[dict]]] = defaultdict(lambda: defaultdict(list))
    for fp in files:
        if os.path.basename(fp).startswith("_"):
            continue
        data = json.load(open(fp, encoding="utf-8"))
        for row in data:
            by_model[row["whisper_model"]][row["pipeline"]].append(row)

    lines = ["# 주변 말소리 · TV 소음 전처리 벤치마크 리포트", ""]
    for wmodel in sorted(by_model.keys()):
        pipes = by_model[wmodel]
        sample_n = max((len(v) for v in pipes.values()), default=0)
        lines.append(f"## Whisper 모델: `{wmodel}` ({sample_n}클립)")
        lines.append("")
        hdr = "| 전처리 | " + " | ".join(CONDS) + " | 전체 CER | clean(cs/zk) | pause_tv Auto-stop | 1T 전처리 RTF | 모델 / 용량 / 라이선스 |"
        lines.append(hdr)
        lines.append("|---|" + "---|" * (len(CONDS) + 5))

        order = [p for p in ["raw", "spk_gate_0.35", "spk_gate_0.42", "voxlock_zero", "voxlock_enroll", "voxlock_enroll_fp16"] if p in pipes]
        for p in sorted(pipes.keys()):
            if p not in order:
                order.append(p)

        for pipe in order:
            rows = pipes[pipe]
            by_cond = defaultdict(list)
            for r in rows:
                by_cond[r["cond"]].append(r)

            c_str = [f"{cer_stats(by_cond[c]):.1f}%" if by_cond[c] else "-" for c in CONDS]
            all_cer = cer_stats(rows)

            clean_rows = by_cond.get("clean", [])
            cs_rows = [r for r in clean_rows if r["src"] == "cs"]
            zk_rows = [r for r in clean_rows if r["src"] == "zk"]
            cs_zk = f"{cer_stats(cs_rows):.1f}% / {cer_stats(zk_rows):.1f}%" if cs_rows and zk_rows else "-"

            ptv = by_cond.get("pause_tv", [])
            if ptv:
                ok = sum(1 for r in ptv if r.get("auto_stop_ok", False))
                autostop_str = f"{ok}/{len(ptv)} ({100.0 * ok / len(ptv):.0f}%)"
            else:
                autostop_str = "-"

            tot_audio = sum(r.get("sec", 0.0) for r in rows)
            tot_prep = sum(r.get("sec_prep", 0.0) for r in rows)
            rtf = tot_prep / max(tot_audio, 1e-6)
            rtf_str = f"{rtf:.3f}" if pipe != "raw" else "0.000"

            mname, lic, note = META.get(pipe, (pipe, "-", ""))
            meta_str = f"{mname} ({lic})" + (f" · {note}" if note else "")
            lines.append(
                f"| `{pipe}` | " + " | ".join(c_str) + f" | **{all_cer:.1f}%** | {cs_zk} | {autostop_str} | {rtf_str} | {meta_str} |"
            )
        lines.append("")

        # Also check clean auto-stop sanity
        clean_raw = pipes.get("raw", [])
        clean_only = [r for r in clean_raw if r["cond"] == "clean"]
        if clean_only:
            ok_c = sum(1 for r in clean_only if r.get("auto_stopped", False))
            lines.append(f"- `clean` + `raw` Silero Auto-stop 정상 발동: {ok_c}/{len(clean_only)} ({100.0 * ok_c / len(clean_only):.0f}%)")
            lines.append("")

    print("\n".join(lines))


if __name__ == "__main__":
    main()
