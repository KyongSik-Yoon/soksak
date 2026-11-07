---
description: Hugging Face Whisper 체크포인트를 ggml로 변환해 Release에 올리고 앱 모델 목록에 추가한다
---

인자 예: `/convert-model MahmoudAshraf/acft-whisper-large-v3 q4_0`

1. 대상 모델이 ACFT(짧은 audio_ctx 학습)인지 확인한다. 아니면 앱에서 `acft = false`로 두어야 하고(하한 768),
   그 경우 속도 이득이 작다는 점을 사용자에게 먼저 알린다.
2. `.github/workflows/convert-model.yml`을 workflow_dispatch로 실행한다. 입력:
   `hf_repo`, `tokenizer_repo`(모델 저장소에 vocab.json이 없을 때), `quant`(폰은 q4_0 권장), `out_name`, `tag`(`model-…`).
   - `gh workflow run convert-model.yml -f hf_repo=… -f quant=… -f out_name=… -f tag=…` 가 되면 그것으로,
     안 되면(세션 토큰이 dispatch 권한 없음) 사용자 Chrome에서 Actions → Convert Whisper model → Run workflow.
3. 로그의 sanity check(jfk.wav를 `-ac 768`로 받아 적은 문장)가 정상인지 확인한다.
4. `WhisperModelManager.MODELS`에 항목 추가: `url = ""`, `releaseTag = <tag>`, `fileName = <out_name>`, `acft` 설정,
   `DeviceFit.sttBytes`에 크기 추가. `/ship`.
5. 사용자에게: 설정의 모델 목록에서 Get → Compare models로 기존 모델과 비교하도록 안내.
