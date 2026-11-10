//! Soksak PC link: the phone sends a finished dictation, this puts it on the clipboard.
//!
//! Wire format, one request per connection (the Android side is `PcLink.kt`):
//!
//! ```text
//! SOKSAK/1 <pairing code> <byte length>[ type]\n
//! <that many bytes of UTF-8 text>
//! ```
//!
//! answered with `OK\n`, `OK clipboard\n` or `ERR <reason>\n`. The text always goes on the
//! clipboard. With `type` the phone also asks for it to be typed into the focused window, which
//! happens only if this computer's config has `allow_type = true`; otherwise the answer is
//! `OK clipboard`, so the phone can say the text is waiting to be pasted. A length of 0 is a
//! connection test: the code is checked, nothing is copied, and the answer is the one a real
//! request would get.
//!
//! There is no encryption here. The daemon only answers peers on the Tailscale network (which
//! is WireGuard) unless `allow_lan` is set, and the pairing code keeps other devices on that
//! network from writing to the clipboard.

use std::fs;
use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

pub const DEFAULT_PORT: u16 = 48620;
pub const PROTOCOL: &str = "SOKSAK/1";
/// A long dictation is a few KB; this only stops a peer from making us buffer without limit.
pub const MAX_TEXT_BYTES: usize = 256 * 1024;
const MAX_HEADER_BYTES: usize = 128;
const IO_TIMEOUT: Duration = Duration::from_secs(5);
/// No 0/O or 1/I/L: the code is read off a terminal and typed on a phone.
const CODE_ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const CODE_LEN: usize = 10;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Config {
    pub port: u16,
    /// Let the phone ask for the text to be typed into the focused window. Off by default:
    /// typing into a terminal runs what was said, so that is this computer's call, not the
    /// phone's.
    pub allow_type: bool,
    /// Run through `sh -c` with the text on stdin.
    pub copy_cmd: String,
    pub type_cmd: String,
    /// Answer peers outside Tailscale too. Off: the text would cross the LAN unencrypted.
    pub allow_lan: bool,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            port: DEFAULT_PORT,
            allow_type: false,
            copy_cmd: "wl-copy".into(),
            type_cmd: "wtype -".into(),
            allow_lan: false,
        }
    }
}

impl Config {
    /// `key = value` lines; `#` starts a comment. Unknown keys are errors so a typo is noticed.
    pub fn parse(text: &str) -> Result<Config, String> {
        let mut c = Config::default();
        for (n, line) in text.lines().enumerate() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let (key, value) = line
                .split_once('=')
                .ok_or_else(|| format!("line {}: expected key = value", n + 1))?;
            let (key, value) = (key.trim(), value.trim());
            match key {
                "port" => c.port = value.parse().map_err(|_| format!("line {}: bad port", n + 1))?,
                "allow_type" => c.allow_type = flag(value).ok_or_else(|| format!("line {}: allow_type is true or false", n + 1))?,
                "copy_cmd" => c.copy_cmd = value.into(),
                "type_cmd" => c.type_cmd = value.into(),
                "allow_lan" => c.allow_lan = flag(value).ok_or_else(|| format!("line {}: allow_lan is true or false", n + 1))?,
                _ => return Err(format!("line {}: unknown key {key}", n + 1)),
            }
        }
        Ok(c)
    }

    pub fn load(dir: &Path) -> Result<Config, String> {
        match fs::read_to_string(dir.join("config")) {
            Ok(text) => Config::parse(&text),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(Config::default()),
            Err(e) => Err(format!("config: {e}")),
        }
    }
}

fn flag(value: &str) -> Option<bool> {
    match value {
        "true" => Some(true),
        "false" => Some(false),
        _ => None,
    }
}

/// `$SOKSAK_DAEMON_DIR`, else `$XDG_CONFIG_HOME/soksak-daemon`, else `~/.config/soksak-daemon`.
pub fn config_dir() -> PathBuf {
    if let Some(d) = std::env::var_os("SOKSAK_DAEMON_DIR") {
        return PathBuf::from(d);
    }
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".config"));
    base.join("soksak-daemon")
}

/// Upper-case letters and digits only, so `abcd-efgh 23` and `ABCDEFGH23` are the same code.
pub fn normalize_code(code: &str) -> String {
    code.chars().filter(|c| c.is_ascii_alphanumeric()).map(|c| c.to_ascii_uppercase()).collect()
}

