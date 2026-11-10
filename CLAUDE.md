# 속삭 (Soksak) — Sam의 OpenWispr 포크 (KyongSik-Yoon/soksak, 공개)

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
- 원본: RohitAg13/openWispr (MIT). 포크 네트워크에서 분리(detach). 2026-11-10 공개 전환(저작권 점검·고지·서명 키 교체·앱 ID 변경 후). 원본 업데이트는 `upstream` remote로 수동 반영.
- 이름: 2026-11-10 OpenWispr → **속삭(Soksak)**으로 바꿨다. 원작이 Play 스토어에 같은 이름으로 있어 혼동을 피하고, README 맨 위에 포크임을 밝힌다.
  저장소도 `soksak`으로 rename(옛 주소는 GitHub가 리다이렉트). 아이콘은 말풍선+음파 마크(`ic_mark`), 런처·스플래시 배경은 자주색 #3B2F4A.
  앱 색도 원작의 코랄 "muted sunset"에서 보라 계열 "dusk"(`android/app/src/main/java/com/voicerewriter/ui/Theme.kt`, 버블·모델 메뉴·레일은 같은 값을 자체 상수로)로 바꿨다 — 원작과 똑같아 보이지 않게. 레이아웃·폰트는 원작 것 유지.
  화면 문구만 바꾸고 저장·등록된 식별자는 유지: `OpenWisprAccessibilityService`(접근성 활성화가 컴포넌트 이름에 묶임), 로그 태그 `OpenWisprA11y`,
  Kotlin 패키지 `com.voicerewriter`. 앱 ID는 공개 준비 때 서명 키와 함께 바꿨다(아래 빌드·배포 절).
- 확정된 결정
  - 음성 인식: Whisper만 쓴다 — CPU(whisper.cpp, `android/lib`)와 NPU(sherpa-onnx QNN). 기본 Tiny/Base/Small과 SenseVoice는 쓰지 않아 2026-11-09 제거했다(`ModelDownloader`의 RETIRED 목록이 폰에서 파일을 지운다). Moonshine(tiny→Base Korean)은
    2026-11-08 추가했다가 2026-11-09 제거 — 라우팅 벤치에서 소음에 매우 약하고(0dB 97% CER) 다른 모델 대비 장점이 없었다. Parakeet, Hinglish, LLM(AI polish, Rewrite), Smart cleanup(규칙 기반 정리)은 제거 상태 유지.
  - 기본 언어 한국어. 한국어+영어 개발 용어가 섞인 발화가 주 사용 사례.
  - Turbo q5_0은 느리고 이득이 없어 제거, q4_0만 유지.
  - GPU 가속은 Vulkan, OpenCL 모두 CPU보다 느려 제거(아래 GPU 절). 다시 시도하지 않는다.
  - Android 전용. macOS 앱은 사용자가 맥을 쓰지 않아 2026-11-10 지웠고, 원본의 홍보 사이트·Play 스토어
    메타데이터(fastlane)·태그 릴리스 워크플로·브랜드 자료도 함께 지웠다. 되살리지 않는다.
    벤치·모델 패키징 워크플로 변경은 CI를 돌리지 않는다(paths-ignore).
  - 홈페이지: `docs/index.html` 한 파일(빌드 없음)을 GitHub Pages(main의 /docs)로 낸다 — https://kyongsik-yoon.github.io/soksak/ (2026-11-10, 저장소 homepage도 이 주소).
    한 가지 차분한 화풍(고운바탕 제목, dusk 색, 둥근 시트가 겹쳐 넘어감; 첫 화면은 소리 파형이 마크를 지나 글 리본으로 나오는 그림). 같은 분야 제품(Wispr Flow 등)의 배색·문구를 닮게 만들지 않는다.
    이전 판은 `docs/scenes.html`(장마다 화풍이 다른 판)과 `docs/classic.html`(첫 버전)로 보존.
    dusk 팔레트·마크를 그대로 쓰고, 숫자(CER 등)는 이 문서의 벤치 값과 맞춘다. 기능·모델이 바뀌면 함께 고친다. `docs/**`만 바뀐 커밋은 CI를 돌리지 않는다.

