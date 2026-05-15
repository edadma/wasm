//! env_echo — print every environment variable as `KEY=VALUE`, one per line,
//! sorted by key. Driven by the `--env` CLI flag's pass-through.
//!
//! Usage:
//!
//!     sbt 'cliJVM/run -e HOME=/root -e LANG=C.UTF-8 examples/rust/env_echo.wasm'
//!     # HOME=/root
//!     # LANG=C.UTF-8

use std::env;

fn main() {
    // std::env::vars iterates in unspecified order; sort by key so the
    // output is deterministic for testing.
    let mut pairs: Vec<(String, String)> = env::vars().collect();
    pairs.sort_by(|a, b| a.0.cmp(&b.0));
    for (k, v) in pairs {
        println!("{k}={v}");
    }
}
