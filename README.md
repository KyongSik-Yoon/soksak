# 속삭 (Soksak)

휴대폰에서 한국어로 말하면, 지금 쓰고 있는 앱의 입력창에 글로 넣어 주는 Android 받아쓰기 앱입니다.
음성 인식은 전부 기기 안에서(Whisper, CPU 또는 Qualcomm NPU) 돌고, 소리를 서버로 보내지 않습니다.

> **이 프로젝트는 [RohitAg13/openWispr](https://github.com/RohitAg13/openWispr)(MIT)를 포크해서 갈라져 나온 프로젝트입니다.**
> 원작의 Android 앱을 바탕으로 한국어·개인 사용에 맞게 크게 고쳤고, 원작과는 별개로 관리합니다.
> 원작자에게 감사드립니다. 원작의 macOS 앱, LLM 다듬기, 클라우드 연동은 이 포크에 없습니다.
>
> *Soksak is a fork of [OpenWispr](https://github.com/RohitAg13/openWispr) (MIT), reworked into an
> Android-only, on-device Korean dictation app. It is maintained separately from the original.*

## 무엇을 하나

- **떠 있는 버블**: 아무 앱에서나 버블을 누르고 말하면, 포커스를 뺏지 않고 그 입력창에 글을 넣습니다(접근성 서비스).
- **한국어 + 개발 용어**: 한국어 문장에 영어 개발 용어가 섞인 말을 주로 다룹니다. 단어장과 별칭 교정으로 이름·용어를 맞춥니다.
- **말하는 동안 변환**: 말이 쉴 때마다 조각을 미리 변환해서, 멈춘 뒤 기다리는 시간이 짧습니다.
- **조용히 말하기**: 속삭이듯 말해도 녹음을 자르지 않고 받아씁니다.
- **모델 고르기**: 버블을 위로 밀면 모델 메뉴가 뜹니다.

## 음성 모델

| 모델 | 엔진 | 비고 |
|---|---|---|
| Small ACFT (FUTO) | whisper.cpp (CPU) | 기본값, 짧은 클립에 맞춘 미세조정 |
| Large v3 Turbo ACFT q4_0 | whisper.cpp (CPU) | 가장 정확, 느림 |
| Small ACFT (NPU) | sherpa-onnx + QNN | Snapdragon 8 Elite Gen 5(SM8850) 전용 |
| Medium (NPU) | sherpa-onnx + QNN | SM8850 전용 |

모델은 앱에서 고를 때 내려받습니다. 라이선스는 앱의 설정 → General → 오픈소스 라이선스에 있습니다.

## 빌드

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

- Android SDK와 JDK 25가 필요합니다(컴파일 대상은 Java 17).
- NPU용 sherpa-onnx JNI와 QNN 런타임은 CI의 `sherpa-qnn` 잡이 만들어 넣습니다. 로컬 빌드에서는 NPU 모델이 동작하지 않습니다.
- `main`에 push하면 CI가 APK를 만들어 Release `build-<번호>`에 올리고, 앱이 새 빌드를 알려 줍니다.

## 라이선스

MIT — [LICENSE](LICENSE). 원작(OpenWispr contributors)의 저작권 표시를 그대로 유지합니다.
앱에 들어 있는 제3자 구성요소의 고지는 [`android/app/src/main/assets/third_party_notices.txt`](android/app/src/main/assets/third_party_notices.txt)에 있습니다.