## 빌드·배포 흐름 (폰에 올리는 빌드는 CI가 만든다)
로컬 확인: SDK는 `~/Android/Sdk`(`android/local.properties`, gitignore), JDK 25(CI와 동일, 컴파일 대상은 Java 17) → `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`.
로컬 APK에는 sherpa JNI가 없어(CI `sherpa-qnn` 잡 산출물) NPU 모델은 동작하지 않는다.
1. `main`에 push(`*.md`, `.claude/**`, `scripts/**`, `docs/**`만 바뀐 경우 제외) → `.github/workflows/ci.yml`의 "Android build & unit tests"가 `:app:assembleDebug :app:testDebugUnitTest` 실행 (~4.5분).
2. 성공하면 Release `build-<run_number>`에 `soksak-dev-<run>.apk`를 올리고 최근 5개만 남긴다.
   versionCode = `1000 + run`, versionName = `1.6.0-dev.<run>` (`android/app/build.gradle.kts`).
  설정 화면에는 `1.6.0 · build N`으로 보인다. `1.6.0`은 원본 기반 버전 그대로 두기로 결정(2026-11-08) — 바꾸지 않는다.
3. 폰의 앱이 Home 진입 시 새 빌드를 감지해 업데이트 창을 띄운다 (`UpdatePrompt.kt`, `AppUpdater.kt`).
- 의존성: `settings.gradle.kts`가 Google의 Maven Central 미러를 Central보다 먼저 쓴다. Central은 Kotlin 컴파일러 jar를 GitHub
  릴리스 다운로드로 넘기는데, 그게 504를 내던 2026-11-08 저녁 내내 CI가 실패했다. sherpa AAR도 `gh release download`(토큰)로 받는다.
- 상태 확인: `scripts/ci-wait.sh` (커밋 SHA의 CI를 기다려 결과·APK 링크·실패 로그 출력).
- 서명: 저장소 밖의 고정 키(사용자 PC `~/.config/soksak-signing/`, alias `soksak`, 비밀번호 파일 포함 — 잃으면 다시 재설치)로 서명 → 덮어 설치 가능.
  CI는 Secret `SOKSAK_KEYSTORE_BASE64`·`SOKSAK_KEYSTORE_PASSWORD`를 풀어 `SOKSAK_KEYSTORE_FILE`/`_PASSWORD`로 넘기고, main push에 Secret이 없으면 실패한다.
  로컬 빌드는 PC 기본 디버그 키라 폰에 업데이트로 못 깐다. 예전 커밋 키(debug.keystore, pw android)는 공개 전 2026-11-10 교체·삭제했다.
- 앱 ID: `io.github.kyongsikyoon.soksak` (2026-11-10, 이전 `com.voicerewriter.dev` — 바꿀 때 폰에서 새 앱으로 설치, 설정 → General 백업/복원으로 단어장·설정을 옮김).

## 모델
- 목록: `WhisperModelManager.MODELS` — `small-acft`(FUTO, 기본), `turbo-acft-q4`(Large v3 Turbo ACFT q4_0), `small-acft-npu`, `medium-npu`.
- 라우팅 벤치(`route-bench.yml`, 깨끗함/카페소음 10dB/0dB/아주 작은 소리, CER): Turbo 12.0/15.2/63.2/12.7%,
  Small ACFT 13.1/19.5/56.4/16.3%(Turbo의 1/5 시간), SenseVoice 20.6/27.8/51.7/21.4%(가장 빠름), Moonshine Base 21.4/40.9/97.5/38.7%.
  소리 크기·SNR 기반 자동 선택은 아직 검증 안 됨(첫 규칙은 SNR 기준값이 틀려 발동 안 함). 사용자 경험: 속삭임은 Whisper(Small ACFT)가 잘 들음.
- 벤치마크: `.github/workflows/bench-models.yml` + `scripts/bench_models.py` — FLEURS ko_kr test 40문장(539초)을 앱과 같은 설정으로.
  2026-11-08 결과(CER / x86 4스레드 RTF): Turbo 3.3%/2.43, Moonshine Base 5.0%/0.20, Small ACFT 5.7%/0.53, SenseVoice int8 7.1%/0.14,
  SenseVoice fp32 7.3%/0.17(→ 제거), Moonshine tiny 7.7%/0.13. 낭독체라 개발 용어 섞인 발화는 반영 못 함. 속도 비율만 폰에 옮겨진다. `engine` 필드(`SpeechEngine`)로 런타임을 나눈다(`OnDeviceStt`가 분기).
