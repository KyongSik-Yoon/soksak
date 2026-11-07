# 포크 변경 이력

최신이 위. 항목마다 날짜, 커밋, 사용자 관점의 변화, 사용자가 폰에서 확인한 결과(있으면).
새 작업을 끝내면 맨 위에 추가한다.

## 2026-11-07 — 포크 시작부터 build-17까지 (첫 세션)

### 확인된 상태 (사용자 폰, Galaxy, RAM 10.8GB)
- build-17 사용 중. 앱 내 업데이트 동작 확인 (Play 프로텍트 "검사 없이 설치?" + 지문 확인 후 설치).
- 화면 없이 녹음하기 동작, 입력창 포커스 유지됨.
- 활성 모델: Large v3 Turbo ACFT (q4). audio_ctx 하한을 128로 낮춘 뒤 속도 "상당히 빨라짐".
- Turbo q5_0은 "너무 느리고 품질도 안 좋다" → 제거.

### 커밋
- `9eacd90` Turbo q5_0 제거, q4를 "Large v3 Turbo ACFT"로 개명. 받아 둔 q5 파일은 자동 삭제.
- `87695f9` 설정의 Version 줄 정리 (`1.6.0 · build N`).
- `e5e5fef` 업데이터: installer of record일 때만 무확인 설치 요청 (Galaxy에서 `INSTALL_FAILED_ABORTED: Self update…`).
- `a0a0fe2` Turbo 모델을 토큰으로 Release에서 직접 다운로드 (서명 URL로 리다이렉트, 이어받기 지원).
- `9910240` 앱 내 업데이트: Home 업데이트 창 + 설정 Updates(토큰, Check/Update).
- `49deb39` CI가 main 빌드마다 Release `build-<run>`에 APK 게시, versionCode `1000+run`.
- `04bd7d9` Recommended 배지가 세로로 깨지던 레이아웃 수정.
- `e007b58` ACFT 모델 audio_ctx 하한 128 (기존 768), Turbo q4_0 항목.
- `b4bbcf7` Turbo ACFT, 한글 조사 인식 별칭 교체, Style example 프롬프트, Compare models 화면.
- `0032e1a` `convert-model.yml`: HF 체크포인트 → ggml 양자화 → Release.
- `d4cdf36` Whisper 전용화: Parakeet, Hinglish, LLM(polish/rewrite), Smart cleanup, `:llm` 모듈 제거. APK 83MB → 34MB.
- `67de796`, `0f5bce4`, `3587be9` 고정 디버그 서명 키 (공개 노출된 첫 키는 교체). 저장소 비공개 전환 + 포크 분리.
- `cee6460` 플로팅 버튼 서비스에서 화면 없이 녹음 (microphone FGS), 단어장 상한 320자.
- `4552a3a` 한국어: beam 5 + 자연 분할, `용어:` 접두, 기억한 입력창으로 재삽입.
- `e260819`, `a784ed0` FUTO Small ACFT 모델, `.dev` 앱 ID, CI APK 아티팩트.