/// `ABCDE-FGHJK`, as shown to the user.
pub fn display_code(code: &str) -> String {
    let (a, b) = code.split_at(code.len() / 2);
    format!("{a}-{b}")
}

fn new_code() -> io::Result<String> {
    let mut out = String::new();
    let mut urandom = fs::File::open("/dev/urandom")?;
    let mut byte = [0u8; 1];
    // Rejection sampling: a plain modulo would favour the first letters of the alphabet.
    let limit = 256 - 256 % CODE_ALPHABET.len();
    while out.len() < CODE_LEN {
        urandom.read_exact(&mut byte)?;
        if (byte[0] as usize) < limit {
            out.push(CODE_ALPHABET[byte[0] as usize % CODE_ALPHABET.len()] as char);
        }
    }
    Ok(out)
}

/// The pairing code in `dir/code`, made on first use and readable only by this user.
pub fn load_or_create_code(dir: &Path) -> io::Result<String> {
    let path = dir.join("code");
    if let Ok(existing) = fs::read_to_string(&path) {
        let code = normalize_code(&existing);
        if !code.is_empty() {
            return Ok(code);
        }
    }
    fs::create_dir_all(dir)?;
    let code = new_code()?;
    let mut opts = fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    opts.open(&path)?.write_all(format!("{code}\n").as_bytes())?;
    Ok(code)
}

/// Replace the pairing code (`soksak-daemon pair --new`): every paired phone must re-enter it.
pub fn reset_code(dir: &Path) -> io::Result<String> {
    let _ = fs::remove_file(dir.join("code"));
    load_or_create_code(dir)
}

/// Compares every byte whatever the first difference, so timing says nothing about the code.
pub fn same_code(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    let mut diff = a.len() ^ b.len();
    for i in 0..a.len().max(b.len()) {
        diff |= (*a.get(i).unwrap_or(&0) ^ *b.get(i).unwrap_or(&0)) as usize;
    }
    diff == 0
}

/// This machine and the Tailscale ranges (100.64.0.0/10, fd7a:115c:a1e0::/48).
pub fn peer_allowed(ip: IpAddr, allow_lan: bool) -> bool {
    if allow_lan || ip.is_loopback() {
        return true;
    }
    match ip {
        IpAddr::V4(v4) => tailscale_v4(v4),
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => v4.is_loopback() || tailscale_v4(v4),
            None => {
                let s = v6.segments();
                s[0] == 0xfd7a && s[1] == 0x115c && s[2] == 0xa1e0
            }
        },
    }
}

fn tailscale_v4(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    o[0] == 100 && (o[1] & 0xc0) == 64
}

#[derive(Debug, PartialEq, Eq)]
pub struct Header {
    pub code: String,
    pub len: usize,
    /// The phone asked for the text to be typed, not only copied.
    pub type_text: bool,
}

/// Parses `SOKSAK/1 <code> <len>[ type]` (without the newline).
pub fn parse_header(line: &str) -> Result<Header, &'static str> {
    let mut parts = line.trim_end_matches('\r').split(' ');
    if parts.next() != Some(PROTOCOL) {
        return Err("protocol");
    }
    let code = parts.next().ok_or("header")?;
    let len: usize = parts.next().ok_or("header")?.parse().map_err(|_| "header")?;
    let type_text = match parts.next() {
        None => false,
        Some("type") => true,
        Some(_) => return Err("header"),
    };
    if parts.next().is_some() {
        return Err("header");
    }
    if len > MAX_TEXT_BYTES {
        return Err("too long");
    }
    Ok(Header { code: normalize_code(code), len, type_text })
}

pub fn request(code: &str, text: &str, type_text: bool) -> Vec<u8> {
    let ask = if type_text { " type" } else { "" };
    let mut out = format!("{PROTOCOL} {} {}{ask}\n", normalize_code(code), text.len()).into_bytes();
    out.extend_from_slice(text.as_bytes());
    out
}