- sherpa 모델(`LocalSherpaStt.kt`): NPU용 tar.bz2 번들을 이 저장소 Release에서 받아 `models/<bundle>/`에 필요한 파일만 풀고
  압축본은 지운다(commons-compress). sherpa는 AAR을 쓰지 않는다(2026-11-09): Kotlin API를 `android/app/src/main/java/com/k2fsa/sherpa/onnx`에 vendoring(sherpa@347ec76),
  JNI .so는 CI `sherpa-qnn` 잡이 같은 커밋으로 빌드해 `app/src/main/jniLibs/arm64-v8a`(gitignore)에 넣는다. 둘의 커밋이 다르면 JNI가 필드를 못 찾는다.- ACFT 모델(`acft = true`)은 audio_ctx 하한 128로 클립 길이에 맞춰 돌린다 (일반 모델은 768). `jni.c`의 `audio_ctx_floor`.
  일반 Whisper를 짧은 audio_ctx로 돌리면 반복·붕괴한다 — 하한을 낮추지 말 것.
- Turbo는 공개 ggml이 없어 `.github/workflows/convert-model.yml`(workflow_dispatch)로 HF safetensors → ggml → 양자화 후
  이 저장소 Release(`model-*`, pre-release)에 올린다. 앱이 받는다(`releaseTag` 필드, 공개라 토큰 불필요).
- **Medium (NPU)**(`medium-npu`, `SpeechEngine.QNN_WHISPER`): OpenAI whisper-medium의 SM8850용 QNN 컨텍스트 바이너리
  (encoder.bin/decoder.bin + tokens.txt). `.github/workflows/npu-model.yml`이 sherpa의 QNN 모델 라이브러리(`asr-models-qnn-2`의 linux-x64판)를
  sherpa `ghcr.io/k2-fsa/qnn-sdk:2.40` 이미지에서 `qnn-context-binary-generator`로 컴파일해 Release `model-qnn-whisper-medium-sm8850`(pre-release)에 올리고,
  앱이 받는다. 폰에서 모델 라이브러리를 컴파일하는 방식(build-66~70)은 디코더 그래프 최종 처리 중 프로세스가 끝나 버렸다 → 쓰지 않음
  (APK에서 libQnnHtpPrepare.so도 뺌). sherpa QNN 디코더는 토큰을 `입력 초 × 6`개(시작·힌트 토큰 포함)에서 끊으므로, `LocalSherpaStt.windows`로 14초 이하 창으로 자르고 `paddedForTokenBudget`로 무음을 붙여 초당 12토큰을 보장한다. 단어장 프롬프트는 넣지 않는다 — 토큰마다 디코더 1스텝(창마다 반복, 단어장을 채우면 Medium 31초 기준 약 12초 추가)이라 만들지 않기로 결정(2026-11-10). 단어장 사후 교정(`VocabCorrector.correct`)은 NPU에도 적용된다. 예외로 Medium 한국어에만 문장부호 힌트 6토큰(`<|startofprev|> 네, 알겠어.`, `LocalSherpaStt.qnnHint`, 패치의 `promptTokens`)을 디코드 앞에 넣는다: 힌트가 없으면 14초 창 전체에 부호가 안 찍히는 일이 있었고, PC 비교(같은 가중치, 3분 연기 음성)에서 마침표 30→52, 쉼표 11→23, 누락 없음. Small ACFT는 같은 힌트로 문장이 빠지고 반복 붕괴가 나와 적용하지 않는다. 무음에서는 `네.`/`네, 알겠습니다.`가 나오므로 Silero가 말(0.1초 이상 연속)을 못 찾은 창의 그 출력만 버린다(`isHintEcho`, 자체 인스턴스 — 음량 비교는 실제 환경의 빈 입력에서 뚫렸다). 조용히 말하기에서는 Silero가 속삭임을 못 들어 버리지 않는다. 힌트가 실제 말 앞에 붙는 경우는 "네, 알겠어."가 실제 답이기도 해 잘라내지 않는다(관측된 적 없음). CI는 sherpa 소스에 `.github/patches/sherpa-qnn.patch`를 적용한다(인코더 출력을 디코드당 한 번만 복사, 괄호·음표 같은 non-speech 토큰 억제(CPU의 `suppress_nst`와 동일), 입력·출력 버퍼를 rpcmem 한 덩어리로 등록해 실행마다 복사하지 않음(안 되면 복사로 폴백, 로그 `QNN shared memory`), `promptTokens` 필드로 지정한 토큰을 시작 토큰 앞에서 디코드, 단계별 시간·QNN 프로파일 로그 — 설정 "NPU 진단 로그"(`npuDiagnostics`, 기본 끔)를 켰을 때만 동작. 캐시 키가 패치 해시라 바꾸면 자동 재빌드).
  `NpuSupport`(SoC SM8850 = Hexagon v81)일 때만 목록에 보임. CI `sherpa-qnn` 잡이 sherpa v2.0.1 JNI를 QNN 켜고(정적 ORT, TTS 끔) 빌드해
  QNN 런타임(libQnnHtp/HtpPrepare/System/HtpV81Stub/Skel)과 함께 jniLibs로 넣는다(actions/cache).
  `OfflineRecognizer.prependAdspLibraryPath(nativeLibraryDir)`가 없으면 HTP 오류 1008. 매니페스트 `<uses-native-library libcdsprpc.so>`가
  없으면 deviceCreate 14001 → sherpa QNN 코드가 **프로세스를 exit**한다(build-66에서 버블 warm 때문에 실행할 때마다 꺼짐).
  `NpuLoadGuard`: 모델 폴더의 `.npu-loading` 표식이 다음 로드 때 남아 있으면 그 빌드에서는 NPU 모델 로드를 거부(재다운로드나 새 빌드로 해제).
