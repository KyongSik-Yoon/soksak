#!/usr/bin/env python3
"""Audio for the Auto-model routing benchmark (route-bench.yml).

36 base clips — 24 FLEURS ko_kr test utterances (real read speech) and 12 code-switched dev
requests by Edge TTS (two sentences each) — each in four conditions:
  clean    as recorded
  babble10 café-like babble (4 other FLEURS speakers summed) at 10 dB SNR
  babble0  the same babble at 0 dB SNR (speech as loud as the room)
  quiet    -30 dB (far or very soft speech) over a -70 dBFS mic noise floor. Real whispering is
           unvoiced and sounds different; this only approximates "very quiet".
Usage: route_bench_data.py <out dir>
"""
import asyncio, csv, json, os, random, subprocess, sys, tarfile
import numpy as np, soundfile as sf

OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
SR = 16000
rng = random.Random(11); nrng = np.random.default_rng(11)

SENTENCES = [
    "GitLab에 MR 올리고, worktree 정리한 다음 Claude Code로 테스트 돌려줘.",
    "Kubernetes pod가 계속 CrashLoopBackOff 나는데 로그 좀 확인해줄래?",
    "ClickHouse 쿼리가 느린데 materialized view로 바꾸면 얼마나 빨라질지 비교해줘.",
    "이번 PR은 리뷰 코멘트 반영해서 force push 하지 말고 새 커밋으로 올려줘.",
    "README에 설치 방법이랑 환경 변수 설명 추가해줘.",
    "API 응답이 너무 느리니까 Redis 캐시를 앞에 두는 방향으로 설계해봐.",
    "Docker 이미지 크기가 2기가가 넘는데 multi-stage build로 줄여볼 수 있을까?",
    "JVM 힙 메모리가 계속 올라가는데 메모리 릭인지 heap dump 떠서 분석해봐.",
    "OpenTelemetry collector 설정에서 sampling 비율을 10퍼센트로 낮춰줘.",
    "테스트 커버리지 리포트를 뽑아서 80퍼센트 안 되는 모듈만 알려줘.",
    "nginx 설정에서 타임아웃을 60초로 늘리고 재시작해줘.",
    "Gradle 빌드가 너무 오래 걸리는데 캐시 설정 좀 손봐줘.",
    "TypeScript 타입 에러가 스무 개쯤 나오는데 하나씩 고쳐줘.",
    "브랜치 이름은 feature 슬래시 settings UI로 해서 새로 만들어줘.",
    "로그인 페이지에서 OAuth 콜백이 404가 나는데 라우팅 설정을 확인해줘.",
    "unit test가 CI에서만 실패하는데 로컬에서는 통과하거든, 타임존 문제인지 봐줘.",
    "이 함수 이름이 너무 길어서 좀 더 짧고 명확하게 리팩토링해줘.",
    "지금 열려있는 이슈 중에서 버그 라벨 붙은 것만 정리해서 보여줘.",
    "Slack 알림 메시지 포맷을 좀 더 읽기 쉽게 바꿔줄래?",
    "어제 머지한 커밋 때문에 대시보드 차트가 안 그려지는 것 같아, revert 해줘.",
    "이 스크립트를 cron으로 매일 새벽 세 시에 돌게 등록해줘.",
    "앱에서 플로팅 버튼이 키보드를 가리는데 위치를 조금 올려줘.",
    "배포 서버 12번에서 nats-server CPU 사용률이 왜 높은지 원인 파악해봐.",
    "이건 급한 거 아니니까 내일 오전에 다시 확인하자.",
]

def load_mp3(path):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", path, "-f", "s16le", "-ac", "1", "-ar", str(SR), "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.int16).astype(np.float32) / 32768

async def tts(text, voice, path):
    import edge_tts
    await edge_tts.Communicate(text, voice).save(path)

base = []  # (name, audio, ref)
# --- code-switched TTS: 12 clips of 2 sentences, alternating voices ---------------------
order = SENTENCES[:]; rng.shuffle(order)
for i in range(12):
    voice = ["ko-KR-SunHiNeural", "ko-KR-InJoonNeural"][i % 2]
    parts, ref = [], []
    for s in order[i * 2:i * 2 + 2]:
        asyncio.run(tts(s, voice, f"{OUT}/tmp.mp3"))
        parts += [load_mp3(f"{OUT}/tmp.mp3"), np.zeros(int(SR * rng.uniform(0.3, 0.7)), np.float32)]
        ref.append(s)
    base.append((f"cs{i:02d}", np.concatenate(parts), " ".join(ref)))

# --- FLEURS: 24 utterances for targets + a pool for babble -------------------------------
from huggingface_hub import hf_hub_download as dl
tsv = dl("google/fleurs", "data/ko_kr/test.tsv", repo_type="dataset")
tgz = dl("google/fleurs", "data/ko_kr/audio/test.tar.gz", repo_type="dataset")
tarfile.open(tgz).extractall(f"{OUT}/fleurs")
rows, seen = [], set()
for r in csv.reader(open(tsv, encoding="utf-8"), delimiter="\t", quoting=csv.QUOTE_NONE):
    p = f"{OUT}/fleurs/test/{r[1]}"
    if r[1] in seen or not os.path.exists(p): continue
    seen.add(r[1]); rows.append((p, r[2]))
def load(p):
    a, _ = sf.read(p, dtype="float32")
    return a.mean(axis=1) if a.ndim > 1 else a
targets, pool = rows[200:224], rows[300:340]  # not used by the other benchmarks
for i, (p, t) in enumerate(targets):
    base.append((f"fl{i:02d}", load(p), t))
pool_audio = [load(p) for p, _ in pool]

def rms(x): return float(np.sqrt(np.mean(x ** 2)) + 1e-12)

def babble(n):
    out = np.zeros(n, np.float32)
    for a in rng.sample(pool_audio, 4):
        reps = np.tile(a, n // len(a) + 1)[:n]
        out += reps / rms(reps)
    return out

clips = []
for name, a, ref in base:
    a = a / max(np.abs(a).max(), 1e-6) * 0.5  # same nominal level for every source
    conds = {"clean": a}
    b = babble(len(a))
    for snr in (10, 0):
        conds[f"babble{snr}"] = a + b * (rms(a) / rms(b)) / (10 ** (snr / 20))
    conds["quiet"] = a * 10 ** (-30 / 20) + nrng.normal(0, 10 ** (-70 / 20), len(a)).astype(np.float32)
    for c, x in conds.items():
        x = np.clip(x, -1, 1).astype(np.float32)
        sf.write(f"{OUT}/{name}_{c}.wav", x, SR, subtype="PCM_16")
        clips.append({"name": f"{name}_{c}", "cond": c, "src": name[:2], "ref": ref, "sec": len(x) / SR})
json.dump(clips, open(f"{OUT}/clips.json", "w"), ensure_ascii=False, indent=1)
print(len(clips), "clips;", sum(c["sec"] for c in clips) / 4, "s per condition")
