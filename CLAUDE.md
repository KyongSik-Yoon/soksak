# OpenWispr — Sam의 개인 포크 (KyongSik-Yoon/openWispr, private)

이 문서는 어느 세션에서든 개발을 바로 이어가기 위한 기준 문서다. 코드와 어긋나면 `/resync`로 맞춘다.
변경 이력은 `.claude/CHANGELOG.md`, 경로 검증은 `scripts/check-harness-refs.sh`.

## 세션 시작 체크리스트
1. 이 파일과 `.claude/CHANGELOG.md` 맨 위 항목을 읽는다 (마지막 상태, 사용자 폰 확인 결과).
2. `scripts/check-harness-refs.sh` — 실패하면 먼저 `/resync`.
3. 원본 반영이 필요할 때만: `git remote add upstream https://github.com/RohitAg13/openWispr.git && git fetch upstream`
   후 필요한 커밋만 cherry-pick (제거한 Parakeet/LLM 관련 변경은 가져오지 않는다).
4. 배포는 `/ship`, 모델 추가는 `/convert-model`.

## 목적과 결정 사항
- 목표: 휴대폰만으로 Claude에게 **한국어 음성 프롬프트**를 내려 개발한다. Typeless/Wispr Flow 수준의 인식과
  플로팅 버튼 사용성을 **로컬 Whisper**로 구현한다. 앱스토어 배포 없음, 개인용.
- 원본: RohitAg13/openWispr (MIT). 포크 네트워크에서 분리(detach)했고 비공개. 원본 업데이트는 `upstream` remote로 수동 반영.
- 확정된 결정
  - 음성 인식: Whisper(whisper.cpp, `android/lib`) + **Moonshine Korean, SenseVoice Small**(sherpa-onnx, 2026-11-08 사용자 요청으로
    추가 — 발열·반응속도). Parakeet, Hinglish, LLM(AI polish, Rewrite), Smart cleanup(규칙 기반 정리)은 제거 상태 유지.
  - 기본 언어 한국어. 한국어+영어 개발 용어가 섞인 발화가 주 사용 사례.
  - Turbo q5_0은 느리고 이득이 없어 제거, q4_0만 유지.
  - GPU는 Vulkan 시도 후 제거(느림·발열), OpenCL로 재시도 중(아래 GPU 절).
  - macOS 코드(`macos/`)는 건드리지 않는다. CI의 macOS 잡이 취소/실패해도 무시.

## 빌드·배포 흐름 (로컬 Android SDK 없음 → CI가 컴파일러)
1. `main`에 push(`*.md`, `.claude/**`, `scripts/**`만 바뀐 경우 제외) → `.github/workflows/ci.yml`의 "Android build & unit tests"가 `:app:assembleDebug :app:testDebugUnitTest` 실행 (~4.5분).
2. 성공하면 Release `build-<run_number>`에 `openwispr-dev-<run>.apk`를 올리고 최근 5개만 남긴다.
   versionCode = `1000 + run`, versionName = `1.6.0-dev.<run>` (`android/app/build.gradle.kts`).
  설정 화면에는 `1.6.0 · build N`으로 보인다. `1.6.0`은 원본 기반 버전 그대로 두기로 결정(2026-11-08) — 바꾸지 않는다.
3. 폰의 앱이 Home 진입 시 새 빌드를 감지해 업데이트 창을 띄운다 (`UpdatePrompt.kt`, `AppUpdater.kt`).
- 상태 확인: `scripts/ci-wait.sh` (커밋 SHA의 CI를 기다려 결과·APK 링크·실패 로그 출력).
- 서명: 디버그 빌드는 커밋된 고정 키 `android/app/debug.keystore`(pw `android`)로 서명 → 덮어 설치 가능.
  저장소가 비공개라 키도 비공개. 키를 바꾸면 폰에서 한 번 삭제 후 재설치해야 한다.
- 앱 ID: 디버그는 `com.voicerewriter.dev` (Play 원본과 공존).

## 모델
- 목록: `WhisperModelManager.MODELS` — tiny, base, small, `small-acft`(FUTO), `moonshine-ko`, `sensevoice-small`,
  `turbo-acft-q4`(Large v3 Turbo ACFT q4_0). `engine` 필드(`SpeechEngine`)로 런타임을 나눈다(`OnDeviceStt`가 분기).