- **Small ACFT (NPU)**(`small-acft-npu`): FUTO 가중치를 `.github/workflows/npu-acft.yml`이 sherpa 방식으로 SM8850 컨텍스트 바이너리로 만든다(`scripts/npu_acft_export.py`가 `whisper.load_model`을 ACFT 가중치로 바꿔 텐서 접두어 `small`을 유지, `scripts/npu_compile_sm8850.sh`가 QAIRT 이미지에서 컴파일). Release `model-qnn-whisper-small-acft-sm8850`. audio context는 30초 고정 그대로.
- 디바이스 추천: `DeviceFit.kt` — 언제나 small-acft(가장 작은 모델), 저장공간 부족만 미리 알린다.

## GPU — 두 번 시도, 둘 다 제거 (2026-11-08)
- Vulkan(build-30): 사용자 폰(스냅드래곤)에서 CPU보다 훨씬 느리고 발열 → 제거.
- OpenCL + Adreno 커널(build-35~39): 크래시 2건(토큰 임베딩 51866행 업로드, 지연 초기화 전 get_alignment)과 빈 결과(flash attention)를
  고쳐 동작까지 갔으나 CPU보다 느림 → 제거. 경위와 패치는 `.claude/CHANGELOG.md`와 커밋 e4c51c4~b71a8b8에 있다.
- 결론: 이 앱의 Whisper는 GPU를 쓰지 않는다. 속도는 모델 선택과 NPU로 해결한다.

## 인식 품질 관련 장치
- 비영어 디코딩: beam 5 + 자연 분할 (`LocalWhisperStt.kt`).
- 프롬프트 = 단어장(한국어면 `용어:` 접두) + 사용자 "Style example" 문장 (`buildPrompt`, `localizeGlossary`). 단어장 상한 320자.
- 단어장 별칭: 한글 별칭에 조사/하다 어미가 붙어도 교체하고 어미는 보존 (`textproc/VocabCorrector.kt`의 `particleHit`).
  한글에는 Soundex 가산점을 주지 않는다.
- 환각 거르기(`textproc/HallucinationFilter.kt`, 모든 Whisper 결과): 거의 빈 클립에서 Whisper가 프롬프트(Style example·단어장)
  끝을 그대로 내거나 "이 시각 세계였습니다" 같은 방송 상투구를 낸다 → 프롬프트 조각(6자 이상)이면 버리고 상투구 문장은 지운다.
- 모델 비교: 설정 → Compare models (`CompareActivity.kt`), 저장된 녹음(`PendingAudio`)을 모든 모델로 재실행.

