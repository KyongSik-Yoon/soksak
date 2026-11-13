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
  - 클라우드 STT(Groq/OpenAI/Custom, 원작에서 물려받음)는 2026-11-11 제거했다. 음성 인식은 온디바이스뿐이다. 저장된 `sttProvider`·`sttEndpoint`·`sttKey`는 다음 설정 저장 때 폰에서 지워진다.
  - 기본 언어 한국어. 한국어+영어 개발 용어가 섞인 발화가 주 사용 사례.
  - Turbo q5_0은 느리고 이득이 없어 제거, q4_0만 유지.
  - GPU 가속은 Vulkan, OpenCL 모두 CPU보다 느려 제거(아래 GPU 절). 다시 시도하지 않는다.
  - Android 전용. macOS 앱은 사용자가 맥을 쓰지 않아 2026-11-10 지웠고, 원본의 홍보 사이트·Play 스토어
    메타데이터(fastlane)·태그 릴리스 워크플로·브랜드 자료도 함께 지웠다. 되살리지 않는다.
    벤치·모델 패키징 워크플로 변경은 CI를 돌리지 않는다(paths-ignore).
  - 홈페이지: `docs/index.html` 한 파일(빌드 없음)을 GitHub Pages(main의 /docs)로 낸다 — https://kyongsik-yoon.github.io/soksak/ (2026-11-10, 저장소 homepage도 이 주소).
    한 가지 차분한 화풍(고운바탕 제목, dusk 색, 둥근 시트가 겹쳐 넘어감; 첫 화면은 소리 파형이 마크를 지나 글 리본으로 나오는 그림). 같은 분야 제품(Wispr Flow 등)의 배색·문구를 닮게 만들지 않는다.
    버블 장 바로 다음이 "PC로 보내기" 장(`#pc`, 2026-11-11 — 사용자에게 가장 중요한 기능이라 따로 크게): 잠긴 폰의 위젯(또는 PC 전송을 켠 버블)을 누르면 듣고, 글 조각이 점선을 타고 날아가 컴퓨터 화면의 입력창에 찍히는 시연. 두 방식을 번갈아 돌고 탭·위젯·버블을 눌러 다시 볼 수 있다. 실제 동작대로 글은 말이 끝난 뒤 한 번에 간다.
    이전 판은 `docs/scenes.html`(장마다 화풍이 다른 판)과 `docs/classic.html`(첫 버전)로 보존.
    dusk 팔레트·마크를 그대로 쓰고, 숫자(CER 등)는 이 문서의 벤치 값과 맞춘다. 기능·모델이 바뀌면 함께 고친다. `docs/**`만 바뀐 커밋은 CI를 돌리지 않는다.

