# 포크 변경 이력

최신이 위. 항목마다 날짜, 커밋, 사용자 관점의 변화, 사용자가 폰에서 확인한 결과(있으면).
새 작업을 끝내면 맨 위에 추가한다.

## 2026-11-08 — GPU 재시도: OpenCL(Adreno), Turbo만
- 사용자: 스냅드래곤이니 OpenCL로 해보자. Vulkan 때 인프라(설정 토글, GpuGuard, Compare 이중 실행)를 되살리고 백엔드만 OpenCL로.
- Turbo만 GPU 대상(`GPU_MODELS`). 기본 꺼짐. 설정 문구에 로드 실패 이유(`loadError`)를 그대로 보여 진단에 쓴다.
- 함정: ICD loader `libOpenCL.so`가 APK에 패키징돼 벤더 드라이버를 가림 → packaging exclude. CI는 ~5분 유지.

## 2026-11-08 — 플로팅 버튼에서 모델 전환
- 사용자 선택: 위로 휙 밀기로 메뉴 열기(탭·길게 누르기는 그대로), 버블에 모델 약자 배지.
- `ModelPicker.kt`(오버레이 카드), `BubbleService.showModelPicker/pickModel/watchModelBadge`, `BubbleView.badge`.

## 2026-11-08 — GPU(Vulkan) 가속 시도 후 제거
- build-30에서 whisper.cpp 1.8.7 + ggml Vulkan을 별도 `libwhisper_gpu.so`로 넣고 설정 토글로 제공했다.
- 사용자 폰에서 "엄청나게 느리고 폰이 죽으려 함" → 커밋 8c79ef3을 통째로 revert. CI도 ~4.5분으로 복귀.
- 참고(다시 시도할 일은 없지만): NDK API-24 libvulkan 스텁에 1.1+ 함수 없음, Gradle 안에서 shader 생성기 fork ENOMEM(vfork 패치 필요).

## 2026-11-08 — Moonshine 긴 음성 빈 결과 수정 (AAR 2.0.1)
- 폰 Compare에서 17초 녹음이 Moonshine Korean만 `(empty)`(0.6초)로 나옴.
- 원인: sherpa-onnx 1.13.3의 Moonshine v2 버그 — 디코더 encoder_attention_mask 길이가 어긋나 ~7초 넘는 입력에서 ONNX 브로드캐스트 오류 후 빈 결과.
  Python sherpa-onnx 1.13.3으로 재현(7초 정상, 12/17/19초 빈 결과), 2.0.1은 전부 정상(19초 1.0초). 업스트림 #3976에서 2.0.0에 수정.
- AAR을 2.0.1로 올림(Kotlin API 그대로). SenseVoice도 같은 AAR.

## 2026-11-08 — Moonshine Korean, SenseVoice Small 추가
- sherpa-onnx(AAR 1.13.3) 재도입, `LocalSherpaStt`. 모델 목록에서 Get으로 받기(공개 Release tar.bz2, 압축 해제).
  작업 환경에서 Python sherpa-onnx로 확인: Moonshine-ko는 한국어 샘플 2개를 정확히, 6.9초 음성을 0.37초에.
  SenseVoice 2025-09-09는 한국어를 중국어 섞어 출력 → 2024-07-17 사용(한국어 정상, 띄어쓰기가 가끔 과함).
- Compare models가 엔진별로 분기(`OnDeviceStt.transcribe`).
- 설정 Updates: 열 때 자동 Check, 새 빌드가 있으면 "Build N is available" 우선 표시(이전엔 "Installed."가 계속 남음).

## 2026-11-08 — 업데이트를 시스템 설치 관리자로
- 22→23 앱 내 업데이트가 USER_ACTION_REQUIRED인데도 `Self update is blocked by unknown source package`로 실패.
  패턴: 웹 설치 직후 첫 업데이트는 성공, 앱이 installer of record가 된 다음 업데이트부터 실패.
  → 자체 PackageInstaller 세션을 버리고 APK를 캐시에 받아 FileProvider + ACTION_VIEW로 시스템 설치 관리자에 넘김.
  이번에도 build-23에서 올라올 때는 옛 코드라 브라우저 설치 1회 필요.

## 2026-11-08 — 플로팅 버튼 유휴 축소
- `BubbleService`: 대기 상태로 3.5초간 터치가 없으면 도킹된 가장자리 쪽으로 70% 크기·50% 투명도로 줄어든다
  (Typeless 방식). 터치하거나 녹음/처리/완료 상태가 되면 즉시 원래 크기. 터치 영역(창 크기)은 그대로.

## 2026-11-08 — Gemini 앱 클립보드
- 접근성 삽입: 빈 입력창이 placeholder("Ask Gemini")를 글자로 보고하던 경우를 빈 칸으로 처리해 클립보드 없이
  SET_TEXT로 넣는다(`isShowingHintText`). 그래도 붙여넣기 경로를 타면 0.8초 뒤 클립보드를 비운다.
  사용자 보고: Claude 앱은 정상, Gemini 앱에서만 받아쓰기 텍스트가 클립보드에도 복사됨.

## 2026-11-08 — build-21
- 업데이터가 무확인 설치를 요청하지 않고 항상 `USER_ACTION_REQUIRED`로 설치 확인을 띄운다.
  build-17이 installer of record가 된 뒤 `Self update is blocked by unknown source package`로 실패했기 때문.
  build-17~20에서 올라올 때는 옛 업데이터 코드 때문에 브라우저로 한 번 직접 설치해야 한다.

## 2026-11-08 — build-19
- `a75e56a` Home 기록 카드: Delete가 세로로 한 글자씩 깨지던 하단 버튼 줄 수정. Before/After 토글은
  단어장 교정으로 글자가 바뀐 기록에만 표시, 좁으면 버튼 줄이 다음 줄로 넘어감, 버튼 글자는 줄바꿈 금지.

## 2026-11-07 — 하네스 (build-18)
- `9587cdb` CLAUDE.md, 이 변경 이력, `/ship` `/resync` `/convert-model`, `scripts/ci-wait.sh`, `scripts/check-harness-refs.sh`.
  문서·하네스만 바뀐 커밋은 CI가 빌드하지 않는다. 앱 기능 변화 없음(build-18은 ci.yml 변경으로 생긴 빌드).

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