/// Runs `cmd` through the shell with `text` on stdin. Output is discarded rather than piped:
/// `wl-copy` leaves a child holding the selection, and a pipe it inherited would never close.
pub fn feed(cmd: &str, text: &str) -> Result<(), String> {
    let mut child = Command::new("sh")
        .arg("-c")
        .arg(cmd)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("{cmd}: {e}"))?;
    let written = child.stdin.take().expect("piped stdin").write_all(text.as_bytes());
    let status = child.wait().map_err(|e| format!("{cmd}: {e}"))?;
    written.map_err(|e| format!("{cmd}: {e}"))?;
    if status.success() {
        Ok(())
    } else {
        Err(format!("{cmd}: {status}"))
    }
}

const OK: &str = "OK";
/// The phone asked for typing and this computer doesn't allow it: copied, waiting to be pasted.
const OK_CLIPBOARD: &str = "OK clipboard";

fn read_header(stream: &mut TcpStream) -> Result<String, &'static str> {
    let mut line = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        match stream.read(&mut byte) {
            Ok(1) if byte[0] == b'\n' => break,
            Ok(1) => line.push(byte[0]),
            _ => return Err("header"),
        }
        if line.len() > MAX_HEADER_BYTES {
            return Err("header");
        }
    }
    String::from_utf8(line).map_err(|_| "header")
}

/// One request. Returns what to log (never the text itself) and the reply.
fn handle(stream: &mut TcpStream, code: &str, config: &Config) -> Result<(String, &'static str), String> {
    let header = parse_header(&read_header(stream)?)?;
    if !same_code(&header.code, code) {
        // Slows down guessing; a mistyped code on the phone waits a second for its answer.
        thread::sleep(Duration::from_secs(1));
        return Err("code".into());
    }
    let typing = header.type_text && config.allow_type;
    let reply = if header.type_text && !typing { OK_CLIPBOARD } else { OK };
    if header.len == 0 {
        return Ok(("connection test".into(), reply));
    }
    let mut body = vec![0u8; header.len];
    stream.read_exact(&mut body).map_err(|_| "body")?;
    let text = String::from_utf8(body).map_err(|_| "utf-8")?;
    // The clipboard first, also when typing: if the wrong window had focus the text is not lost.
    feed(&config.copy_cmd, &text)?;
    if typing {
        feed(&config.type_cmd, &text)?;
    }
    let how = if typing { "typed" } else { "copied" };
    Ok((format!("{} characters {how}", text.chars().count()), reply))
}

fn serve_one(mut stream: TcpStream, peer: SocketAddr, code: &str, config: &Config) {
    // 100.x rather than ::ffff:100.x in the log, now that the socket is dual-stack.
    let peer = SocketAddr::new(peer.ip().to_canonical(), peer.port());
    if !peer_allowed(peer.ip(), config.allow_lan) {
        eprintln!("{}: refused, not on Tailscale", peer.ip());
        return;
    }
    let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
    let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
    let reply = match handle(&mut stream, code, config) {
        Ok((what, reply)) => {
            eprintln!("{}: {what}", peer.ip());
            format!("{reply}\n")
        }
        Err(why) => {
            eprintln!("{}: rejected ({why})", peer.ip());
            // Only the first word reaches the phone: command errors can hold local paths.
            format!("ERR {}\n", why.split(':').next().unwrap_or("error"))
        }
    };
    let _ = stream.write_all(reply.as_bytes());
    // A rejected request still has its text in flight. Closing with that unread resets the
    // connection and the phone loses the reply, so take what is left (bounded) first.
    let _ = stream.shutdown(Shutdown::Write);
    let _ = stream.set_read_timeout(Some(Duration::from_secs(1)));
    let _ = io::copy(&mut (&stream).take(MAX_TEXT_BYTES as u64), &mut io::sink());
}

/// Accepts connections until the process ends.
pub fn serve(listener: TcpListener, code: String, config: Config) {
    let shared = Arc::new((code, config));
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let Ok(peer) = stream.peer_addr() else { continue };
        let shared = Arc::clone(&shared);
        thread::spawn(move || serve_one(stream, peer, &shared.0, &shared.1));
    }
}

