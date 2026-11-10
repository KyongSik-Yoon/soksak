---
description: 변경을 커밋·push하고 CI 빌드를 기다려 폰 업데이트까지 안내한다
---

현재 작업 트리의 변경을 배포한다. $ARGUMENTS 가 있으면 커밋 메시지 요지로 쓴다.

1. `git status`로 변경 확인. 제거·이름변경한 심볼이 있으면 `git grep`으로 남은 참조가 없는지 확인한다.
2. Settings 필드를 건드렸다면 `Settings.kt`와 `SettingsActivity.snapshot()`이 맞는지 확인한다 (CLAUDE.md 작업 규칙).
3. `scripts/check-harness-refs.sh` 실행. 실패하면 CLAUDE.md를 코드에 맞게 고친다.
4. 커밋: `git commit`(작성자 `KyongSik-Yoon <sam1287@gmail.com>`은 저장소 로컬 config에 설정됨, `git config user.email`로 확인) — 제목은 영어 한 줄, 본문에 이유.
   세션이 요구하는 attribution 줄을 끝에 붙인다.
5. `git push <remote> HEAD:main` (remote는 KyongSik-Yoon/soksak을 가리키는 것).
6. `scripts/ci-wait.sh` 로 결과를 기다린다.
   - 실패: 출력된 Kotlin 에러/테스트 실패를 고치고 2단계부터 반복.
   - 성공: build 번호와 "앱을 열면 업데이트 창이 뜬다"를 사용자에게 한국어로 짧게 알린다.
7. `.claude/CHANGELOG.md` 맨 위에 커밋 SHA와 사용자 관점 변화 한 줄을 추가하고 같은 방식으로 커밋·push한다.
   `*.md`, `.claude/**`, `scripts/**`만 바뀐 커밋은 CI가 돌지 않는다(새 build·업데이트 창 없음).
