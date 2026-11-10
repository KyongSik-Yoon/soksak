//! The daemon end to end over a real socket, with a stand-in for the clipboard command.

use std::fs;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::thread;

use soksak_daemon::{send, serve, Action, Config};

const CODE: &str = "TESTCODE23";

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("soksak-daemon-test-{}-{name}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

/// A daemon on a free local port whose "clipboard" is `dir/copied` and "keyboard" `dir/typed`.
fn start(dir: &Path, action: Action) -> String {
    let config = Config {
        action,
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
    let addr = start(&dir, Action::Clipboard);
    send(&addr, "testc-ode23", "GitLab에 MR 올리고,\nworktree 정리해줘.").unwrap();
    assert_eq!(fs::read_to_string(dir.join("copied")).unwrap(), "GitLab에 MR 올리고,\nworktree 정리해줘.");
    assert!(!dir.join("typed").exists());
}

#[test]
fn type_action_copies_and_types() {
    let dir = scratch("type");
    let addr = start(&dir, Action::Type);
    send(&addr, CODE, "hello").unwrap();
    assert_eq!(fs::read_to_string(dir.join("copied")).unwrap(), "hello");
    assert_eq!(fs::read_to_string(dir.join("typed")).unwrap(), "hello");
}

#[test]
fn wrong_code_copies_nothing() {
    let dir = scratch("code");
    let addr = start(&dir, Action::Clipboard);
    assert_eq!(send(&addr, "WRONGCODE2", "secret"), Err("ERR code".into()));
    assert!(!dir.join("copied").exists());
}

#[test]
fn connection_test_copies_nothing() {
    let dir = scratch("ping");
    let addr = start(&dir, Action::Clipboard);
    send(&addr, CODE, "").unwrap();
    assert!(!dir.join("copied").exists());
}

#[test]
fn other_protocols_get_an_error_line() {
    let dir = scratch("http");
    let addr = start(&dir, Action::Clipboard);
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
    let err = send(&addr, CODE, "x").unwrap_err();
    assert!(err.starts_with("ERR "), "{err}");
    drop(dir);
}
