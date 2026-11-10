package com.voicerewriter

import java.util.Locale

/**
 * Korean for the Settings screen. Strings stay written in English at the call site and are
 * looked up here by that English text, so code still reads as before and a missing entry just
 * shows English. Templates use plain `%s` (no positional args: `$` would be a Kotlin template).
 *
 * Language follows the phone unless Settings → General → Language pins one ([mode]).
 */
object I18n {
    /** "auto" (phone language), "ko" or "en". Set from [Settings.uiLanguage]. */
    @Volatile var mode: String = "auto"

    val korean: Boolean
        get() = when (mode) {
            "ko" -> true
            "en" -> false
            else -> Locale.getDefault().language == "ko"
        }

    internal val KO: Map<String, String> = mapOf(
        // Header / setup card
        "Settings" to "설정",
        "You're all set" to "설정 완료",
        "Finish setup" to "설정 마무리",
        "Microphone · Bubble · Auto-insert all active" to "마이크 · 버블 · 자동 입력 모두 켜짐",
        "A couple of quick permissions" to "간단한 권한 몇 가지가 필요해요",
        "Microphone" to "마이크",
        "On" to "켜짐",
        "Needed to hear you" to "음성을 들으려면 필요해요",
        "Enable" to "켜기",
        "Floating bubble" to "플로팅 버블",
        "Tap-to-talk over any app" to "어느 앱 위에서든 탭해서 말하기",
        "Auto-insert" to "자동 입력",
        "Types text into the field you're in" to "지금 있는 입력창에 바로 써 넣어요",
        "Manage" to "관리",
        "Permission needed to show the bubble" to "버블을 띄우려면 권한이 필요해요",

        // Voice
        "Voice · transcription" to "음성 · 받아쓰기",
        "Engine" to "엔진",
        "On-device" to "기기 내",
        "Custom" to "직접 설정",
        "Models download once, then run fully offline." to "모델은 한 번 받으면 완전히 오프라인으로 돌아가요.",
        "Import" to "가져오기",
        "Get" to "받기",
        "Use" to "사용",
        "Recommended" to "추천",
        "Active" to "사용 중",
        "Delete %s" to "%s 삭제",
        "API key" to "API 키",
        "key" to "키",
        "Transcription endpoint URL" to "받아쓰기 엔드포인트 URL",
        "Model id" to "모델 ID",
        "Audio is sent to " to "음성이 ",
        "your provider" to "설정한 제공자",
        " for transcription." to "(으)로 전송돼 받아써져요.",
        "Switch to on-device to keep everything private." to "모든 걸 기기 안에 두려면 기기 내 엔진으로 바꾸세요.",
        "key or endpoint" to "키 또는 엔드포인트",
        "Dictation language" to "받아쓰기 언어",
        "The language you speak while dictating" to "받아쓸 때 말하는 언어",
        "Speech is transcribed in this language, never translated." to "이 언어로 받아쓰고, 번역하지는 않아요.",
        "Selected" to "선택됨",
        "Done" to "완료",
        "Style example" to "말투 예시",
        "A sentence the way you usually dictate. Whisper copies its style: English terms stay in English, punctuation matches. Leave blank to turn off." to
            "평소 받아쓰는 말투의 문장 하나. Whisper가 그 스타일을 따라 해요: 영어 용어는 영어로, 문장부호도 비슷하게. 비우면 꺼져요.",
        "e.g. GitLab에 MR 올려줘." to "예: GitLab에 MR 올려줘.",
        "Transcribe while you talk" to "말하는 동안 변환",
        "Whisper models start on what you've said at each pause, so only the last few seconds are left when you stop. Also keeps long dictations from losing sentences." to
            "Whisper 모델이 말을 쉴 때마다 앞부분을 미리 변환해서, 멈추면 마지막 몇 초만 남아요. 긴 받아쓰기에서 문장이 빠지는 것도 막아요.",
        "Auto-stop on pause" to "쉬면 자동 중지",
        "End recording when you stop talking" to "말을 멈추면 녹음을 끝내요",
        "Auto-stop wait" to "자동 중지 대기",
        "Stops after %ss of silence · tap to change" to "%s초 동안 조용하면 멈춰요 · 탭해서 변경",
        "Record without the sheet" to "시트 없이 녹음",
        "Keep the app you're typing in focused. On-device only; open Soksak once after a reboot" to
            "입력 중인 앱의 포커스를 유지해요. 기기 내 엔진 전용, 재부팅 후 속삭을 한 번 열어 주세요",
        "Review before inserting" to "넣기 전에 확인",
        "Show the text and wait, instead of typing it straight into the field" to "입력창에 바로 넣지 않고 텍스트를 보여 주고 기다려요",

        // Bubble
        "Bubble" to "버블",
        "Show bubble" to "버블 표시",
        "The floating tap-to-talk button" to "탭해서 말하는 플로팅 버튼",
        "Only on text fields" to "입력창에서만",
        "Needs auto-insert. The bubble stays visible until you turn it back on." to "자동 입력이 필요해요. 다시 켤 때까지 버블은 계속 보여요.",
        "Appear only when you can type" to "입력할 수 있을 때만 나타나요",

        // Privacy
        "Privacy" to "개인정보",
        "Keep history" to "기록 보관",
        "Stored on this device · powers personalization" to "이 기기에만 저장 · 맞춤 기능에 쓰여요",
        "Keep audio" to "녹음 보관",
        "Recordings stay on this device so a dictation that fails can be run again. Never uploaded." to
            "실패한 받아쓰기를 다시 돌릴 수 있게 녹음을 이 기기에 남겨요. 업로드하지 않아요.",
        "7 days" to "7일",
        "30 days" to "30일",
        "90 days" to "90일",
        "Forever" to "계속",
        "Clear all data" to "모든 데이터 지우기",
        "On-device history cleared" to "기기 내 기록을 지웠어요",

        // Personalization
        "Personalization" to "맞춤 설정",
        "Personal dictionary" to "개인 단어장",
        "Names & terms you taught it" to "가르친 이름과 용어",
        "Compare models" to "모델 비교",
        "Re-run a recent dictation on every model" to "최근 받아쓰기를 모든 모델로 다시 돌려 봐요",
        "Learned from edits" to "수정에서 배운 것",
        "Corrections it remembered" to "기억해 둔 고침",
        "Import from contacts" to "연락처에서 가져오기",
        "Matched on-device · never uploaded" to "기기 안에서만 맞춰요 · 업로드 안 함",

        // Reliability
        "Reliability" to "안정성",
        "Last clipboard fallback" to "마지막 클립보드 대체",
        "Auto-start helper" to "자동 실행 도우미",
        "For Samsung, Xiaomi, OnePlus, Oppo" to "삼성, 샤오미, 원플러스, 오포용",

        // Feedback
        "Feedback" to "피드백",
        "Report a problem" to "문제 신고",
        "Open an issue on GitHub" to "GitHub에 이슈 올리기",
        "Couldn't open a browser." to "브라우저를 열 수 없어요.",

        // Updates
        "Updates" to "업데이트",
        "GitHub token (optional)" to "GitHub 토큰 (선택)",
        "Optional. Only needed while %s is private: a fine-grained token with Contents: Read-only. Stored only on this phone." to
            "선택 사항이에요. %s 저장소가 비공개일 때만 필요해요: Contents: Read-only 권한만 준 fine-grained 토큰. 이 폰에만 저장돼요.",
        "Checking…" to "확인 중…",
        "Build %s is available." to "빌드 %s 업데이트가 있어요.",
        "Up to date (build %s)." to "최신이에요 (빌드 %s).",
        "Check failed." to "확인에 실패했어요.",
        "Build %s is available (installed: %s)." to "빌드 %s 업데이트가 있어요 (설치됨: %s).",
        "Installed: %s" to "설치됨: %s",
        "Allow Soksak to install updates, then tap Update again." to "속삭의 앱 설치를 허용한 뒤 업데이트를 다시 눌러 주세요.",
        "Installing… confirm if Android asks." to "설치 중… Android가 물으면 확인해 주세요.",
        "Update failed." to "업데이트에 실패했어요.",
        "Update" to "업데이트",
        "Check" to "확인",
        "Silent updates: on" to "무확인 업데이트: 켜짐",
        "Shizuku is running. Update installs without the confirmation screens." to "Shizuku 실행 중. 업데이트가 확인 화면 없이 설치돼요.",
        "Silent updates: allow Soksak" to "무확인 업데이트: 속삭 허용 필요",
        "Shizuku is running. Tap to let Soksak install updates through it." to "Shizuku 실행 중. 탭해서 속삭이 Shizuku로 설치하도록 허용하세요.",
        "Silent updates: start Shizuku" to "무확인 업데이트: Shizuku 시작 필요",
        "Shizuku isn't running (it stops on reboot). Tap to open it and start it with wireless debugging." to
            "Shizuku가 꺼져 있어요(재부팅하면 꺼져요). 탭해서 열고 무선 디버깅으로 시작하세요.",
        "Silent updates (optional)" to "무확인 업데이트 (선택)",
        "Install Shizuku to skip the scan and fingerprint screens on every update. Tap to get it." to
            "Shizuku를 설치하면 업데이트마다 나오는 스캔·지문 화면을 건너뛸 수 있어요. 탭해서 받기.",

        // Models: download/delete
        "Import failed" to "가져오기에 실패했어요",
        "Download failed" to "다운로드에 실패했어요",
        "Deleted %s — %sMB freed" to "%s 삭제됨 — %sMB 확보",
        "Deleted %s" to "%s 삭제됨",
        "Delete %s?" to "%s 삭제할까요?",
        "This frees %s on this device. The model stays available — you can download it again from this screen whenever you want it, which needs a connection." to
            "이 기기에서 %s를 비워요. 모델은 언제든 이 화면에서 다시 받을 수 있어요(인터넷 연결 필요).",
        "Delete" to "삭제",
        "Keep" to "유지",

        // General
        "General" to "일반",
        "Replay onboarding" to "온보딩 다시 보기",
        "Walk through setup again" to "설정 과정을 다시 진행해요",
        "Back up dictionary & settings" to "단어장·설정 백업",
        "Save a file to move them to another install (no tokens or keys)" to "다른 설치로 옮길 파일을 저장해요 (토큰·키는 빼고)",
        "Restore from backup" to "백업에서 복원",
        "Replaces the dictionary and settings with the file's" to "단어장과 설정을 파일 내용으로 바꿔요",
        "Backup saved." to "백업을 저장했어요.",
        "Backup failed." to "백업하지 못했어요.",
        "Backup restored." to "백업을 복원했어요.",
        "Restore failed." to "복원하지 못했어요.",
        "Open source licenses" to "오픈소스 라이선스",
        "Libraries, fonts and models this app uses" to "이 앱이 쓰는 라이브러리·폰트·모델",
        "Version" to "버전",
        "Language" to "언어",
        "Follow phone (%s)" to "휴대폰 설정 따름 (%s)",
        "한국어" to "한국어",
        "English" to "English",
        "Tap to change" to "탭해서 변경",

        // Quiet mode + bubble model menu
        "Quiet mode" to "조용히 말하기",
        "For whispering in a quiet office: keeps the whole recording, stops on your own voice level instead of the speech detector, and uses its own model. Also on the bubble's flick-up menu." to
            "조용한 사무실에서 속삭이듯 말할 때: 녹음을 자르지 않고 전부 쓰며, 음성 감지기 대신 내 목소리 크기로 자동 중지하고, 전용 모델을 써요. 버블을 위로 밀어 나오는 메뉴에서도 켜고 끌 수 있어요.",
        "Quiet mode model" to "조용히 말하기 모델",
        "%s · tap to change" to "%s · 탭해서 변경",
        "Speech model" to "음성 모델",
        "Download more in Settings" to "설정에서 더 받기",
        "Models…" to "모델…",
        "Off" to "꺼짐",
        // Send to PC (PcLink)
        "Send to PC" to "PC로 보내기",
        "Copies each dictation to the clipboard of your computer, directly over Tailscale. The computer runs soksak-daemon; `soksak-daemon pair` there shows the address and code. Nothing is sent while this is set to this phone only." to
            "받아쓴 글을 Tailscale로 내 컴퓨터에 바로 보내 클립보드에 넣어요. 컴퓨터에서 soksak-daemon을 실행하고, 거기서 `soksak-daemon pair`를 치면 주소와 코드가 나와요. \"이 폰만\"일 때는 아무것도 보내지 않아요.",
        "PC address" to "PC 주소",
        "Pairing code" to "연동 코드",
        "Check that the computer answers." to "컴퓨터가 응답하는지 확인해요.",
        "Test" to "시험",
        "Connecting…" to "연결 중…",
        "Connected." to "연결됐어요.",
        "Enter the PC address and pairing code first." to "PC 주소와 연동 코드를 먼저 입력하세요.",
        "PC not reachable" to "PC에 연결할 수 없어요",
        "PC refused the pairing code" to "PC가 연동 코드를 거부했어요",
        "PC could not copy the text" to "PC에서 글을 복사하지 못했어요",
        "PC address is not valid" to "PC 주소가 올바르지 않아요",
        "Send dictations to" to "받아쓴 글 보낼 곳",
        "This phone only" to "이 폰만",
        "Phone and PC" to "폰과 PC",
        "PC only" to "PC만",
        "The bubble stays visible everywhere, and the text is typed on this phone only if the computer can't be reached." to
            "버블이 어디서나 보이고, 컴퓨터에 닿지 않을 때만 이 폰에 입력해요.",
        "NPU diagnostics" to "NPU 진단 로그",
        "Adds step-by-step NPU timings to the debug log. Can slow the NPU, so leave it off normally" to "NPU 단계별 시간을 디버그 로그에 남겨요. NPU가 느려질 수 있어 평소에는 꺼 두세요",
        "Copy debug log" to "디버그 로그 복사",
        "This app's recent log, for reporting a problem" to "문제를 알려줄 때 쓰는 이 앱의 최근 로그",
        "Copied %s lines" to "%s줄 복사함",
        "Nothing was recognized." to "인식된 말이 없어요.",
    )
}

/** [en] in the Settings language. */
fun tr(en: String): String = if (I18n.korean) I18n.KO[en] ?: en else en

/** [tr] then `String.format` with [args] (templates use `%s`). */
fun trf(en: String, vararg args: Any?): String = tr(en).format(*args)
