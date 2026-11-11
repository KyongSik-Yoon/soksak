# 포크 변경 이력

최신이 위. 항목마다 날짜, 커밋, 사용자 관점의 변화, 사용자가 폰에서 확인한 결과(있으면).
새 작업을 끝내면 맨 위에 추가한다.

## 2026-11-11 — Turbo (NPU)에 문장부호 힌트 (이 커밋)
- Medium과 같은 `네, 알겠어.` 힌트를 large-v3 번호(startofprev 50362)로. 폰에서 같은 녹음 20개 재측정 전.

## 2026-11-11 — Turbo (NPU) Run all 측정 (문서만)
- 최근 녹음 20개(102초), 모델 로드 제외: Turbo NPU 19.0초(문장당 0.95초), Small ACFT CPU 27.7초(1.39초), Medium NPU 43.7초(2.18초). 정답 전사가 없어 CER은 못 냄.
- 결과 비교: Turbo NPU는 쉼표·마침표가 거의 안 찍힘(Medium은 힌트 덕에 찍힘). 용어는 Turbo가 가장 나음. 18초 녹음 끝 문장("깔끔한 형태로 할 수 없을까")은 NPU 둘 다 틀림(14초 창 경계), CPU Small만 맞음. 1.7초 녹음 하나는 Turbo만 영어로 냄("Hi, nice to meet you." — 실제 영어 발화였는지 미확인).

## 2026-11-11 — Turbo (NPU) 실험 표시 뗌 (이 커밋)
- 사용자 확인(build-122~123): Turbo (NPU)가 한 번에 동작, 속도·인식률 모두 좋음(체감). 버블 메뉴·설정 동기화·토큰/엔진 칸 제거도 확인.
- 목록 맨 끝으로 옮기고 "experimental" 뗌, README·홈페이지 표와 데모 메뉴에 추가. 수치 비교는 아직.

## 2026-11-11 — 클라우드 음성 인식(Groq/OpenAI/Custom) 제거 (이 커밋)
- 설정의 Engine 선택·API 키 입력과 `SttEngine`(HTTP 클라이언트), `Defaults`(제공자 목록)를 지웠다. 받아쓰기는 온디바이스 Whisper만 쓴다.
- 저장된 `sttProvider`/`sttEndpoint`/`sttKey`는 다음 저장 때 지워지고, 옛 백업 파일·녹음 메타의 같은 키는 읽지 않고 무시한다.

## 2026-11-11 — GitHub 토큰 설정 제거 (이 커밋)
- 저장소와 Release가 공개라 토큰이 필요 없다. 업데이트 확인·모델 다운로드는 익명 API 호출만 쓴다(시간당 60회/IP, 403이면 "나중에 다시").
- 폰에 저장된 `githubToken` 값은 다음 설정 저장 때 지워진다(`RETIRED_STRINGS`).

## 2026-11-11 — 실험: Large v3 Turbo ACFT를 NPU로 (이 커밋)
- 사용자 제안. sherpa는 Medium 위로 QNN 빌드가 없어 될지 모르는 상태에서 시작. 변환 스크립트를 되살려 HF 저장소·크기를 인자로 받게 함.
- CI 1차 실패: float32 인코더(6.35억 파라미터)의 모델 라이브러리가 2GB를 넘어 링크 실패. 2차: `--float_bitwidth 16`으로 성공(인코더 1.35GB, 디코더 0.45GB, tar.bz2 1.59GB, 약 25분). HF 대비 logit 차이 0.
- 런타임 패치: large-v3 계열 토큰 번호(transcribe 50360, notimestamps 50364)와 `turbo` 접두어. 앱에 `turbo-acft-npu`(TN) 추가. 폰 확인 전.