## 빌드·배포 흐름 (폰에 올리는 빌드는 CI가 만든다)
로컬 확인: SDK는 `~/Android/Sdk`(`android/local.properties`, gitignore), JDK 25(CI와 동일, 컴파일 대상은 Java 17) → `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`.
로컬 APK에는 sherpa JNI가 없어(CI `sherpa-qnn` 잡 산출물) NPU 모델은 동작하지 않는다.
1. `main`에 push(`*.md`, `.claude/**`, `scripts/**`, `docs/**`, `daemon/**`만 바뀐 경우 제외) → `.github/workflows/ci.yml`의 "Android build & unit tests"가 `:app:assembleDebug :app:testDebugUnitTest` 실행 (~4.5분).
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
- 목록: `WhisperModelManager.MODELS` — `small-acft`(FUTO, 기본 — FUTO 서버의 q8_0 파일을 그대로 이 저장소 Release `model-acft-small-q8_0`에 올려 거기서 받는다, 2026-11-13), `turbo-acft-q4`(Large v3 Turbo ACFT q4_0), `medium-npu`, `turbo-acft-npu`.
- 라우팅 벤치(`route-bench.yml`, 깨끗함/카페소음 10dB/0dB/아주 작은 소리, CER): Turbo 12.0/15.2/63.2/12.7%,
  Small ACFT 13.1/19.5/56.4/16.3%(Turbo의 1/5 시간), SenseVoice 20.6/27.8/51.7/21.4%(가장 빠름), Moonshine Base 21.4/40.9/97.5/38.7%.
  소리 크기·SNR 기반 자동 선택은 아직 검증 안 됨(첫 규칙은 SNR 기준값이 틀려 발동 안 함). 사용자 경험: 속삭임은 Whisper(Small ACFT)가 잘 들음.
  실제 속삭임 10문장(폰, 2026-11-11, Compare "Run all" → logcat `SoksakBench`, 로드 제외): Small ACFT CPU 35.1% / 문장당 1.4초, Medium NPU 35.5% / 2.1초, Small ACFT NPU 38.9% / 0.7초 — Medium의 합성 "아주 작은 소리" 우위(14.4 vs 19.7%)는 실제 속삭임에선 안 나타남. 화자 1명·표본 10개, 영어 용어 음역은 오답으로 셈.
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
  (APK에서 libQnnHtpPrepare.so도 뺌). sherpa QNN 디코더는 토큰을 `입력 초 × 6`개(시작·힌트 토큰 포함)에서 끊으므로, `LocalSherpaStt.windows`로 14초 이하 창으로 자르고 `paddedForTokenBudget`로 무음을 붙여 초당 12토큰을 보장한다. 단어장 프롬프트는 넣지 않는다 — 토큰마다 디코더 1스텝(창마다 반복, 단어장을 채우면 Medium 31초 기준 약 12초 추가)이라 만들지 않기로 결정(2026-11-10). 단어장 사후 교정(`VocabCorrector.correct`)은 NPU에도 적용된다. 예외로 Medium 한국어에만 문장부호 힌트 6토큰(`<|startofprev|> 네, 알겠어.`, `LocalSherpaStt.qnnHint`, 패치의 `promptTokens`)을 디코드 앞에 넣는다: 힌트가 없으면 14초 창 전체에 부호가 안 찍히는 일이 있었고, PC 비교(같은 가중치, 3분 연기 음성)에서 마침표 30→52, 쉼표 11→23, 누락 없음. Small ACFT는 같은 힌트로 문장이 빠지고 반복 붕괴가 나와 적용하지 않는다. 무음에서는 `네.`/`네, 알겠습니다.`가 나오므로 Silero가 말(0.1초 이상 연속)을 못 찾은 창의 그 출력만 버린다(`isHintEcho`, 자체 인스턴스 — 음량 비교는 실제 환경의 빈 입력에서 뚫렸다). 조용히 말하기에서는 Silero가 속삭임을 못 들으므로 대신 `QuietSpeechDetector.hasSpeech`(방 소음 대비 음량, 0.1초 연속)로 판단한다 — 처음엔 아예 버리지 않았는데 무음에서 "네."가 남았다(2026-11-11). 힌트가 실제 말 앞에 붙는 경우는 "네, 알겠어."가 실제 답이기도 해 잘라내지 않는다(관측된 적 없음). CI는 sherpa 소스에 `.github/patches/sherpa-qnn.patch`를 적용한다(인코더 출력을 디코드당 한 번만 복사, 괄호·음표 같은 non-speech 토큰 억제(CPU의 `suppress_nst`와 동일), 입력·출력 버퍼를 rpcmem 한 덩어리로 등록해 실행마다 복사하지 않음(안 되면 복사로 폴백, 로그 `QNN shared memory`), `promptTokens` 필드로 지정한 토큰을 시작 토큰 앞에서 디코드, 단계별 시간·QNN 프로파일 로그 — 설정 "NPU 진단 로그"(`npuDiagnostics`, 기본 끔)를 켰을 때만 동작. 캐시 키가 패치 해시라 바꾸면 자동 재빌드).
  `NpuSupport`가 칩을 판별한다(SM8850 = Hexagon v81, SM8750 = v79). NPU 모델은 그 칩용 번들이 있는 것만 목록에 보인다(`WhisperModel.socs`, `availableOn`): Medium은 SM8850만, Turbo는 둘 다. CI `sherpa-qnn` 잡이 sherpa v2.0.1 JNI를 QNN 켜고(정적 ORT, TTS 끔) 빌드해
  jniLibs로 넣는다(actions/cache, sherpa의 QNN 헤더만 씀). QNN 런타임(libQnnHtp/System, v79·v81 Stub/Skel)은 Qualcomm이 Maven에 올린 `com.qualcomm.qti:qnn-runtime:2.40.0`에서 Gradle이 받고 나머지 .so는 `packaging.jniLibs.excludes`로 뺀다(2026-11-13 — 전에는 sherpa가 재배포한 묶음이었는데 라이선스 파일이 없고 Qualcomm 원본과 크기도 달랐다).
  라이선스는 Qualcomm AI Stack License(POM 표기 "Qualcomm AI Hub Model License"): 앱에 포함된 오브젝트 코드로만 배포 가능하고 **NOTICE 파일을 지우면 안 된다** → `qnnNotice` 태스크가 AAR의 NOTICE.txt를 `qualcomm_qnn_notice.txt` asset으로 꺼내 라이선스 화면 끝에 보여 준다. 버전을 올리면 헤더(sherpa `qnn-include`)·AAR·모델 컴파일 SDK를 같은 QAIRT로 맞춘다.
  `OfflineRecognizer.prependAdspLibraryPath(nativeLibraryDir)`가 없으면 HTP 오류 1008. 매니페스트 `<uses-native-library libcdsprpc.so>`가
  없으면 deviceCreate 14001 → sherpa QNN 코드가 **프로세스를 exit**한다(build-66에서 버블 warm 때문에 실행할 때마다 꺼짐).
  `NpuLoadGuard`: 모델 폴더의 `.npu-loading` 표식이 다음 로드 때 남아 있으면 그 빌드에서는 NPU 모델 로드를 거부(재다운로드나 새 빌드로 해제).
