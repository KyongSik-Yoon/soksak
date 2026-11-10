//! The daemon end to end over a real socket, with a stand-in for the clipboard command.

use std::fs;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::thread;

use soksak_daemon::{send, serve, Config};

const CODE: &str = "TESTCODE23";

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("soksak-daemon-test-{}-{name}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

/// A daemon on a free local port whose "clipboard" is `dir/copied` and "keyboard" `dir/typed`.
fn start(dir: &Path, allow_type: bool) -> String {
    let config = Config {
        allow_type,
        copy_cmd: format!("cat > {}", dir.join("copied").display()),
        type_cmd: format!("cat > {}", dir.join("typed").display()),
        ..Config::default()
    };
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap().to_string();
    thread::spawn(move || serve(listener, CODE.into(), config));
    addr
}

#[test]
fn text_reaches_the_clipboard_command() {
    let dir = scratch("copy");
    let addr = start(&dir, true);
    let reply = send(&addr, "testc-ode23", "GitLab에 MR 올리고,\nworktree 정리해줘.", false);
    assert_eq!(reply, Ok("OK".into()));
    assert_eq!(fs::read_to_string(dir.join("copied")).unwrap(), "GitLab에 MR 올리고,\nworktree 정리해줘.");
    // Allowed here, but the phone didn't ask.
    assert!(!dir.join("typed").exists());
}

#[test]
fn typing_happens_when_asked_and_allowed() {
    let dir = scratch("type");
    let addr = start(&dir, true);
    assert_eq!(send(&addr, CODE, "hello", true), Ok("OK".into()));
    assert_eq!(fs::read_to_string(dir.join("copied")).unwrap(), "hello");
    assert_eq!(fs::read_to_string(dir.join("typed")).unwrap(), "hello");
}

#[test]
fn typing_is_refused_unless_this_computer_allows_it() {
    let dir = scratch("notype");
    let addr = start(&dir, false);
    assert_eq!(send(&addr, CODE, "hello", true), Ok("OK clipboard".into()));
    assert_eq!(fs::read_to_string(dir.join("copied")).unwrap(), "hello");
    assert!(!dir.join("typed").exists());
    // The connection test says the same, so the phone can tell before a dictation.
    assert_eq!(send(&addr, CODE, "", true), Ok("OK clipboard".into()));
    assert_eq!(send(&addr, CODE, "", false), Ok("OK".into()));
}

#[test]
fn wrong_code_copies_nothing() {
    let dir = scratch("code");
    let addr = start(&dir, false);
    assert_eq!(send(&addr, "WRONGCODE2", "secret", false), Err("ERR code".into()));
    assert!(!dir.join("copied").exists());
}

#[test]
fn connection_test_copies_nothing() {
    let dir = scratch("ping");
    let addr = start(&dir, false);
    send(&addr, CODE, "", false).unwrap();
    assert!(!dir.join("copied").exists());
}

#[test]
fn other_protocols_get_an_error_line() {
    let dir = scratch("http");
    let addr = start(&dir, false);
    let mut s = TcpStream::connect(&addr).unwrap();
    s.write_all(b"GET / HTTP/1.1\r\n\r\n").unwrap();
    let mut reply = String::new();
    let _ = s.read_to_string(&mut reply);
    assert_eq!(reply, "ERR protocol\n");
}

#[test]
fn a_failing_clipboard_command_is_reported() {
    let dir = scratch("fail");
    let config = Config { copy_cmd: "cat > /dev/null; exit 3".into(), ..Config::default() };
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap().to_string();
    thread::spawn(move || serve(listener, CODE.into(), config));
    let err = send(&addr, CODE, "x", false).unwrap_err();
    assert!(err.starts_with("ERR "), "{err}");
    drop(dir);
}