## 2026-11-11 — 버블 메뉴 윗줄 정리, 조용히 말하기 색 (이 커밋)
- 사용자 지적: 메뉴 위쪽 행이 조잡함(🤫 이모지, "PC" 글자 배지, 배지 없는 행은 글자가 어긋남), 조용히 말하기의 청록 배지가 보라와 안 어울림.
- 설정 행을 선 아이콘(달·모니터·키보드/클립보드) + 이름 + 오른쪽 현재 값으로 통일, 구분선으로 모델 목록과 나눔. 체크도 글리프 대신 벡터. 어두운 테마에서 강조색을 밝은 보라로.
- 조용히 말하기 배지·메뉴 강조를 장밋빛(#B4608E / 어두운 테마 #E8A3C6)으로. 홈페이지 문구·배지도 같이. 폰 확인 전.

## 2026-11-11 — Small ACFT (NPU) 제거 (이 커밋)
- 근거: 실제 속삭임 10문장 CER 38.9%로 CPU판(35.1%)·Medium NPU(35.5%)보다 낮고, 단어장 프롬프트를 못 받음. 속도(문장당 0.7초)만 장점인데 "말하는 동안 변환"으로 체감이 작음.
- 모델 목록·고지·README·홈페이지에서 빼고, 폰의 번들은 다음 실행 때 지움(`RETIRED_DIRS`). 저장된 선택이 이 모델이면 내려받은 것 중 가장 큰 모델로 넘어감(`OnDeviceStt.pick`).
- 빌드 경로(npu-acft 워크플로, npu_acft_export·npu_compile_sm8850 스크립트)도 삭제. Release `model-qnn-whisper-small-acft-sm8850`은 남아 있음(삭제는 사용자 결정).

## 2026-11-11 — 자동 중지 대기 2.2초 선택지, 위젯은 2.2초, 대기를 원으로 표시 (이 커밋)
- 사용자 확인(build-119): 잠금화면 위젯에서 자동 중지 동작. 다만 3초 + 변환이 길게 느껴짐, 1.6초는 짧을 수 있음.
- "Auto-stop wait"에 2.2초 추가(1.6/2.2/3/5). 위젯 받아쓰기는 설정과 무관하게 2.2초. 전체 화면에서 조용해지면 원 둘레가 차오르고 "곧 끝나요 · 누르면 바로 끝".

## 2026-11-11 — 조용히 말하기에서 자동 중지가 한 번도 안 걸리던 것 + 위젯 전체 화면 (efa3a34, 이 커밋)
- 증상: 조용히 말하기에서는 말을 멈춰도 끝나지 않아 매번 다시 눌러야 했다(잠금화면 위젯에서 특히 불편).
- 원인(폰의 녹음 20개에 감지기를 다시 돌림 — Compare "Run all"이 `SoksakBench` 로그와 앱 외부 폴더 `bench/*.wav`를 남김): 20개 모두 발동 없음.
  ① 마이크 첫 조각이 0(-120dB), 이어 0.25초는 -65dB → 소음 바닥이 거기로 잡히고 천천히만 올라 모든 조각이 "말". ② 전체 음량은 500Hz 아래 방 소음(-45~-50dB, ±5dB 출렁)이 지배해
  속삭임(-44~-50dB)과 안 갈림. 500~2000Hz 대역에서는 조용할 때 -52dB로 일정, 속삭임 -36~-48dB.
- 고침: 감지기가 500~2500Hz만 재고(`feedPcm`), 시작 0.3초 무시, 0.1초 이어진 소리만 말. 같은 20개로 확인(PC 시뮬레이션과 Kotlin 둘 다):
  말 멈추고 기다린 3개가 말 끝+3초에 멈춤(6.21/8.58/6.75초), 나머지 17개는 도중에 안 끊김. NPU 힌트 에코 판정(`hasSpeech`)도 같은 규칙.
- 위젯 받아쓰기 전체 화면(`LockDictationActivity`): 잠금화면 위에 뜨고 녹음 중 화면 유지, 아무 데나 눌러 끝, 결과 뒤 닫힘. 사용자 확인: 뜨고 전송됨.

## 2026-11-11 — 영어 용어에 붙은 조사가 교정에서 사라지던 것 + 속삭임 테스트로 단어장 채움 (4ec6759, build-114)
- 증상: 단어장에 README가 있으면 "README에 설치" → "README 설치". "README에"가 한 토큰이라 퍼지 매칭(Soundex + 1글자 차이)이 조사까지 바꿈.
  고침: 라틴 글자와 한글이 바뀌는 곳에서 토큰을 나눔. `KoreanVocabTest.particleOnLatinTermIsKept`.
- 단어장(백업 파일로 복원, 폰 adb 조작): 별칭 CI(시아이·씨아이), README(리드미), CHANGELOG(체인지로그·체인지로), Medium(미디엄), Small(스몰·smoll·smol),
  로그(로크), 함수(암수), 기본값(기본급) + 프롬프트용 main·머지·커밋·토글·버블·클립보드. 조용히 말하기 모델 → small-acft. 해로운 학습 별칭 3개 삭제(인식←위치, 꿀←골, 있다가←이따가 — 흔한 말·맞는 말을 바꿈).
  속삭임 테스트 출력 30개 중 11개가 교정으로 좋아지고 정답 10문장은 그대로(JVM 확인). 나머지 오류(윌리트, 세포크 등)는 표본 맞춤이 될 것 같아 넣지 않음.

## 2026-11-11 — 실제 속삭임으로 모델 비교 (c9e2c8f, build-113)
- Compare에 "Run all on every model (to log)": 최근 녹음 전부를 모델마다 한 번 로드 후 돌려 logcat `SoksakBench`에 시간·결과.
- 사용자가 개발 문장 10개(49초)를 속삭여 녹음, 조용히 말하기 켬. CER(공백·부호 제거, 영어 음역은 오답) / 디코드: Small ACFT CPU 35.1% / 14.5초,
  Medium NPU 35.5% / 21.2초, Small ACFT NPU 38.9% / 6.7초. 정확도는 CPU Small과 Medium이 동급, Medium이 1.5배 느림. 공통 오류: CI·README·CHANGELOG·커밋 같은 영어 용어 음역/오인식.

## 2026-11-11 — 받아쓰기 위젯, PC로 보내기 전용 (7727a41, 3bed43c, build-110; 크기 고정 build-111; 조용히 말하기 고정 build-112)
- 1×1 "속삭 받아쓰기" 위젯(홈·잠금화면): 탭하면 받아쓰기 시작, 다시 탭하면 멈춤. 폰을 잠근 채 PC로 말 보내기용이라
  위젯으로 한 받아쓰기는 PC 전송 설정과 상관없이 PC로 간다(실패하면 폰 입력). 사용자 확인: 홈·잠금화면 모두 뜨고 동작. 잠금화면이 100×100dp 칸을 줘서 마크가 너무 컸음 → 48dp 원 고정(build-111). 위젯 받아쓰기는 항상 조용히 말하기(build-112, 사용자가 잠금 상태에선 거의 속삭임).

## 2026-11-11 — PC "바로 입력"이 숫자로 찍히던 것 (3469666, 데몬만)
- 사용자 첫 실사용: 클립보드는 정상, 바로 입력은 말한 글 대신 `1234567890-=qwer…`. 원인은 입력기 kime — `wtype`의 가상 키맵이 무시됨.
  같은 문장으로 비교: wtype은 깨지고, 클립보드 + Ctrl+Shift+V는 정상.
- `daemon/soksak-paste`(Hyprland `send_key_state`로 터미널이면 Ctrl+Shift+V, 아니면 Ctrl+V)를 `~/.local/bin`에 설치, 사용자 config에 `type_cmd = soksak-paste`.
- 2026-11-11 사용자 확인: 폰 → PC 바로 입력 정상.

## 2026-11-11 — 단어 고치기 화면이 키보드에 가리던 것 (9a10c84, build-105; 0fda637, build-106)
- 홈이 키보드 높이만큼 줄지 않고 창 전체를 밀어 올려(pan) 입력창이 키보드 바로 위에 붙고 Save가 가려졌다.
  이제 `adjustResize` + 키보드 높이에서 아래 탭 바 높이를 뺀 만큼만 목록을 줄이고(build-105는 키보드 전체만큼 줄여 탭 바 높이만큼 빈 공간이 남음), 단어를 탭하면 입력창에 바로 포커스, 고치기 줄과 Save 줄(`SAVE_ROOM` 96dp)까지 보이게 스크롤.
- 2026-11-11 사용자 폰에서 확인(build-106): 문제 없음.

## 2026-11-11 — 조용히 말하기에서 말없이 멈추면 "네."가 찍히던 것 (4c29177, build-104)
- NPU 힌트 메아리("네.")를 조용히 말하기에서는 Silero가 속삭임을 못 들어 아예 거르지 않았다(86af614) → 무음 녹음에도 "네."가 남음.
  이제 그 창의 소리를 자동 중지용 음량 감지기(`QuietSpeechDetector.hasSpeech`, 방 소음 대비 2.5배·0.1초 연속)로 보고, 말이 없으면 버린다.
- 폰 확인 전.

## 2026-11-11 — 대기 중 CPU·배터리 측정 (build-103, 사용자 폰, 무선 adb) — 고칠 것 없음
- 앱 프로세스 CPU(코어 하나 대비): 화면 꺼짐 0.06%(3분), 홈 화면 0.23%, 채팅 앱 앞 0.43%, 다른 앱 스크롤 중 0.51%,
  스톱워치처럼 매 프레임 바뀌는 화면 1.08%(접근성 이벤트 수신 비용; 이때 입력창 확인은 디바운스로 0회).
- 시스템 배터리 통계(완충 이후 26시간, 전체 6777mAh 중 속삭 127mAh = 1.9%): 화면 21.8, CPU 49.1(앱 화면 35.7, 서비스 상태 9.4시간 13.1 — 받아쓰기 변환 포함),
  통신 55(모델 4.8GB 다운로드, 일회성), 화면 꺼짐 상태 CPU 1.64mAh. 대기 소모는 문제 수준이 아니라 최적화하지 않음.
- 측정법: `/proc/<pid>/task/*/stat`의 utime+stime 차이(셸 사용자로 읽힘), `dumpsys batterystats --charged`. 화면 끄기는 keyevent 26(223은 안 먹음).

## 2026-11-11 — PC에서 클립보드/바로 입력을 앱에서 고르기
- 사용자 질문: 자동 입력을 왜 화면이 아닌 데몬 설정 파일에 뒀나 → 허용 여부만 PC에 남기고(`allow_type`, 기본 불허) 선택은 앱으로 옮김.
  설정 → PC로 보내기 → "PC에서"(클립보드/바로 입력), 버블 메뉴에도 같은 행. PC가 허용 안 하면 클립보드에만 넣고 버블에 "PC clip".
- 데몬의 `action` 설정은 없앰. 사용자 PC 데몬도 새 버전으로 교체(허용은 사용자가 직접 켜야 함). `wtype` 실제 타이핑은 미확인.

## 2026-11-11 — PC로 보내기 (폰에서 말한 글을 컴퓨터 클립보드로) — e095d40, 7aaa58a, build-102
- 사용자 아이디어: 사무실에서는 PC로 일하니, 폰에서 받아쓴 글이 연동된 PC 클립보드에도 들어가면 좋겠다.
- `daemon/`: Rust 데몬(표준 라이브러리만). Tailscale 대역만 받고 연동 코드를 확인한 뒤 `wl-copy`로 클립보드에 넣는다. 설정으로 `wtype` 자동 입력도 가능.
- 앱: 설정 → PC로 보내기(주소, 연동 코드, 시험, 보낼 곳: 이 폰만/폰과 PC/PC만), 버블 메뉴에 같은 전환 행. 기본 꺼짐.
- PC에서 확인: 데몬 테스트 13개, 앱 JVM 테스트(가짜 소켓 왕복 포함), 앱의 전송 코드로 실제 데몬에 Tailscale 주소로 보내 받는 것까지.
  **폰 → PC 실제 전송은 사용자가 코드 입력 후 확인 필요.** ufw가 켜져 있어 tailscale0에서 포트 48620을 여는 명령을 사용자에게 안내.

## 2026-11-10 — 홈페이지를 속삭 컨셉의 한 가지 화풍으로 정리
- 사용자가 wisprflow.ai 같은 차분한 느낌을 원함 → 구조(세리프 제목, 둥근 색 패널, 말이 글로 흐르는 그림)만 참고하고
  배색·문구는 속삭 것으로: 첫 화면은 소리 파형 → 말풍선 마크 → 글 리본. 버블 시연, 20초 대 3초 비교, 조용히 말하기(밤),
  폰 안 처리 흐름, 숫자, 설치 순. 장면판은 `docs/scenes.html`로 보존(푸터 링크).

## 2026-11-10 — 홈페이지를 장면 전환 방식으로 개편
- 사용자가 보여 준 모션 그래픽 영상의 구조(장마다 화풍 전환)만 빌려 스크롤 장면으로 다시 만듦: 타이틀 → 01 버블(블록, 시연)
  → 02 말하는 동안 변환(픽셀 아케이드 경주, 20초 vs 3초) → 03 조용히 말하기(밤) → 04 기기 안에서(아이소메트릭 스택)
  → 05 숫자(손글씨 노트) → 설치·모델·포크 고지. 첫 버전은 `docs/classic.html`(푸터 "이전 페이지").

## 2026-11-10 — 속삭 홈페이지 (e3beae3)
- `docs/index.html` 한 파일을 GitHub Pages로 공개: https://kyongsik-yoon.github.io/soksak/ . 저장소 homepage가 원작 사이트(openwispr.dev)를
  가리키던 것을 이 주소로 바꾸고 설명도 고쳤다. 버블 시연, 기능, 벤치 숫자(나쁜 조건 포함), 모델, 설치, 포크 고지.
- `docs/**`는 앱 CI에서 제외. 이 커밋은 ci.yml을 고쳐서 build-101이 한 번 만들어졌다(앱 내용은 build-100과 같음).

## 2026-11-10 — 선택 모델이 없을 때 받아쓰기가 멈추던 문제 (6bfc330, build-100)
- 증상: 새로 설치(모델 하나)한 뒤 받아쓰기하면 아무 반응 없이 멈추고, 뒤로 가기를 해야 풀림.
- 원인: 백업 복원이 옛 앱의 모델(예: Turbo)을 선택값으로 가져옴 → 디스크에 없는 "아는 모델"이라 대체되지 않고
  25초 "모델 다운로드 대기"로 감. 시트 없는 모드에선 그 단계가 아무것도 그리지 않는 투명 창이라 터치를 다 막음.
- 수정: 선택 모델이 없으면 받아 둔 가장 큰 모델로 받아쓰기(`OnDeviceStt.pick`). 다운로드가 실제로 진행 중일 때만
  기다리고 아니면 바로 오류. 대기 시트는 시트 없는 모드에서도 보임.

## 2026-11-10 — 앱 색을 속삭의 "dusk" 팔레트로
- 사용자 지적: 이름·아이콘만 바뀌고 안은 원작과 똑같아 보인다. 색 방향 1번(밤·속삭임) 선택.
- 변경(285e875): 원작 코랄 "muted sunset" → 라벤더·보라·dusk(라이트 #F5F3F8 바탕, 다크 #1E1A26), 버블·모델 메뉴·레일 포함. 상태 색(초록·벽돌) 유지, 조용히 말하기 배지는 파랑 → 청록. build-99 CI 성공. 사용자 확인(build-99): "잘 나와".

## 2026-11-10 — 공개 준비: 라이선스 고지, 이름 속삭(Soksak), macOS 제거
- 저작권 점검: 원작 MIT, 의존성 Apache/MIT, 폰트 OFL, Whisper MIT·ACFT Apache-2.0 — 공개에 걸림 없음. Qualcomm QNN 런타임은 "앱에 포함된 오브젝트 코드로만" 배포 허용(AI Hub Model License) → APK 안이라 허용 범위.
  Moonshine 한국어는 Community License(사본·Notice·"Powered by Moonshine AI" 의무)라 Release `model-moonshine-base-ko` 삭제 결정(auto mode가 삭제를 막아 사용자가 직접 실행), 벤치에서도 뺌(41ba2d3).
- 고지(fa1311a): 설정 → General → 오픈소스 라이선스 화면, `third_party_notices.txt`, whisper.cpp LICENSE, 모델 Release 설명에 라이선스. build-94 CI 성공.
- 제거(fabf591): macOS 앱(사용자가 맥 안 씀), 원작 홍보 사이트·Play 메타데이터·태그 릴리스 워크플로·브랜드 자료.
- 이름(e9e9e2f): 원작이 Play 스토어에 있어 OpenWispr → 속삭/Soksak. 저장소 `soksak`으로 rename, README 맨 위에 포크 명시, LICENSE에 포크 저작권 줄 추가,
  말풍선+음파 아이콘(런처 배경 자주색), 원작자 메일·Play 평가 메뉴 삭제(이슈 신고는 이 저장소로). 접근성 서비스 클래스·앱 ID는 유지(업데이트로 설치되고 접근성 설정이 안 풀림).
- 토큰 선택화(f1b65db, build-96 CI 성공): 업데이트 확인·설치·모델 다운로드가 토큰 없이도 동작(공개 저장소면 익명 호출), 있으면 토큰 사용. 설정의 토큰 칸은 "(선택)".
- 백업·복원(9f81441, build-97): 설정 → General에서 단어장(학습 단어 포함)·설정을 JSON으로 저장·복원, 토큰·키는 제외. 사용자가 옛 앱에서 백업 완료.
- 서명 키 교체 + 앱 ID 변경(c57d378, build-98 CI 성공): 저장소 밖 키(CN=Soksak, Secret으로 CI 서명), 앱 ID `io.github.kyongsikyoon.soksak`, 커밋돼 있던 debug.keystore 삭제. APK 서명·패키지 확인함. 폰에서는 새 앱으로 설치된다.
- 사용자가 새 앱으로 옮기고 옛 앱 삭제 → 저장소 공개 전환. 로그인 없이 최신 빌드 조회 200, 옛 주소 200, 모델·APK 다운로드 동작 확인. 이제 폰의 GitHub 토큰은 지워도 된다(토큰 없는 업데이트는 폰에서 아직 미확인).

## 2026-11-10 — 설정을 홈의 탭으로: 하단 바 고정, 내용만 슬라이드
- 사용자 확인(build-90): 설정에도 바가 보이는 것은 의도대로. 다만 화면(Activity) 전환이라 바까지 함께 밀려 들어왔다.
- 변경: 홈 안에서 `AnimatedContent`로 홈/설정 탭을 바꾼다(탭 순서 방향으로 0.25초 슬라이드). 설정 탭에서 뒤로 가기는 홈 탭으로, 홈 복귀 시 다시 읽음. 버블의 설정 바로가기는 기존 `SettingsActivity` 그대로. 커밋 9f77cd8, build-91 CI 성공.
- 사용자 녹화(build-91): 홈→설정 전환 때 홈·설정 내용 전체가 아래로 내려갔다 스프링처럼 올라옴. 원인: 설정 화면이 로드 전 `if (!loaded) return`으로 크기 0 → `AnimatedContent`의 크기 애니메이션. 에뮬레이터(빈 설정)에서는 로드가 즉시 끝나 재현 안 됨.
- 수정(16bc9c0): 로드 전에도 헤더+배경으로 화면을 채움. build-92 CI 성공. 사용자 확인: 전환 자연스러워짐.
- 사용자 지적(build-92): 설정→홈에 약간의 딜레이(녹화상 탭 후 약 0.25초 정지). 원인: 홈을 버렸다 돌아올 때 기록 카드(최대 100개, 지연 로딩 없음)를 첫 프레임에 전부 다시 만듦.
- 수정(bfe0a40): `AnimatedContent` 대신 홈을 계속 살려 두고 두 화면을 같은 오프셋으로 이동(스크롤 위치도 유지). 설정 첫 프레임 뒤에 슬라이드 시작(안 그러면 첫 프레임 동안 시간이 흘러 한 번에 바뀜, 에뮬레이터에서 확인). build-93 CI 성공. 사용자 확인(build-93): 딜레이 없음, "완벽하다".

## 2026-11-10 — 설정 화면에도 하단 바
- 증상: 설정에 들어가면 Home·Talk·Settings 바가 사라져 뒤로 가기로만 돌아올 수 있었다.
- 변경: 바를 공용 `AppBottomBar`로 옮겨 설정 화면에도 표시(현재 탭 강조), 설정에서 Home을 누르면 아래에 있던 홈으로 돌아간다. 라벨은 홈과 같은 영어 유지. 커밋 4e56f64, build-90 CI 성공.
- 사용자 확인(build-90): 바 표시는 의도대로(이후 탭 방식으로 바뀜, 위 항목).

## 2026-11-10 — 힌트 작업 정리·리뷰 반영
- 정리(4032480): Silero 생성 함수 하나로, 녹음기와 같은 말소리 기준(0.5) 공유, 빈 창 처리 단순화.
- 리뷰 반영(86af614): 말소리는 0.1초 이상 연속이어야 인정(흩어진 잡음 프레임으로 "네."가 남지 않게). 조용히 말하기에서는 메아리를 버리지 않음(Silero가 속삭임을 못 들음). 힌트가 실제 말 앞에 붙는 경우는 실제 답과 구분할 수 없어 손대지 않음(관측 없음). build-89 CI 성공.
- 사용자 확인(build-93): 빈 입력은 아무것도 들어가지 않고, 짧게 말한 "네."는 그대로 들어간다.

## 2026-11-10 — Medium 힌트의 무음 처리를 Silero로
- 증상(build-87): 빈 입력에 `네.`가 들어감. 음량 기준 판별이 실제 환경에서 뚫렸다(일정한 합성 잡음으로만 시험했음).
- 변경: 출력이 `네.`류일 때만 자체 Silero 인스턴스로 창을 검사해 말이 없으면 버린다. PC에서 같은 모델 파일로 잡음·클릭음 최고 0.1 미만, 0.35초 단어는 3% 음량에서도 1.0. 커밋 5c86f0f. build-88 CI 성공.
- 같은 31초 Compare(build-87): 마침표는 모두 2개, 쉼표는 Small ACFT CPU 5, Medium 1, Small ACFT NPU 0. 세 모델의 단어는 거의 같아 힌트로 인한 악화는 안 보임.
- 폰 확인은 build-93에서(위 항목).

## 2026-11-10 — Medium NPU 문장부호 힌트
- 증상: Medium 받아쓰기에 마침표·물음표가 드묾. 괄호 억제 목록은 원인이 아니었다(`.`, `,`, `?`, `!`가 목록에 없음). 힌트 없이는 14초 창 하나가 통째로 부호 없이 나왔다.
- 변경: 한국어 Medium에만 `<|startofprev|> 네, 알겠어.` 6토큰을 시작 토큰 앞에서 디코드, 패딩 예산에 반영. 커밋 07772e3, 3973a68. build-87 CI 성공.
- PC 비교(같은 가중치, 앱과 같은 14초 창·그리디·억제 목록, 3분 연기 음성): Medium 마침표 30→52, 쉼표 11→23, 누락 없음. Small ACFT는 문장이 빠지고 `ㅜ` 반복 붕괴가 나와 제외.
- 무음: `<|startofprev|>` 없이는 `이 영상은 유료 광고를…` 같은 새 환각, 있으면 `네.`/`네, 알겠습니다.`. 목소리 없는 창의 그 출력만 버린다.
- 폰 확인(build-87, 30.9초 Compare): 디코더 단계 수 − 토큰 수가 Medium 10(시작 4 + 힌트 6), Small ACFT NPU 4로 힌트가 Medium에만 들어간다. 크래시·`네.` 끼어듦 없음. 스텝당 약 53ms라 창당 약 0.32초, 3개 창이면 Medium 디코드 9.3~9.5초 중 약 1초가 힌트 비용.
- 사용자가 붙여 넣은 30.9초 결과(197자, 로그의 글자 수와 일치): 문장 끝 마침표 2개는 맞게 찍힘. 긴 문장 안의 쉼표는 거의 없음. 힌트 없는 같은 녹음과의 비교는 없다.

## 2026-11-10 — NPU 입출력 버퍼를 공유 메모리로
- 근거: 실행 1회의 NPU 바깥 시간이 Medium 약 13ms, Small ACFT 약 5ms로 모델 크기를 따라 늘고, 진단을 꺼도 디코드가 1~6%만 줄었다.
- sherpa 패치: 텐서 전체를 rpcmem 한 덩어리에 두고 `memRegister`로 등록. 실패하면 예전 복사 방식, 실행 실패 시 복사로 재시도. 로드 때 `QNN shared memory` 로그. 커밋 35ae39b. build-86 CI 성공.
- 폰 측정(build-86, 같은 31초 Compare, 진단 끔, 로드 제외): Medium 디코드 9.89 → 8.17초(-17%), Small ACFT NPU 3.71 → 2.92초(-21%), Small ACFT CPU 9.4초(이번 측정, 경로 무관). 진단 켬에서는 디코더 1회의 QNN−RPC 차이가 Medium 약 13ms → 0.15ms, Small 약 5ms → 0.05ms. 토큰 수·글자 수는 이전과 동일(Medium 53/38/25, 204자, Small 52/38/34, 210자), 로드 시간 변화 없음, 모델 전환 반복에도 `using copies`·크래시 없음. 표본 1개씩.

## 2026-11-10 — 디버그 로그 정리, NPU 진단 토글, 대시보드 숫자 줄바꿈 수정
- 폰 측정(build-85, 진단 끔, 같은 31초 Compare, 로드 제외): Medium 디코드 9.89초(켠 상태 10.03초, -1.4%), Small ACFT NPU 3.71초(3.94초, -5.7%), Small ACFT CPU 8.49초. 프로파일링 비용은 스텝당 1~2ms라 작고, 디코더 스텝의 QNN 호스트 약 13ms는 프로파일링 때문이 아니다. 표본 1개. 복사 로그는 1000줄 상한에 걸리지 않았고 오래된 크래시 기록과 텐서 목록이 사라졌다.
- 폰 확인(build-83): 복사 로그가 1000줄 상한에 걸렸고 약 800줄이 11-08 GPU 빌드의 크래시 기록(crash 버퍼는 며칠 남음), 약 130줄이 입력창 밖 포커스 로그. 진단을 켜면 모델 로드의 텐서 목록만으로 상한이 찼다.
- 복사 로그에서 24시간 지난 줄, `editable=false` 포커스, sherpa 텐서 목록 제외. 커밋 c0f3803(build-84), 97ae5d6(build-85) CI 성공. 폰 확인은 아직.
- 폰 측정(build-83, Medium NPU 버블 받아쓰기, 진단 켬, 15.4초, 조각 1개): 멈춘 뒤 대기 8.2초 = 로드 3.4초(토글 변경으로 재로드) + 디코드 4.6초(2창, 47토큰). 디코더 실행 1회 QNN 58.5ms 중 RPC 45.8ms. 진단 끈 상태 측정은 아직.
- 디버그 로그 복사: 화면 그리기·FastRPC 일상 메시지를 빼고 최대 1000줄(`DebugLog.relevant`). 커밋 f8ca777.
- 첫 화면 통계: `1,582`가 칸에서 줄바꿈되던 것 수정(여백 축소, 28sp, 줄바꿈 금지, 1만부터 `12.3K`). 커밋 3aa020f → build-82 CI 성공. 폰 화면 확인은 아직.
- 설정 `NPU 진단 로그`(기본 끔): sherpa debug 출력과 NPU 실행마다의 QNN 프로파일링을 켤 때만 동작. 커밋 1bed381 → build-83 CI 성공. 기준값(프로파일링 켠 상태, 31초 Compare): Medium 디코드 10.0초, Small ACFT 3.9초. 끈 상태 측정은 아직.

## 2026-11-09 — NPU 디코더에 비음성 토큰 억제 추가
- 증상(build-80, Compare, 속삭임 17초): Medium NPU의 첫 창이 `(댓글 읽음)`으로 나오고 뒤 창만 정상. sherpa QNN 디코더는 최댓값을 그대로 골라 `(` 같은 토큰이 선택 가능했다. CPU 경로는 `suppress_nst`로 막고 있었다.
- 조치: sherpa 패치에 OpenAI 비음성 토큰 82개 억제 추가. 커밋 52f6416 → build-81 CI 성공.
- 폰 확인(build-81, 같은 속삭임 17초 Compare, 시간은 모델 로드 포함): `(댓글 읽음)` 사라지고 첫 창 문장 복구. Medium NPU 11.3초로 네 모델 중 가장 정확(첫 창 끝의 "Compare models를 돌리면"만 누락), Small ACFT NPU 4.3초("키우고 싶죠", "병원으로" 등 오류), Turbo ACFT 15.3초("결호 50초" 등 오류). 표본 1개.
- 폰 확인(build-81, 평소 목소리 14초 Compare, 로드 포함): Medium NPU 9.2초 거의 정확(`small a c f t m p u`만 어긋남, `compare models` 그대로), Small ACFT NPU 2.0초와 Small ACFT CPU 2.6초는 정확도 비슷("정화터는", "녹음에" 등 오류). 30초 고정 입력으로 인한 Small NPU의 정확도 손실은 이 표본에서 안 보임. 표본 1개.
- 폰 확인(build-81, 평소 목소리 31초 Compare, 로드 포함): 세 모델 모두 끝까지 받아 적어 30초 초과 입력 정상. Medium NPU 15.0초 가장 정확("MP5", "로고에", "finished 줄" 누락), Small ACFT NPU 6.0초와 CPU 10.6초는 비슷("기다립니다", "재질", 모델명 오류). 세 표본 모두 Medium이 가장 정확.

## 2026-11-09 — 안 쓰는 모델 제거, Small ACFT (NPU) 추가
- Transpose 4스레드(build-79) 측정: 363ms → 120ms. 같은 측정에서 NPU 실행이 약 150ms 늘어 전체는 3098 → 3068ms로 거의 그대로. 남은 시간 대부분이 NPU 실행(스텝당 약 60ms)이라 모델을 줄이는 방향으로 전환.
- 설정 목록에서 Tiny, Base, Small, SenseVoice Small 제거(사용자 요청). 기본 모델은 Small ACFT, 폰의 해당 파일은 업데이트 후 자동 삭제. 커밋 296ce40.
- Small ACFT (NPU) 추가: FUTO 가중치를 sherpa QNN 방식으로 변환(`npu-acft.yml`, 컨텍스트 바이너리 578MB). 변환 전 가중치는 HF 출력과 일치 확인. 커밋 a71c5ac, b8e3d3f → build-80 CI 성공. 정확도는 미측정(audio context 30초 고정이라 ACFT 이점이 줄 수 있음).
- 폰 측정(build-80, Compare, 1.3초 음성, 8토큰): Small ACFT NPU 디코드 458ms(인코더 116, Transpose 37, 12스텝 실행 264 = 스텝당 22ms), 로드 1.2초. Medium NPU 1314ms(인코더 375, Transpose 120, 실행 714 = 스텝당 60ms), 로드 3.4초.

## 2026-11-09 — Medium (NPU) 진단 로그(단계별 시간, QNN 프로파일)
- 토큰당 약 65ms의 정체를 재기 위해 sherpa 패치를 `.github/patches/sherpa-qnn.patch`로 옮기고 `QNN timing us`, `QNN profile` 로그를 추가. 원인을 찾으면 진단 코드는 뺀다. 커밋 a6168d9 → build-78 CI 성공.
- 측정(build-78, 9.7초 음성, 33토큰, 전체 3098ms): NPU 디코더 37스텝 실행 2211ms(스텝당 약 60ms, 마지막 스텝 프로파일 기준 NPU 연산 44ms·QNN 호스트 12.5ms·RPC 2ms), 인코더 364ms(NPU 350ms), **출력 Transpose 363ms(CPU 단일 스레드)**, self 캐시 복사 105ms, cross 복사 10ms. NPU 연산이 절반 이상이라 패치만으로는 크게 못 줄인다.
- 조치: Transpose를 4스레드로 분산(패치, 커밋 718890e → build-79 CI 성공, 효과는 미측정). QNN 호스트 12.5ms는 공유 메모리로 줄일 여지가 있으나 텐서 98개 등록이 필요해 보류.

## 2026-11-09 — Medium (NPU) 토큰당 295MB 재복사 제거(sherpa 패치)
- 측정(build-76, Compare): NPU 디코드 = 고정 약 1.0초 + 토큰당 약 73ms(19/21/35토큰 2412/2586/3595ms). 14.5초 음성에서 NPU 6.0초, Small 1.9초, Small ACFT 2.6초, SenseVoice 0.2초, Turbo 10.2초(모두 모델 로드 제외). 모델 로드 3.4~3.8초는 Compare처럼 모델을 바꿀 때만 든다.
- 원인 후보: sherpa `RunDecoder`가 토큰마다 변하지 않는 인코더 출력(24층 k·v, 295MB)을 입력 버퍼로 다시 복사. CI `sherpa-qnn` 잡에서 첫 토큰(offset 0)에만 복사하도록 sed로 패치(캐시 키 r2).
- 커밋 6209889 → build-77 CI 성공, 사용자 폰에서 크래시 없음.
- 결과(build-77, Compare 1회): 11.6초 음성 48토큰 디코드 4185ms. 패치 전 식으로는 약 4550ms라 약 8% 단축에 그친다. 재복사는 주원인이 아니었다. 같은 녹음에서 Small 2.4초, Small ACFT 2.6초, SenseVoice 0.2초, Turbo 10.4초.

## 2026-11-09 — Medium (NPU)에도 말하는 동안 변환 적용 + 시간 로그
- 배경: 사용자 "NPU를 안 쓰는 것보다 느린 느낌". NPU는 멈춘 뒤에야 전체를 처리했다(Whisper 모델은 말하는 동안 미리 처리).
- 수정: `StreamingWhisper`를 NPU에도 적용. 프롬프트 미지원이라 조각 간 문맥 연결은 없다. 5초 미만 발화는 조각이 안 생겨 대기 시간이 그대로다.
- 로그(`LocalSherpaStt`): 모델 로드 ms, 구간별 디코드 ms·토큰 수(설정 → 디버그 로그 복사).
- 커밋 79be7e5 → build-76 CI 성공. 폰 확인은 아직.

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
