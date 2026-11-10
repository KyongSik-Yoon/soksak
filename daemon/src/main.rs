use std::net::TcpListener;
use std::process::{Command, ExitCode};

use soksak_daemon::{config_dir, display_code, load_or_create_code, reset_code, send, serve, Config};

const USAGE: &str = "\
soksak-daemon          receive dictations from the Soksak app (what the service runs)
soksak-daemon pair     show the address and pairing code to enter on the phone
soksak-daemon pair --new   replace the pairing code
soksak-daemon send <host:port> <text>   send text to a daemon, as the phone would";

/// This machine's Tailscale address, if `tailscale` is installed and up.
fn tailscale_ip() -> Option<String> {
    let out = Command::new("tailscale").args(["ip", "-4"]).output().ok()?;
    let ip = String::from_utf8(out.stdout).ok()?.lines().next()?.trim().to_string();
    (out.status.success() && !ip.is_empty()).then_some(ip)
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let args: Vec<&str> = args.iter().map(String::as_str).collect();
    let dir = config_dir();
    let config = match Config::load(&dir) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("{}: {e}", dir.join("config").display());
            return ExitCode::FAILURE;
        }
    };
    let code = match args.as_slice() {
        ["pair", "--new"] => reset_code(&dir),
        _ => load_or_create_code(&dir),
    };
    let code = match code {
        Ok(c) => c,
        Err(e) => {
            eprintln!("{}: {e}", dir.join("code").display());
            return ExitCode::FAILURE;
        }
    };

    match args.as_slice() {
        [] | ["run"] => {
            let listener = match TcpListener::bind(("::", config.port))
                // Dual-stack where the system allows it; IPv4 only where IPv6 is off.
                .or_else(|_| TcpListener::bind(("0.0.0.0", config.port))) {
                Ok(l) => l,
                Err(e) => {
                    eprintln!("port {}: {e}", config.port);
                    return ExitCode::FAILURE;
                }
            };
            eprintln!(
                "listening on port {} ({}); run `soksak-daemon pair` for the pairing code",
                config.port,
                if config.allow_lan { "any network" } else { "Tailscale only" },
            );
            serve(listener, code, config);
            ExitCode::SUCCESS
        }
        ["pair"] | ["pair", "--new"] => {
            let host = tailscale_ip().unwrap_or_else(|| "<this computer's Tailscale IP>".into());
            println!("In Soksak: Settings -> Send to PC");
            println!("  PC address    {host}:{}", config.port);
            println!("  Pairing code  {}", display_code(&code));
            ExitCode::SUCCESS
        }
        ["send", addr, text] => match send(addr, &code, text, false) {
            Ok(_) => ExitCode::SUCCESS,
            Err(e) => {
                eprintln!("{e}");
                ExitCode::FAILURE
            }
        },
        _ => {
            eprintln!("{USAGE}");
            ExitCode::FAILURE
        }
    }
}
