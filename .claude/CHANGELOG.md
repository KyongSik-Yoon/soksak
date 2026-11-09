# 포크 변경 이력

최신이 위. 항목마다 날짜, 커밋, 사용자 관점의 변화, 사용자가 폰에서 확인한 결과(있으면).
새 작업을 끝내면 맨 위에 추가한다.

## 2026-11-09 — Medium (NPU)에도 말하는 동안 변환 적용 + 시간 로그
- 배경: 사용자 "NPU를 안 쓰는 것보다 느린 느낌". NPU는 멈춘 뒤에야 전체를 처리했다(Whisper 모델은 말하는 동안 미리 처리).
- 수정: `StreamingWhisper`를 NPU에도 적용. 프롬프트 미지원이라 조각 간 문맥 연결은 없다. 5초 미만 발화는 조각이 안 생겨 대기 시간이 그대로다.
- 로그(`LocalSherpaStt`): 모델 로드 ms, 구간별 디코드 ms·토큰 수(설정 → 디버그 로그 복사).

## 2026-11-09 — Compose BOM 2026.09.00(Compose 1.12.1, Material3 1.4.0), Coroutines 1.11.0
- Compose 1.12가 compileSdk 37 이상을 요구해 앱 모듈만 37로 올렸다. targetSdk는 36 유지(런타임 동작 변화 없음).
- 로컬 `:app:assembleDebug :app:testDebugUnitTest` 성공. 로컬 APK 39.6→45.8MB. 커밋 8c0c0c3 → build-75 CI 성공. UI 라이브러리 교체라 폰에서 화면 확인 필요.

## 2026-11-09 — Medium (NPU)에서 말 끝이 잘리던 문제
- 증상: "그 닷컴 비그 파일은 이제 안 쓸"처럼 끝 음절이 사라짐(사용자 스크린샷, build-73).
- 원인: sherpa QNN Whisper(`offline-whisper-model-qnn.cc`)가 토큰을 `입력 초 × 6`개(시작 토큰 4개 포함)에서 끊는다. 한국어는 음절당 약 1토큰이라 빨리 말한 짧은 문장이 걸린다.
- 수정: 입력 뒤에 무음을 붙여 초당 12토큰을 보장. 30초 입력의 상한이 176토큰이라 창은 28초에서 14초 이하로 줄였다. 커밋 ea8f290 → build-74 CI 성공.
- 결과(build-74): 사용자 폰에서 문제 없음, "전보다 확실히 나음".

## 2026-11-09 — 툴체인 업그레이드(Gradle 9.9.0, AGP 9.5.1, Kotlin 2.4.21)
- AGP 9는 Kotlin을 내장하므로 `kotlin.android` 플러그인과 `kotlinOptions`를 뺐다. Kotlin 버전은 Compose 플러그인 버전이 정한다.
- 빌드 JDK는 로컬·CI 모두 25(바이트코드 대상은 Java 17 유지). NDK r25는 유지(whisper.cpp 성능 때문에 벤치 없이 올리지 않는다). Compose BOM·Coroutines는 빌드가 요구하지 않아 그대로.
- 이 PC에 Android SDK를 설치해 로컬 빌드 가능. 업그레이드 전후 모두 `:app:assembleDebug :app:testDebugUnitTest` 성공(단위 테스트 98개).
  로컬 APK에는 sherpa JNI가 없어(CI `sherpa-qnn` 잡 산출물) SenseVoice·NPU는 CI 빌드로만 확인 가능.
- 커밋 4b08ee0, bd23ada → build-73 CI 성공. 사용자에게 보이는 기능 변화는 없다(빌드 도구만 교체).
- 결과(build-73): 사용자 폰에서 문제 없이 동작 확인.

## 2026-11-09 — NPU 모델을 CI에서 SM8850 컨텍스트 바이너리로
- 결과(build-72): 사용자 폰에서 **Medium (NPU) 정상 동작** 확인(15:18). 변환 워크플로 1회 ~50분(QNN SDK 이미지, 디스크 정리 필요).
  CI 평소 빌드 시간은 그대로(sherpa-qnn 캐시 적중 ~10초 + Android ~4.5분).
- 남은 개선: 1.48GB tar.bz2를 폰에서 Java로 푸는 데 오래 걸림 → 무압축 tar + 압축 해제 진행률 표시.
- build-70 로그(디버그 로그 복사): QNN 로드·DSP 연결·ADSP 경로는 성공, 인코더는 이전 시도에서 encoder.bin까지 만들어졌으나
  디코더 "Finalizing graph 'decoder'" 도중 프로세스가 사라짐(네이티브 크래시 기록 없음 → 종료/kill). 폰 위 컴파일을 포기.
