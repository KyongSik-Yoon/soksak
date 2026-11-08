#!/usr/bin/env python3
"""Build the audio for the chunked-vs-whole Turbo benchmark (see chunk-bench.yml).

Two sets, each a list of 20-40s clips with the reference text:
- codeswitch: dictation-style Korean with English dev terms, the way the app is actually used,
  spoken by Microsoft Edge TTS (two Korean voices). Several sentences get a "thinking" pause of
  0.7-1.2s mid-sentence, which is exactly where a pause-based cutter is most likely to split.
- fleurs: real Korean read speech (FLEURS ko_kr test), 3-4 utterances joined with 0.4-1.0s gaps.

Usage: chunk_bench_data.py <out dir>
"""
import asyncio, csv, json, os, random, subprocess, sys
import numpy as np, soundfile as sf

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
rng = random.Random(7)
SR = 16000

# "|" marks a mid-sentence thinking pause.
SENTENCES = [
    "GitLab에 MR 올리고, | worktree 정리한 다음 Claude Code로 테스트 돌려줘.",
    "배포 서버 점 12번에서 nats-server CPU 사용률이 왜 높은지 | 원인 파악해봐.",
    "ClickHouse 쿼리가 느린데 | materialized view로 바꾸면 얼마나 빨라질지 비교해줘.",
    "이번 PR은 리뷰 코멘트 반영해서 | force push 하지 말고 새 커밋으로 올려줘.",
    "Kubernetes pod가 계속 CrashLoopBackOff 나는데 로그 좀 확인해줄래?",
    "README에 설치 방법이랑 | 환경 변수 설명 추가해줘.",
    "unit test가 CI에서만 실패하는데 | 로컬에서는 통과하거든, 타임존 문제인지 봐줘.",
    "API 응답이 너무 느리니까 Redis 캐시를 앞에 두는 방향으로 설계해봐.",
    "이 함수 이름이 너무 길어서 | 좀 더 짧고 명확하게 리팩토링해줘.",
    "로그인 페이지에서 OAuth 콜백이 404가 나는데 | 라우팅 설정을 확인해줘.",
    "Docker 이미지 크기가 2기가가 넘는데 multi-stage build로 줄여볼 수 있을까?",
    "어제 머지한 커밋 때문에 | 대시보드 차트가 안 그려지는 것 같아, revert 해줘.",
    "TypeScript 타입 에러가 스무 개쯤 나오는데 하나씩 고쳐줘.",
    "JVM 힙 메모리가 계속 올라가는데 | 메모리 릭인지 heap dump 떠서 분석해봐.",
    "이 스크립트를 cron으로 매일 새벽 세 시에 돌게 등록해줘.",
    "Slack 알림 메시지 포맷을 | 좀 더 읽기 쉽게 바꿔줄래?",
    "OpenTelemetry collector 설정에서 | sampling 비율을 10퍼센트로 낮춰줘.",
    "브랜치 이름은 feature 슬래시 settings UI로 해서 새로 만들어줘.",
    "테스트 커버리지 리포트를 뽑아서 | 80퍼센트 안 되는 모듈만 알려줘.",
    "지금 열려있는 이슈 중에서 | 버그 라벨 붙은 것만 정리해서 보여줘.",
    "Gradle 빌드가 너무 오래 걸리는데 캐시 설정 좀 손봐줘.",
    "이건 급한 거 아니니까 | 내일 오전에 다시 확인하자.",
    "nginx 설정에서 타임아웃을 60초로 늘리고 | 재시작해줘.",
    "앱에서 플로팅 버튼이 키보드를 가리는데 | 위치를 조금 올려줘.",
]
VOICES = ["ko-KR-SunHiNeural", "ko-KR-InJoonNeural"]

async def tts(text, voice, path):
    import edge_tts
    await edge_tts.Communicate(text, voice).save(path)

def load_mp3(path):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", path, "-f", "s16le", "-ac", "1", "-ar", str(SR), "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.int16).astype(np.float32) / 32768

def silence(sec):
    return np.zeros(int(sec * SR), np.float32)

clips = []
# --- codeswitch: 5 clips per voice, 4-5 sentences each -----------------------------------
order = SENTENCES[:]
k = 0
for vi, voice in enumerate(VOICES):
    rng.shuffle(order)
    groups = [order[i:i + 5] for i in range(0, 20, 5)][:4] + [order[20:24]]
    for gi, group in enumerate(groups):
        parts, ref = [], []
        for s in group:
            for pi, piece in enumerate(s.split("|")):
                mp3 = f"{OUT}/tmp.mp3"
                asyncio.run(tts(piece.strip(), voice, mp3))
                parts.append(load_mp3(mp3))
                parts.append(silence(rng.uniform(0.7, 1.2) if pi == 0 and "|" in s else rng.uniform(0.4, 0.9)))
            ref.append(s.replace("|", "").replace("  ", " "))
        audio = np.concatenate(parts)
        name = f"cs_{vi}_{gi}"
        sf.write(f"{OUT}/{name}.wav", audio, SR, subtype="PCM_16")
        clips.append({"set": "codeswitch", "name": name, "ref": " ".join(ref), "sec": len(audio) / SR})
        k += 1

# --- fleurs: 8 clips of 3 utterances --------------------------------------------------------
from huggingface_hub import hf_hub_download as dl
import tarfile
tsv = dl("google/fleurs", "data/ko_kr/test.tsv", repo_type="dataset")
tgz = dl("google/fleurs", "data/ko_kr/audio/test.tar.gz", repo_type="dataset")
tarfile.open(tgz).extractall(f"{OUT}/fleurs")
rows, seen = [], set()
for r in csv.reader(open(tsv, encoding="utf-8"), delimiter="\t", quoting=csv.QUOTE_NONE):
    p = f"{OUT}/fleurs/test/{r[1]}"
    if r[1] in seen or not os.path.exists(p):
        continue
    seen.add(r[1]); rows.append((p, r[2]))
rows = rows[100:]  # not the 40 used by bench_models.py
for i in range(8):
    group = rows[i * 3:(i + 1) * 3]
    parts, ref = [], []
    for p, t in group:
        a, sr = sf.read(p, dtype="float32")
        if a.ndim > 1: a = a.mean(axis=1)
        parts += [a, silence(rng.uniform(0.4, 1.0))]
        ref.append(t)
    audio = np.concatenate(parts)
    name = f"fl_{i}"
    sf.write(f"{OUT}/{name}.wav", audio, SR, subtype="PCM_16")
    clips.append({"set": "fleurs", "name": name, "ref": " ".join(ref), "sec": len(audio) / SR})

json.dump(clips, open(f"{OUT}/clips.json", "w"), ensure_ascii=False, indent=1)
for c in clips:
    print(f"{c['name']} {c['sec']:.1f}s {c['ref'][:60]}")