## 녹음·입력 구조
- 플로팅 버튼 서비스(`BubbleService.kt`)가 앱 UI가 보일 때 **microphone 타입 FGS**로 승격(`enterForeground`, `micReady`).
  이후 `HeadlessDictation.kt`가 액티비티 없이 녹음→변환→삽입 → 대상 앱이 포커스/키보드를 잃지 않는다.
  자동 중지 대기: 설정 "Auto-stop wait"(탭할 때마다 1.6/3/5초, `AUTO_STOP_CHOICES`, `AudioRecorder.start(hangoverSeconds)`).
  재부팅 직후 등 `micReady=false`면 `RewriteActivity`(투명 시트)로 폴백. 0.7초간 마이크가 디지털 0이면 막힌 것으로 보고 폴백.
- 모델 전환: 버블을 **위로 휙 밀면**(250ms 안에 36dp 이상, 수직 위주; `isFlickUp`) `ModelPicker.kt` 오버레이 메뉴가 뜬다
  (FLAG_NOT_FOCUSABLE이라 대상 앱 포커스 유지, 바깥 터치·6초 무동작이면 닫힘). 고르면 `sttModel` 저장 + 백그라운드 warm.
  버블 아래쪽 작은 탭에 현재 모델 약자(`WhisperModel.badge`: SA T SN MN)를 표시. 느린 끌기는 그대로 위치 이동.
- **조용히 말하기**(설정 "Quiet mode", 버블 메뉴 맨 위 행): 받아쓰기 때 `Settings.forDictation()`이 모델을 `quietModel`
  (기본 Small ACFT)로 바꾸고, `AudioRecorder.start(quiet = true)`가 Silero 대신 `QuietSpeechDetector`(소음 바닥 대비 2.5배·절대 3e-4 이상을
  말로 봄)로 자동 중지하며 녹음을 자르지 않는다(속삭임을 Silero가 못 잡아서). 켜져 있으면 배지가 청록색(보라 마크와 구분), 메뉴에서 고른 모델은 quietModel에 저장.
- 버블 축소: 3.5초 무활동이면 작아지고(`scheduleShrink`), 다른 앱에서 타이핑(text changed/selection/focus 접근성 이벤트,
  `noteTyping`)하거나 입력창에 다시 들어오면 커진다. WINDOW_CONTENT_CHANGED는 활동으로 치지 않는다(스트리밍 응답이 계속 발생시킴).
- **말하는 동안 변환**(`StreamingWhisper.kt`, 설정 "Transcribe while you talk", 기본 켬, CPU·NPU 공통, 헤드리스 경로만):
  녹음 중 0.5초 쉼에서(조각 5초 이상일 때) 잘라 바로 디코드하고, 앞 텍스트 끝 200자를 프롬프트 끝에 붙인다(`buildPrompt`의 previous, NPU는 프롬프트 미지원이라 없음).
  멈추면 꼬리만 디코드(자동 중지 무음은 잘라 마지막 소리 뒤 0.4초까지, `tailEnd`). 소리 0.3초 미만 조각은 건너뜀. 조각 하나라도 실패하면 전체를 통째로 다시 디코드.
  근거: `chunk-bench.yml` — 15초 코드스위칭 CER 22.6→23.8%(동급), 30초+ 및 쉼 많은 실음성은 통째 디코드가 문장을 통째로 빠뜨림
  (45→14%), 멈춘 뒤 대기 ~20초→~3초. Retry/Compare는 여전히 통째로. 녹음 원본은 `AudioRecorder.rawCopy`.
- 삽입: `OpenWisprAccessibilityService.kt` — 포커스된 입력창, 없으면 마지막으로 기억한 입력창(`lastEditable`)에 SET_TEXT 후 재포커스.

## 앱 내 업데이트
- `AppUpdater.kt`: Releases API(토큰은 선택 — 공개 저장소는 익명 호출, 시간당 60회/IP. 비공개일 때만 설정의 fine-grained 토큰 Contents: Read-only 필요. 2026-11-10 공개 준비로 필수 → 선택. 모델 다운로드도 같은 경로) → APK를 캐시에 받고
  FileProvider(`update_paths.xml`) URI로 **시스템 패키지 설치 관리자**를 연다(ACTION_VIEW, 브라우저 다운로드와 같은 경로).