- `npu-model.yml`로 x86에서 컴파일 → 비공개 Release, 앱 모델 항목을 releaseTag 다운로드로 교체, 옛 폴더는 RETIRED_DIRS.
- 디버그 로그: `-t`는 uid 필터 전에 줄을 세서 다른 앱 로그에 밀려 우리 줄이 사라졌다 → 전체를 읽고 끝 N줄.
- NpuLoadGuard: 설정·버블 메뉴에서 그 모델을 다시 고르면 해제.

## 2026-11-09 — build-66 NPU 모델 선택 후 앱이 켜지자마자 꺼짐 → 수정
- 원인: libQnnHtpV81Stub.so가 /vendor/lib64/libcdsprpc.so를 NEEDED로 갖는데, targetSdk 31+ 앱은 매니페스트에
  `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`가 있어야 벤더 라이브러리를 본다(sherpa 데모 매니페스트에 있음).
  deviceCreate 14001 → sherpa가 SHERPA_ONNX_EXIT로 프로세스 종료, 버블이 시작할 때 모델을 warm해서 무한 반복.
- 수정: 매니페스트 선언 + `NpuLoadGuard`(로드 중 죽으면 다음부터 그 빌드에서 거부).

## 2026-11-09 — Medium (NPU) 모델 추가
- 사용자: "진행해, 권한 모두 위임". CI `sherpa-qnn` 잡(QNN JNI 빌드 + v81 런타임) → AAR에 주입, `SpeechEngine.QNN_WHISPER`,
  `NpuSupport`, `LocalSherpaStt`의 QNN 경로(ADSP_LIBRARY_PATH, 컨텍스트 바이너리 캐시, 28초 창 분할, HallucinationFilter).

## 2026-11-09 — NPU(Qualcomm QNN) 검토 + 후보 벤치
- 사용자 폰: Galaxy S26 Ultra SM-S948N = Snapdragon 8 Elite Gen 5 for Galaxy (SM8850, HTP v81). sherpa-onnx QNN 데모 APK
  (1.12.22, SenseVoice)를 사용자가 설치 → 크래시 없이 NPU로 동작, 일반 앱의 NPU 접근 가능 확인.
- 재료: sherpa Release `asr-models-qnn`에 QNN 헤더·런타임 so(2.40), `asr-models-qnn-2`에 Whisper tiny~medium QNN 모델 라이브러리.
  turbo/large는 없음(직접 변환 필요, QAIRT는 HF csukuangfj/qnn-toolkit 미러). sherpa Kotlin API `OfflineWhisperModelConfig.qnnConfig`.
  제약: 30초 고정 입력, 프롬프트(단어장·Style example) 미지원, greedy.
- `npu-bench.yml`(라우팅 벤치 클립, CER clean/babble10/babble0/quiet, 코드스위칭 clean): Turbo ACFT 11.4/15.2/64.3/12.4 (cs 22.5),
  Turbo 원본 12.6/16.4/56.8/14.0 (23.4), **Medium 11.3/17.8/68.1/14.4 (21.4)**, Small ACFT 13.0/19.8/55.5/19.7 (24.5),
  Small 원본 15.7/21.5/65.9/18.1 (31.0), SenseVoice 20.7/28.1/51.4/21.6 (36.8).
  → Medium NPU판이 보통 환경에서 Turbo ACFT와 동급(프롬프트 없이도), 시끄러운 곳은 약함.

## 2026-11-09 — Turbo 대기 단축(꼬리 무음 제거) + 엉뚱한 결과 거르기
- 사용자: Turbo는 말하는 동안 변환을 넣었는데도 느리고, 가끔 완전히 엉뚱한 내용이 나온다. Compare 화면: 1초 클립에서
  Small ACFT "정리한 다음 Claude Code로 테스트 돌려줘."(기본 Style example 끝부분 그대로), Turbo "이 시각 세계였습니다."
- 확인: 말하는 동안 변환은 Turbo에도 적용돼 있었다(Whisper 엔진이면 전부). 다만 마지막 꼬리 조각이 자동 중지 대기(1.6~5초)
  무음까지 통째로 디코드했고, ACFT는 인코더 시간이 클립 길이에 비례해 그 무음이 멈춘 뒤 대기의 상당 부분이었다.
  Compare 시간은 통째 디코드 + 모델 교체 로드가 포함돼 실제 받아쓰기 대기보다 길게 보인다.
- 수정: `StreamingWhisper.tailEnd` — 꼬리는 마지막 소리 뒤 0.4초까지만. `textproc/HallucinationFilter` — 출력이 Style example·단어장의
  일부(6자 이상)면 버리고, 뉴스/유튜브 상투구("이 시각 세계였습니다", "시청해주셔서 감사합니다" 등)로만 된 문장은 지운다.
  모든 Whisper 경로(조각, 통째, Compare)에 적용.

