---
description: CLAUDE.md가 코드와 어긋났는지 점검하고 맞춘다
---

1. `scripts/check-harness-refs.sh` 를 실행해 사라진 파일·심볼을 찾는다.
2. 아래 사실을 코드에서 직접 확인하고 CLAUDE.md와 비교한다.
   - `WhisperModelManager.MODELS`의 id 목록, 어떤 모델이 `acft`/`releaseTag`를 갖는지
   - `jni.c`의 audio_ctx 하한, `LocalWhisperStt.kt`의 beam/segment 조건
   - `.github/workflows/ci.yml`의 Release 게시 규칙(태그 이름, 보관 개수), `build.gradle.kts`의 versionCode 규칙
   - `Settings.kt` 필드 목록 (새 설정이 문서에 반영됐는지)
   - `DeviceFit.kt` 추천 규칙
3. 차이가 있으면 CLAUDE.md를 코드 기준으로 고치고, 백로그에서 끝난 항목은 지운다.
4. 바뀐 내용을 `.claude/CHANGELOG.md`에 "문서 동기화" 한 줄로 남기고 `/ship` 절차로 반영한다.