- Small ACFT (NPU)(`small-acft-npu`)는 2026-11-11 제거했다: 실제 속삭임에서 CPU판·Medium NPU보다 낮고(38.9% vs 35.1/35.5%), 단어장 프롬프트를 못 받고, 남는 장점인 속도(문장당 0.7초)는 "말하는 동안 변환" 때문에 체감이 작다. NPU는 Medium, 작은 모델은 CPU판으로 정리. `RETIRED_DIRS`가 폰의 번들을 지운다.
  그 변환 경로는 남겨 일반화했다: `scripts/npu_acft_export.py <sherpa export_onnx.py> <HF 저장소> <OpenAI 크기>`가 `whisper.load_model`을 파인튜닝 가중치로 바꿔 sherpa 방식 ONNX를 내고(HF 출력과 logit 일치 확인), `scripts/npu_compile.sh`가 QAIRT 이미지에서 `$SOC`(기본 SM8850) 칩용으로 컴파일한다. 파인튜닝한 가중치를 NPU에 올릴 때 이 경로를 쓴다.
- **Large v3 Turbo (NPU)**(`turbo-acft-npu`, 배지 TN, 2026-11-11): CPU판과 같은 Turbo ACFT 가중치를 `.github/workflows/npu-turbo.yml`이 위 경로로 SM8850 바이너리(인코더 1.35GB + 디코더 0.45GB, 받는 파일 1.59GB)로 만든다. Release `model-qnn-whisper-turbo-acft-sm8850`. sherpa는 Medium 위로 QNN 빌드를 내지 않아 두 가지를 직접 맞췄다: ① float32 인코더는 모델 라이브러리가 2GB를 넘어 x86-64 링크가 깨짐(relocation truncated) → 변환기에 `--float_bitwidth 16`(`CONVERT_ARGS`). ② large-v3 계열은 언어가 하나 늘어 transcribe·notimestamps 토큰이 한 칸 뒤(50360/50364)인데 sherpa QNN 런타임은 v2 번호 고정이고 `turbo` 접두어를 모르면 프로세스를 exit → 패치의 `HasV3Vocabulary`. 멜 128채널. 문장부호 힌트는 Medium과 같은 글에 시작 토큰만 50362(`TURBO_KO_HINT`, 2026-11-11 — 힌트 없이는 긴 문장에 부호가 거의 없었다. 같은 녹음 17개 재측정(build-125): 부호 14→26개, 빠진 문장·반복 없음, 시간 +13%; 1.2초짜리 소음 같은 녹음 하나는 "고고고"→빈 결과). NPU라 audio context 30초 고정이므로 ACFT 이점은 없다. 사용자 폰 확인(build-122, 2026-11-11): 한 번에 동작, Medium (NPU)보다 빠르고 인식률도 좋다는 체감. Run all(build-124, 최근 녹음 20개·102초, 로드 제외, 정답 전사 없음): 문장당 Turbo NPU 0.95초 / Small ACFT CPU 1.39초 / Medium NPU 2.18초. 눈으로 본 차이: Turbo NPU는 긴 문장에 문장부호가 거의 없고(힌트 없음) 용어 표기는 가장 낫다(인식률, Small NPU, ACFT); 언어가 한국어로 고정돼 있어도 실제 영어 발화("Hi, nice to meet you.")를 영어 그대로 냈다(Small ACFT·Medium은 한국어로 옮겨 씀); 18초 녹음의 끝 문장은 NPU 둘 다 틀리고 CPU만 맞음. `MODELS`의 맨 끝(폴백 `OnDeviceStt.pick`이 고르는 가장 강한 모델).
  SM8750(Snapdragon 8 Elite, Galaxy Z Fold7)용 번들도 만든다(2026-11-11, 사용자 요청): `gh workflow run npu-turbo.yml -f soc=SM8750` → Release `model-qnn-whisper-turbo-acft-sm8750`. 워크플로는 수동 실행만(칩 하나씩). 번들 폴더·Release 이름에 칩이 들어가고 앱은 `NpuSupport.bundleSoc`으로 자기 칩 것을 받는다. APK에 v79 Stub/Skel도 넣는다. CI에서 번들(1.58GB)과 `build-130`이 만들어졌다. **Fold7 실기기에서는 아직 돌려 보지 못했다** — 그 칩에서는 모델 설명에 "not yet tried on this chip"이 붙는다. 확인되면 떼고 홈페이지·README에 적는다.
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
  자동 중지 대기: 설정 "Auto-stop wait"(탭할 때마다 1.6/2.2/3/5초, `AUTO_STOP_CHOICES`, `AudioRecorder.start(hangoverSeconds)`).
  재부팅 직후 등 `micReady=false`면 `RewriteActivity`(투명 시트)로 폴백. 0.7초간 마이크가 디지털 0이면 막힌 것으로 보고 폴백.