/// Client side, for `soksak-daemon send` and the tests. Ok carries the daemon's `OK…` line.
pub fn send(addr: &str, code: &str, text: &str, type_text: bool) -> Result<String, String> {
    let mut stream = TcpStream::connect(addr).map_err(|e| e.to_string())?;
    let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
    stream.write_all(&request(code, text, type_text)).map_err(|e| e.to_string())?;
    let mut reply = String::new();
    stream.read_to_string(&mut reply).map_err(|e| e.to_string())?;
    match reply.trim_end() {
        ok @ (OK | OK_CLIPBOARD) => Ok(ok.to_string()),
        other => Err(other.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_ignore_case_and_separators() {
        assert_eq!(normalize_code("abcde-fgh 23\n"), "ABCDEFGH23");
        assert_eq!(display_code("ABCDEFGH23"), "ABCDE-FGH23");
    }

    #[test]
    fn code_comparison() {
        assert!(same_code("ABCDEFGH23", "ABCDEFGH23"));
        assert!(!same_code("ABCDEFGH23", "ABCDEFGH24"));
        assert!(!same_code("ABCDEFGH23", "ABCDEFGH2"));
        assert!(!same_code("", "A"));
    }

    #[test]
    fn header_round_trip() {
        for type_text in [false, true] {
            let req = request("abcde-fgh23", "안녕 hello", type_text);
            let text = String::from_utf8(req).unwrap();
            let (line, body) = text.split_once('\n').unwrap();
            assert_eq!(parse_header(line), Ok(Header { code: "ABCDEFGH23".into(), len: body.len(), type_text }));
            assert_eq!(body, "안녕 hello");
        }
        // What the phone sends, spelled out: PcLinkTest.kt checks the same bytes.
        assert_eq!(request("ABCDEFGH23", "hi", false), b"SOKSAK/1 ABCDEFGH23 2\nhi");
        assert_eq!(request("ABCDEFGH23", "hi", true), b"SOKSAK/1 ABCDEFGH23 2 type\nhi");
    }

    #[test]
    fn bad_headers_are_refused() {
        assert_eq!(parse_header("GET / HTTP/1.1"), Err("protocol"));
        assert_eq!(parse_header("SOKSAK/1 CODE"), Err("header"));
        assert_eq!(parse_header("SOKSAK/1 CODE x"), Err("header"));
        assert_eq!(parse_header("SOKSAK/1 CODE 1 extra"), Err("header"));
        assert_eq!(parse_header("SOKSAK/1 CODE 1 type extra"), Err("header"));
        assert_eq!(parse_header(&format!("SOKSAK/1 CODE {}", MAX_TEXT_BYTES + 1)), Err("too long"));
    }

    #[test]
    fn only_tailscale_and_this_machine_by_default() {
        let ip = |s: &str| s.parse::<IpAddr>().unwrap();
        assert!(peer_allowed(ip("127.0.0.1"), false));
        assert!(peer_allowed(ip("::1"), false));
        assert!(peer_allowed(ip("100.64.0.1"), false));
        assert!(peer_allowed(ip("100.100.1.2"), false));
        assert!(peer_allowed(ip("::ffff:100.100.1.2"), false));
        assert!(peer_allowed(ip("fd7a:115c:a1e0::1"), false));
        assert!(!peer_allowed(ip("100.128.0.1"), false));
        assert!(!peer_allowed(ip("192.168.0.10"), false));
        assert!(!peer_allowed(ip("8.8.8.8"), false));
        assert!(!peer_allowed(ip("fd00::1"), false));
        assert!(peer_allowed(ip("192.168.0.10"), true));
    }

    #[test]
    fn config_file() {
        assert_eq!(Config::parse("# nothing\n\n"), Ok(Config::default()));
        assert!(!Config::default().allow_type);
        let c = Config::parse("port = 5000\nallow_type = true\ncopy_cmd = xclip -selection clipboard\nallow_lan = true\n").unwrap();
        assert_eq!(c.port, 5000);
        assert!(c.allow_type);
        assert_eq!(c.copy_cmd, "xclip -selection clipboard");
        assert!(c.allow_lan);
        assert!(Config::parse("prot = 1").is_err());
        assert!(Config::parse("allow_type = yes").is_err());
    }

    #[test]
    fn generated_codes_use_the_alphabet() {
        let code = new_code().unwrap();
        assert_eq!(code.len(), CODE_LEN);
        assert!(code.bytes().all(|b| CODE_ALPHABET.contains(&b)));
    }
}
