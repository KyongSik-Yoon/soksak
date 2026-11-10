# soksak-daemon

속삭 앱에서 받아쓴 글을 이 컴퓨터의 클립보드로 받는 작은 데몬입니다. 폰에서 말하고, 컴퓨터에서 붙여넣습니다.

- 폰이 컴퓨터로 **직접** 보냅니다. 중간 서버나 계정이 없습니다.
- 가는 것은 변환이 끝난 **글**뿐입니다. 소리는 폰을 떠나지 않습니다.
- 기본으로 **Tailscale** 주소에서 온 연결만 받습니다(Tailscale이 암호화). 여기에 연동 코드가 맞아야 클립보드에 씁니다.
- 의존 크레이트가 없습니다(Rust 표준 라이브러리만).

## 설치 (Linux, Wayland)

`wl-clipboard`와 Tailscale이 있어야 하고, 폰에도 Tailscale이 켜져 있어야 합니다.

```bash
cd daemon
cargo build --release
install -Dm755 target/release/soksak-daemon ~/.local/bin/soksak-daemon
install -Dm644 soksak-daemon.service ~/.config/systemd/user/soksak-daemon.service
systemctl --user daemon-reload
systemctl --user enable --now soksak-daemon
```

방화벽을 쓰면 Tailscale 인터페이스에서 포트를 엽니다. ufw 예:

```bash
sudo ufw allow in on tailscale0 to any port 48620 proto tcp
```

## 연동

```bash
soksak-daemon pair
```

여기 나오는 **PC 주소**와 **연동 코드**를 속삭의 설정 → PC로 보내기에 입력하고 "시험"을 누릅니다.
그다음 "받아쓴 글 보낼 곳"을 고릅니다(버블을 위로 밀어 나오는 메뉴에서도 바꿉니다).

- **이 폰만**: 보내지 않음(기본)
- **폰과 PC**: 폰 입력창에 넣고 컴퓨터 클립보드에도 복사
- **PC만**: 컴퓨터로만. 컴퓨터에 닿지 않으면 폰에 입력

코드를 바꾸려면 `soksak-daemon pair --new` 후 서비스를 다시 시작합니다(`systemctl --user restart soksak-daemon`).

## 설정

`~/.config/soksak-daemon/config` (없어도 됩니다):

```ini
port = 48620
# clipboard: 클립보드에만 / type: 클립보드에 넣고 포커스된 창에 타이핑까지
action = clipboard
# 글을 표준 입력으로 받는 명령. X11이면 xclip -selection clipboard, macOS면 pbcopy
copy_cmd = wl-copy
type_cmd = wtype -
# true면 Tailscale 밖의 연결도 받습니다. 그 경우 글이 암호화 없이 갑니다.
allow_lan = false
```

연동 코드는 `~/.config/soksak-daemon/code`(본인만 읽기)에 있습니다. 로그(`journalctl --user -u soksak-daemon`)에는
보낸 기기 주소와 글자 수만 남고 글 내용은 남지 않습니다.

## 시험

```bash
cargo test
soksak-daemon send 127.0.0.1:48620 "시험"   # 폰이 보내는 것과 같은 요청
```

프로토콜은 `src/lib.rs` 맨 위, 앱 쪽은 `android/app/src/main/java/com/voicerewriter/PcLink.kt`입니다.