- 받아쓰기 위젯(`DictateWidget.kt`, 1×1, `home_screen|keyguard`): 탭하면 `BubbleService`에 `ACTION_DICTATE`를 보내 버블 탭(`onTap`)과 같게 시작/중지.
  용도는 폰을 잠근 채 PC로 말 보내기라, 위젯으로 시작한 받아쓰기는 PC 전송 설정과 상관없이 `pc`(실패하면 폰 입력)로 보내고, 조용히 말하기 방식으로 녹음하되 모델은 버블이 지금 쓰는 모델(조용히 말하기가 꺼져 있으면 sttModel, 켜져 있으면 quietModel — 이미 로드된 모델이라 시작이 안 늦는다. 2026-11-11: 그전엔 항상 quietModel이라 다르면 로드를 기다렸고, 위젯 전용 모델 설정은 한 빌드 만에 뺐다)을 쓰고, 무음 2.2초면 끝낸다(`WIDGET_AUTO_STOP_S`; 전체 화면이 남은 대기를 원 둘레로 보여 줌 — `AudioRecorder.silenceProgress`)(`Settings.forWidgetTake`, 설정은 안 바꿈). 시트 폴백 경로에는 적용 안 됨.
  위젯 받아쓰기는 `LockDictationActivity`(잠금화면 위 전체 화면, showWhenLocked + 화면 켜 둠)가 상태를 보여 준다: 듣는 중/변환/결과(`DictationStatus` — `BubbleService`의 show*가 유일한 작성자),
  아무 데나 누르면 끝, 뒤로는 버림, 결과 뒤 스스로 닫힘. 받아쓴 글은 화면에 안 띄운다(잠긴 폰). 녹음은 서비스가 먼저 시작하고 화면은 보기만 하므로 화면이 안 떠도 녹음은 된다.
  마이크를 못 잡은 경우만 화면이 뜬 뒤 한 번 재시도(`widgetTakeFromScreen`).
  위젯 클릭은 백그라운드 FGS 시작이 허용돼 버블이 꺼져 있어도 서비스를 띄운다. 잠금화면(One UI)에 올려 쓰고 있다.
  앱을 업데이트하면 잠금화면 위젯이 "위젯 정보를 불러오는 중"에 멈춰 매번 다시 등록해야 했다 → `DictateWidget.refreshAll`이 패키지 교체·부팅(`BootReceiver`)·버블 서비스 시작·앱 열기 때 RemoteViews를 다시 보낸다(2026-11-11, build-124·125 업데이트에서 사용자 확인: 멈추지 않음). 멈춘 정확한 원인은 로그로 확인하지 못했다.