- 설정 Updates 카드는 열 때 자동으로 Check하고 "Build N is available"을 우선 표시한다.
- 앱이 직접 PackageInstaller 세션을 만드는 방식은 쓰지 않는다. 사용자 폰(Galaxy)에서 그 방식은 "self update"로 분류돼,
  설치된 앱의 installer of record가 이 앱 자신이면 `INSTALL_FAILED_ABORTED: Self update is blocked by unknown source package`로
  거부됐다(웹 설치 후 첫 업데이트는 성공, 두 번째부터 실패). USER_ACTION_REQUIRED를 명시해도 같았다.
- Play 프로텍트 "검사 없이 설치?"와 지문 확인은 시스템 설치 관리자 경로의 OS 보안 단계라 그 경로로는 못 없앤다.
- **Shizuku 무확인 업데이트**(`ShizukuInstaller.kt`): Shizuku가 실행 중이고 권한이 있으면 APK를 stdin으로 `pm install -r -S`
  (셸 사용자 = ADB 설치)해서 확인 화면 없이 설치. 아니면 위 시스템 설치 관리자로 폴백. 설정 Updates 카드에 상태 행.
  `Shizuku.newProcess`는 13.x에서 private이라 리플렉션으로 호출. 매니페스트에 ShizukuProvider와 `<queries>`.
  `setRequestUpdateOwnership`/USER_ACTION_NOT_REQUIRED 세션은 위의 self update 차단 때문에 이 폰에서 해법이 아니다.

## 언어
- 설정 화면은 한국어 지원(`I18n.kt`): 호출부는 영어 그대로 두고 `tr("English")`/`trf("…%s…", x)`로 감싼다. 한국어는
  `I18n.KO` 맵에서 영어 문장을 키로 찾고, 없으면 영어. 템플릿은 `%s`만(위치 인자 `%1$s`는 Kotlin 템플릿과 충돌).
  언어: 휴대폰 따름/한국어/English(설정 → General → Language, `Settings.uiLanguage`). 다른 화면(홈, 온보딩 등)은 아직 영어.
  설정 문자열을 추가하면 `I18n.KO`에도 넣는다(`I18nTest`가 자리표시자 수를 검사).

## 작업 규칙
- 커밋 작성자: `KyongSik-Yoon <sam1287@gmail.com>` — 저장소 로컬 git config에 설정돼 있어 그냥 `git commit`하면 된다(2026-11-10부터, 공개 저장소라 회사 메일을 쓰지 않는다). 세션이 요구하는 attribution 줄을 메시지 끝에 붙인다.
- push 전에 제거·이름변경한 심볼을 `grep -rn`으로 전부 확인 (CI 한 번이 ~5분).
- 새 기능에는 가능한 범위의 JVM 단위 테스트를 `android/app/src/test`에 추가.
- Settings에 필드를 추가하면 `Settings.kt`(필드·Key·read·write)와 `SettingsActivity.snapshot()`을 함께 고친다
  (snapshot이 UI 상태로 Settings 전체를 다시 만들기 때문에 빠지면 기본값으로 덮인다).
- APK에 들어가는 라이브러리·폰트·네이티브 바이너리를 추가하거나 빼면 `android/app/src/main/assets/third_party_notices.txt`(설정 → General → Open source licenses, `ThirdPartyNoticesTest`)를 함께 고친다. 라이선스가 MIT/Apache/OFL가 아니면 배포 조건을 먼저 확인한다 — Moonshine 한국어 모델은 Community License(사본·Notice·"Powered by Moonshine AI" 표시 의무)라 Release에서 내렸다(2026-11-10).
- 비밀정보(토큰, 키)는 Claude가 웹 입력란에 직접 넣지 않는다 — 사용자가 입력.
- 저장소와 Release는 공개다. 새 Release·모델을 올리기 전에 재배포 가능한 라이선스인지 확인하고(위 고지 규칙), 비밀정보는 GitHub Secret으로만 둔다. 다른 공개 저장소·외부 공개 리소스를 새로 만드는 일은 여전히 사용자 확인을 받는다.

## 백로그
- Compare models 결과로 기본 모델 확정.
- (보류) Nemotron 3.5 스트리밍: sherpa-onnx가 다시 들어왔으니 지원 여부만 확인하면 시도 가능.
- Release `model-acft-large-v3-turbo-q5_0` 삭제 여부 (앱에서는 이미 제거).