## 2026-11-09 — 조용히 말하기 (Quiet mode)
- 사용자: 사무실처럼 조용한 곳에서 남에게 피해 없이 말하면서 정확도도 높이고 싶다. "속삭임은 Small ACFT가 잘 듣더라".
- 문제: Silero VAD는 속삭임을 말로 못 잡아 자동 중지가 일찍 걸리거나 앞뒤를 잘라낸다.
- 구현: 설정 `quietMode`/`quietModel`(기본 Small ACFT), `Settings.forDictation()`이 받아쓰기 때 모델을 바꿈.
  `AudioRecorder.start(quiet = true)`는 Silero를 안 쓰고 `QuietSpeechDetector`(방 소음 바닥 대비 2.5배 이상을 말로 봄)로 자동 중지,
  녹음은 자르지 않는다. 버블 위로 밀기 메뉴 맨 위에 켜고 끄기, 켜면 배지가 파란색이고 메뉴에서 고른 모델은 quietModel로 저장.

## 2026-11-09 — Moonshine Base Korean 제거
- 라우팅 벤치 결과를 보고 사용자 결정: "장점이 없다". 모델·MOONSHINE 엔진 코드·audiotext 대체 로직·package-moonshine.yml 제거,
  받아둔 파일은 RETIRED_DIRS로 정리. (Release `model-moonshine-base-ko`는 저장소에 남아 있음, 벤치 스크립트가 참조.)

## 2026-11-09 — 설정 화면 한국어
- 사용자: Shizuku 무확인 업데이트로 build-58 잘 받음. 다음: 설정 화면 한국어 지원.
- `I18n.kt`(영어 키 → 한국어 맵, `tr`/`trf`), SettingsActivity의 문자열 ~110개 감쌈, General → Language(휴대폰/한국어/English).
  모델 이름·크기 설명, 받아쓰기 언어 이름, 다른 화면은 영어 그대로.

## 2026-11-08 — 자동 중지 대기 시간 선택 (Shizuku 무확인 업데이트 첫 실전)
- 사용자: Shizuku 연동 잘 됨, 간단한 개선 하나로 버전 올려 확인해보자 → 백로그의 자동 중지 대기(1.6/3/5초).

## 2026-11-08 — Shizuku로 무확인 업데이트
- 사용자: 말하는 동안 변환 "잘 된다". 업데이트마다 Play 프로텍트 스캔 확인 + 지문이 번거로움.
- 검토: Play 프로텍트 끄기(설정만), 동적 패치(DexClassLoader — 매니페스트·네이티브 못 바꿔 부적합), setRequestUpdateOwnership
  (Samsung self update 차단으로 불가), Obtainium+Shizuku(앱 수정 없음). 사용자 선택: 앱 Update 버튼에 Shizuku 연동.
- 구현: `ShizukuInstaller`(state/requestPermission/install), `AppUpdater.downloadAndInstall`에서 READY면 Shizuku, 실패 시 시스템 설치.

## 2026-11-08 — 말하는 동안 변환 (Whisper, 쉼에서 조각)
- 사용자: Turbo가 가장 정확하지만 긴 문장일수록 느림. 측정: 인코더가 클립 길이에 비례, 빔은 거의 무관.
- 벤치(chunk-bench.yml, TTS 코드스위칭 + FLEURS, 짧은/긴): 조각 방식이 짧은 클립은 동급, 긴/쉼 많은 클립은 훨씬 정확,
  대기 ~3초. 통째 디코드는 30초 넘는(실음성은 20초에서도) 녹음에서 문장을 통째로 빠뜨리는 현재 버그도 드러남.
- 구현: `StreamingWhisper`, `AudioRecorder.rawLength/rawCopy`, `LocalWhisperStt.transcribe(previousText)`, 설정 `pipelinedWhisper`.

## 2026-11-08 — 브라우저 검색창(네이버)에서 클립보드로만 복사됨
- 증상: m.naver.com 검색창(웹 입력) 활성 상태에서 받아쓰기 → 버블 "Copied"(give-up 분기).
- 추정: 웹 입력은 placeholder를 text로 내고 isShowingHintText를 안 세움 → 비어있지 않은 필드+커서 불명 → paste 경로 → paste 실패 → 재시도 끝 클립보드.
- 수정: text == hintText면 빈 칸으로 취급(`currentText`), paste도 실패하면 SET_TEXT로 끝에 덧붙이기(`appendAtEnd`),
  포커스된 입력창을 active window뿐 아니라 모든 창에서 찾기. 그래도 클립보드로 가면 이유를 Settings → Reliability
  "Last clipboard fallback"에 남긴다(`recordFallback`).