- 모델 전환: 버블 아래 **모델 탭(배지)을 누르거나**(`BubbleView.badgeHit`, 탭보다 넉넉한 영역; 2026-11-13 — 위로 밀기는 다른 개발자들이 어려워해서 추가, 길게 누르기는 누르는 동안 녹음이라 쓰지 않음) 버블을 **위로 휙 밀면**(250ms 안에 36dp 이상, 수직 위주; `isFlickUp`) `ModelPicker.kt` 오버레이 메뉴가 뜬다
  (FLAG_NOT_FOCUSABLE이라 대상 앱 포커스 유지, 바깥 터치·6초 무동작이면 닫힘). 위쪽은 설정 행(선 아이콘 `ic_menu_*` + 이름 + 현재 값, 이모지·글자 배지는 쓰지 않음), 구분선 아래가 모델 목록. 고르면 `sttModel` 저장 + 백그라운드 warm.
  버블 아래쪽 작은 탭에 현재 모델 약자(`WhisperModel.badge`: SA T MN TN)를 표시. 느린 끌기는 그대로 위치 이동.
- **조용히 말하기**(설정 "Quiet mode", 버블 메뉴 맨 위 행): 받아쓰기 때 `Settings.forDictation()`이 모델을 `quietModel`
  (기본 Small ACFT)로 바꾸고, `AudioRecorder.start(quiet = true)`가 Silero 대신 `QuietSpeechDetector`(500~2500Hz 대역에서 소음 바닥 대비 2.5배·절대 3e-4 이상이 0.1초 이어지면
  말로 봄, 마이크 시작 0.3초는 무시 — 전체 음량으로는 실제 폰에서 한 번도 발동 안 했다: 첫 조각이 0이라 바닥이 -90dB로 잡히고, 500Hz 아래 방 소음이 속삭임보다 15~20dB 큼. 2026-11-11, 속삭임 녹음 20개로 확인)로 자동 중지하며 녹음을 자르지 않는다(속삭임을 Silero가 못 잡아서). 켜져 있으면 배지가 장밋빛(#B4608E, 어두운 테마 #E8A3C6 — 보라 마크와 어울리되 구분됨; 처음엔 청록이었는데 보라와 안 어울려 2026-11-11 변경), 메뉴에서 고른 모델은 quietModel에 저장.
- 버블 축소: 8초 무활동이면 작아지고(`scheduleShrink`, `IDLE_SHRINK_MS`; 2026-11-13 3.5초에서 늘림 — 말할 내용을 생각하는 동안 작아졌다), 다른 앱에서 타이핑(text changed/selection/focus 접근성 이벤트,
  `noteTyping`)하거나 입력창에 다시 들어오면 커진다. WINDOW_CONTENT_CHANGED는 활동으로 치지 않는다(스트리밍 응답이 계속 발생시킴).
- 듣는 중·변환 중에는 버블 창에 FLAG_KEEP_SCREEN_ON을 건다(`keepScreenOn`; 길게 말하면 화면 꺼짐 시간이 지나 화면이 잠겼다).
- 변환 진행률(`TranscribeProgress.kt`, 2026-11-13): 멈춘 뒤 남은 오디오 × 모델의 실측 속도(조각마다 `record`, 기본값은 Run all 값)로 추정하고 조각이 끝날 때마다 실제 비율로 당긴다. 혼자서는 94%를 넘지 않고 글이 나와야 닫힌다. 버블은 PROCESSING 테두리, 잠금화면은 150dp 링으로 그린다(`DictationStatus.progress`, 헤드리스 경로만).
- **말하는 동안 변환**(`StreamingWhisper.kt`, 설정 "Transcribe while you talk", 기본 켬, CPU·NPU 공통, 헤드리스 경로만):
  녹음 중 0.5초 쉼에서(조각 5초 이상일 때) 잘라 바로 디코드하고, 앞 텍스트 끝 200자를 프롬프트 끝에 붙인다(`buildPrompt`의 previous, NPU는 프롬프트 미지원이라 없음).
  멈추면 꼬리만 디코드(자동 중지 무음은 잘라 마지막 소리 뒤 0.4초까지, `tailEnd`). 소리 0.3초 미만 조각은 건너뜀. 조각 하나라도 실패하면 전체를 통째로 다시 디코드.
  근거: `chunk-bench.yml` — 15초 코드스위칭 CER 22.6→23.8%(동급), 30초+ 및 쉼 많은 실음성은 통째 디코드가 문장을 통째로 빠뜨림
  (45→14%), 멈춘 뒤 대기 ~20초→~3초. Retry/Compare는 여전히 통째로. 녹음 원본은 `AudioRecorder.rawCopy`.
- 삽입: `OpenWisprAccessibilityService.kt` — 포커스된 입력창, 없으면 마지막으로 기억한 입력창(`lastEditable`)에 SET_TEXT 후 재포커스.
  커서 자리에 끼워 넣을 때 앞 글이 공백·줄바꿈·여는 괄호로 끝나지 않으면 한 칸 띄운다(`textproc/InsertSplice.kt`, 2026-11-11 — 이어서 받아쓰면 "있어.조용한데"처럼 붙었다).
  `findFocus`가 editable이 아닌 노드를 주면 트리를 훑어 `isFocused && isEditable` 노드를 찾는다(`focusedEditableIn`): ChatGPT(Compose)는
  다른 앱에서 돌아오면 `findFocus`가 입력창 대신 일반 `View`를 줘서 키보드가 떠 있어도 매번 클립보드로 빠졌다(build-108, 2026-11-11). 노드 캐시 비우기(build-107)는 효과가 없었다.
  포커스된 editable 노드가 아예 없는데 키보드 입력은 받고 있는 편집기(삼성 노트: 포커스가 본문을 감싼 ScrollView에 있고 EditText 노드는 focused=false — uiautomator로 확인, 2026-11-11)는 Android 13+의 접근성 입력 연결(`commitToInputConnection`, 서비스 설정의 `flagInputMethodEditor`)로 키보드처럼 commitText한다. 기존 SET_TEXT 경로가 먼저이고, 이 경로는 포커스된 editable을 못 찾았을 때만. 사용자 확인(build-129): 삼성 노트에 들어감, 로그 `inserted through the input connection`.

## PC로 보내기 (2026-11-11)
- 받아쓴 글을 사용자 PC의 클립보드로 보낸다. 폰 쪽 `PcLink.kt`, PC 쪽 `daemon/`(Rust, 의존 크레이트 없음, `daemon/README.md`).
  설정 `pcSend`(`off`/`both`/`pc`)·`pcHost`·`pcCode`, 기본 꺼짐. 주소와 코드가 모두 있어야 동작한다. 버블 메뉴에도 행이 있다(설정된 경우만).
- 프로토콜: TCP 한 번에 `SOKSAK/1 <코드> <바이트 수>[ type]\n<글>` → `OK`/`OK clipboard`/`ERR <이유>`. 길이 0은 연결 시험. 양쪽 테스트(`PcLinkTest`, `daemon/tests`)가 같은 형식을 검사한다 — 한쪽을 바꾸면 다른 쪽도.
- 클립보드냐 바로 입력이냐는 앱이 고른다(`pcType`, 설정·버블 메뉴 "PC에서"). 단 실제로 타이핑(`wtype`)할지는 PC가 정한다: 데몬 설정 `allow_type`(기본 false).
  허용 안 하면 클립보드에만 넣고 `OK clipboard`로 답해 버블에 "PC clip"이 뜬다. 허용을 폰 요청만으로 켤 수 있게 바꾸지 않는다 — 터미널에 타이핑되면 말한 글이 명령으로 실행된다(2026-11-11 결정).
  타이핑 명령은 `type_cmd`. 사용자 PC(Hyprland + kime)에서는 `wtype`이 입력기 때문에 `1234567890-=qwer…`로 깨져(가상 키맵 무시), `daemon/soksak-paste`(클립보드 + Hyprland `send_key_state`로 Ctrl(+Shift)+V)를 쓴다. 코드 기본값은 범용인 `wtype -` 유지.
- 암호화는 Tailscale에 맡긴다: 데몬은 Tailscale 대역(100.64.0.0/10)과 로컬만 받는다(`allow_lan`으로 해제). OkHttp가 아니라 raw 소켓인 이유는 Android가 100.x로 가는 평문 HTTP를 막기 때문.
- 전송 지점은 `PcLink.deliver` 하나(`HeadlessDictation`, `RewriteActivity.acceptVoice`가 호출). 단어장 교정이 끝난 최종 글을 보낸다. `both`는 폰 입력과 동시에, `pc`는 PC 실패 시에만 폰에 입력(글을 잃지 않게). `pc`에서는 버블의 "입력창에서만 보이기"를 무시한다(`gateFor`).
  결과는 버블 알약(`showPcResult`: "To PC"/"PC clip"/"No PC")으로 알린다 — 백그라운드 토스트는 안 뜬다.
- 연동 코드는 비밀정보: 백업 파일에 넣지 않고, 사용자가 폰에 직접 입력한다. 데몬 로그에는 글 내용을 남기지 않는다.
- 사용자 PC에는 `~/.local/bin/soksak-daemon` + systemd 사용자 서비스 `soksak-daemon`으로 설치돼 있다. `daemon/**`만 바뀐 커밋은 앱 CI를 돌리지 않고 `.github/workflows/daemon.yml`(cargo test·clippy)만 돈다. cargo는 `~/.cargo/bin`(PATH에 없음).

## 앱 내 업데이트
- `AppUpdater.kt`: Releases API를 익명으로 호출(시간당 60회/IP). 저장소가 공개라 GitHub 토큰 설정은 2026-11-11 지웠다(저장된 값은 다음 저장 때 폰에서 삭제). 모델 다운로드도 같은 경로 → APK를 캐시에 받고
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
- Compare models 결과로 기본 모델 확정 — Turbo (NPU)를 같은 녹음으로 재서 Medium (NPU)·Small ACFT와 비교(속삭임 10문장 포함).
- (보류) Nemotron 3.5 스트리밍: sherpa-onnx가 다시 들어왔으니 지원 여부만 확인하면 시도 가능.
- Release `model-acft-large-v3-turbo-q5_0` 삭제 여부 (앱에서는 이미 제거).
- Release `model-qnn-whisper-small-acft-sm8850` 삭제 여부 (앱에서는 2026-11-11 제거).