- sherpa 모델(`LocalSherpaStt.kt`): sherpa-onnx 공개 Release `asr-models`의 tar.bz2를 받아 `models/<bundle>/`에 필요한 파일만 풀고
  압축본은 지운다(commons-compress). AAR `sherpa-onnx-static-link-onnxruntime-2.0.1`은 CI가 `app/libs`에 받는다(gitignore).
  Moonshine-ko는 `encoder_model.ort` + `decoder_model_merged.ort`(v2, `mergedDecoder`), 출력의 `▁` 제거.
  SenseVoice는 **2024-07-17** int8을 쓴다 — 2025-09-09판은 광둥어 튜닝이라 한국어 테스트 음성을 중국어 섞인 결과로 냈다.
  두 엔진 모두 프롬프트(단어장·Style example) 미지원, 단어장 사후 교정만 적용.
  - 1.x AAR은 Moonshine v2가 ~7초 넘는 입력에서 빈 결과를 낸다(encoder_attention_mask 버그, sherpa-onnx #3976, 2.0.0에서 수정). AAR을 2.0 미만으로 내리지 말 것.
- ACFT 모델(`acft = true`)은 audio_ctx 하한 128로 클립 길이에 맞춰 돌린다 (일반 모델은 768). `jni.c`의 `audio_ctx_floor`.
  일반 Whisper를 짧은 audio_ctx로 돌리면 반복·붕괴한다 — 하한을 낮추지 말 것.
- Turbo는 공개 ggml이 없어 `.github/workflows/convert-model.yml`(workflow_dispatch)로 HF safetensors → ggml → 양자화 후
  이 저장소 Release(`model-*`, pre-release)에 올린다. 앱은 GitHub 토큰으로 받는다(`releaseTag` 필드). 토큰이 없으면 Import.
- 디바이스 추천: `DeviceFit.kt` (6GB+ → small-acft).

## GPU (OpenCL) — 실험적, 설정 "GPU acceleration", Turbo만
- 1차 Vulkan 시도(build-30)는 사용자 폰(스냅드래곤)에서 CPU보다 훨씬 느리고 발열이 심해 제거(build-31). Vulkan으로 돌아가지 않는다.
- 2차: ggml **OpenCL** 백엔드 + Qualcomm Adreno 커널. 두 번째 네이티브 라이브러리 `libwhisper_gpu.so` = 업스트림 whisper.cpp **v1.8.7**
  (`android/lib/src/main/jni/whisper-gpu/CMakeLists.txt`). CPU용 `libwhisper*.so`(vendored 1.7.4)는 그대로.
  같은 `jni.c`를 `OPENWISPR_JNI_GPU`로 한 번 더 컴파일해 JNI 이름만 `WhisperLibGpu`로 바꾼다(`JNI_FN` 매크로).
- 소스는 vendoring하지 않고 CI 단계 "Fetch GPU (OpenCL) build deps"가 받는다: whisper.cpp 태그, Khronos OpenCL-Headers,
  NDK로 빌드한 OpenCL-ICD-Loader의 `libOpenCL.so`(**링크 전용**). env `OPENWISPR_GPU_DEPS`가 없으면 GPU 라이브러리 없이 빌드.
- 런타임엔 폰의 `/vendor/lib64/libOpenCL.so`를 쓴다: manifest `<uses-native-library android:name="libOpenCL.so" required=false>`,
  그리고 app `packaging.jniLibs.excludes += "**/libOpenCL.so"`(APK에 들어가면 진짜 드라이버를 가려 GPU를 못 찾는다).
- 런타임 코드: `WhisperGpu`(로드·장치명·`loadError`), `LocalWhisperStt.GPU_MODELS`(Turbo만), `loadContext`(모델+백엔드별 캐시,
  실패 시 CPU 폴백, `lastBackend`). 드라이버 크래시는 Kotlin에서 못 잡으므로 `GpuGuard`가 GPU 호출 전후 디스크 플래그로 감지해
  다음 실행에 설정을 끈다. Compare는 GPU가 켜져 있으면 Turbo를 CPU/GPU 둘 다 돌리고 로드 시간을 따로 표시.

## 인식 품질 관련 장치
- 비영어 디코딩: beam 5 + 자연 분할 (`LocalWhisperStt.kt`).
- 프롬프트 = 단어장(한국어면 `용어:` 접두) + 사용자 "Style example" 문장 (`buildPrompt`, `localizeGlossary`). 단어장 상한 320자.
- 단어장 별칭: 한글 별칭에 조사/하다 어미가 붙어도 교체하고 어미는 보존 (`textproc/VocabCorrector.kt`의 `particleHit`).
  한글에는 Soundex 가산점을 주지 않는다.
- 모델 비교: 설정 → Compare models (`CompareActivity.kt`), 저장된 녹음(`PendingAudio`)을 모든 모델로 재실행.

## 녹음·입력 구조
- 플로팅 버튼 서비스(`BubbleService.kt`)가 앱 UI가 보일 때 **microphone 타입 FGS**로 승격(`enterForeground`, `micReady`).
  이후 `HeadlessDictation.kt`가 액티비티 없이 녹음→변환→삽입 → 대상 앱이 포커스/키보드를 잃지 않는다.
  재부팅 직후 등 `micReady=false`면 `RewriteActivity`(투명 시트)로 폴백. 0.7초간 마이크가 디지털 0이면 막힌 것으로 보고 폴백.
- 모델 전환: 버블을 **위로 휙 밀면**(250ms 안에 36dp 이상, 수직 위주; `isFlickUp`) `ModelPicker.kt` 오버레이 메뉴가 뜬다
  (FLAG_NOT_FOCUSABLE이라 대상 앱 포커스 유지, 바깥 터치·6초 무동작이면 닫힘). 고르면 `sttModel` 저장 + 백그라운드 warm.
  버블 아래쪽 작은 탭에 현재 모델 약자(`WhisperModel.badge`: Ti B S SA M SV T)를 표시. 느린 끌기는 그대로 위치 이동.
- 삽입: `OpenWisprAccessibilityService.kt` — 포커스된 입력창, 없으면 마지막으로 기억한 입력창(`lastEditable`)에 SET_TEXT 후 재포커스.

## 앱 내 업데이트
- `AppUpdater.kt`: Releases API(사용자가 설정에 넣은 fine-grained 토큰, Contents: Read-only) → APK를 캐시에 받고
  FileProvider(`update_paths.xml`) URI로 **시스템 패키지 설치 관리자**를 연다(ACTION_VIEW, 브라우저 다운로드와 같은 경로).
- 설정 Updates 카드는 열 때 자동으로 Check하고 "Build N is available"을 우선 표시한다.
- 앱이 직접 PackageInstaller 세션을 만드는 방식은 쓰지 않는다. 사용자 폰(Galaxy)에서 그 방식은 "self update"로 분류돼,
  설치된 앱의 installer of record가 이 앱 자신이면 `INSTALL_FAILED_ABORTED: Self update is blocked by unknown source package`로
  거부됐다(웹 설치 후 첫 업데이트는 성공, 두 번째부터 실패). USER_ACTION_REQUIRED를 명시해도 같았다.
- Play 프로텍트 "검사 없이 설치?"와 지문 확인은 OS 보안 단계라 앱에서 우회할 수 없다.

## 작업 규칙
- 커밋 작성자: `-c user.name="Sam" -c user.email="sam@jennifersoft.com"`. 세션이 요구하는 attribution 줄을 메시지 끝에 붙인다.
- push 전에 제거·이름변경한 심볼을 `grep -rn`으로 전부 확인 (CI 한 번이 ~5분).
- 새 기능에는 가능한 범위의 JVM 단위 테스트를 `android/app/src/test`에 추가.
- Settings에 필드를 추가하면 `Settings.kt`(필드·Key·read·write)와 `SettingsActivity.snapshot()`을 함께 고친다
  (snapshot이 UI 상태로 Settings 전체를 다시 만들기 때문에 빠지면 기본값으로 덮인다).
- 비밀정보(토큰, 키)는 Claude가 웹 입력란에 직접 넣지 않는다 — 사용자가 입력.
- 공개 저장소/공개 리소스 생성은 자동 승인되지 않는다 — 비공개 Release + 토큰 다운로드 방식을 쓴다.

## 백로그
- GPU(OpenCL): 폰에서 속도·발열 확인 결과에 따라 유지 또는 제거.
- 자동 중지 대기 시간(현재 1.6초 고정, `AudioRecorder.HANGOVER_SAMPLES`)을 설정에서 1.6/3/5초로 선택.
- Compare models 결과로 기본 모델 확정 후 안 쓰는 모델 정리.
- (보류) Nemotron 3.5 스트리밍: sherpa-onnx가 다시 들어왔으니 지원 여부만 확인하면 시도 가능.
- Release `model-acft-large-v3-turbo-q5_0` 삭제 여부 (앱에서는 이미 제거).