## 2026-11-08 — 모델 벤치마크, Moonshine "audiotext" 대응, 버블 다시 커지기
- 벤치(FLEURS ko 40문장): Turbo 3.3% CER, Moonshine Base 5.0%, Small ACFT 5.7%, SenseVoice int8 7.1%, fp32 7.3%, Moonshine tiny 7.7%.
  속도는 Moonshine Base가 Turbo의 ~12배. SenseVoice fp32는 이득 없어 제거(받아둔 파일은 RETIRED_DIRS로 정리).
- 사용자: Moonshine이 3초 녹음을 "audiotext"로 인식. 재현됨(짧은 클립 ~1/4). 감지해서 다른 모델로 재실행.
- 사용자: 버블이 작아진 뒤 다시 안 커짐. 커지는 건 버블 터치뿐이었다 → 타이핑·입력창 재진입에 깨어나고 카운트다운 재시작.

## 2026-11-08 — GPU(OpenCL) 제거
- build-39: 결과는 나오지만 CPU보다 훨씬 느림(사용자). GPU 관련 코드·CI 단계·패치 스크립트를 d8c84e5 상태로 되돌림.
  Moonshine Base Korean·SenseVoice full·버블 모델 전환은 유지. CI ~4.5분으로 복귀.

## 2026-11-08 — GPU: 크래시는 없어졌으나 빈 결과 (build-39)
- build-38: GPU 켜고 받아쓰기 → 크래시 없이 "Empty transcript". whisper.cpp 1.8 기본 flash_attn=true인데 ggml-opencl FA 커널은
  LLM 형태 위주 → GPU 컨텍스트는 flash_attn=false(jni.c `OPENWISPR_JNI_GPU`).
- GPU 결과가 비면 CPU로 다시 돌린다(`lastBackend` = "CPU (GPU returned nothing)"). Compare는 빈 GPU 결과 옆에 gpu.log 끝줄 표시.

## 2026-11-08 — GPU: 두 번째 크래시(get_alignment) 수정
- build-37 크래시 보고: SIGSEGV `ggml_backend_opencl_buffer_type_get_alignment` ← `whisper_model_load`.
  "CPU total size = 37.34 MB" = 토큰 임베딩이 CPU로 간 것 → 첫 패치는 의도대로 동작.
- 원인: ggml-opencl이 backend_ctx를 지연 초기화하는데 whisper.cpp가 그 전에 alignment를 읽음. `scripts/patch-ggml-opencl.py`로
  get_alignment/get_max_size가 먼저 `ggml_cl_init`을 부르게 함.

## 2026-11-08 — Moonshine Base Korean, SenseVoice full, GPU 로드 크래시 대응
- 사용자: moonshine-base-ko와 SenseVoice-Small 비교, 기존 문샤인(tiny-ko) 제거, SenseVoice는 더 큰 걸로.
  SenseVoice-Small은 원래 234M 하나뿐이고 기존 것은 그 int8 → 비양자화(fp32, ~900MB)를 비교용으로 추가.
- base-ko는 sherpa 공개 번들이 없어 `package-moonshine.yml`로 직접 번들링(이 저장소 pre-release). tiny-ko 디렉터리는 RETIRED_DIRS로 정리.
- GPU: 설정에 크래시 보고가 떴다 — "native crash", 로그는 whisper_model_load n_langs=100에서 끊김(가중치 업로드 중).
  ggml-opencl의 양자화 변환 커널이 work group 64 고정·경계 검사 없음 → 51866행 토큰 임베딩이 의심. whisper.cpp 패치로 그런 가중치는 CPU 버퍼에.
  네이티브 크래시면 tombstone에서 ggml/whisper 프레임 이름을 뽑아 보고에 같이 보여준다.

## 2026-11-08 — GPU 토글 시 앱 강제 종료 → 진단·복구
- build-33에서 GPU를 켜자 앱이 닫힘. ggml OpenCL 백엔드는 커널 컴파일 실패·미지원 GPU에서 로그만 남기고 `exit(1)`을 부른다.
- 복구: Settings가 열릴 때 크래시 흔적(`GpuGuard` busy 플래그)이 있으면 드라이버를 건드리기 전에 설정을 끈다
  (전엔 Settings를 열 때마다 다시 죽을 수 있었다). 라이브러리 로드도 GpuGuard 안에서.
- 진단: GPU 라이브러리가 ggml 로그와 stderr를 `files/gpu.log`로 보낸다(`gpuDevice(logPath)`). 다음 실행에 종료 사유
  (ApplicationExitInfo)와 로그의 에러 줄을 설정 토글 아래에 보여준다.

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
