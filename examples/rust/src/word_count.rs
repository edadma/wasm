//! word_count — read a text file from a wasi preopen and print line / word / char counts.
//!
//! Usage from the wasm CLI:
//!
//!     sbt 'cliJVM/run --preopen ./data:/data examples/rust/word_count.wasm /data/input.txt'
//!
//! The `--preopen ./data:/data` flag mounts the host's `./data` directory at the
//! wasi-visible `/data`. The trailing `/data/input.txt` is passed to the program
//! as `argv[1]` — wasm-cli forwards any positional args after the wasm file as
//! WASI argv. `std::fs::read_to_string(path)` lowers into `path_open` +
//! `fd_read` + `fd_close` against the host directory under the hood.

use std::env;
use std::fs;
use std::process::ExitCode;

fn main() -> ExitCode {
    // argv[0] is the program name (the wasm CLI derives it from the file
    // basename). argv[1] is the path we want to read.
    let args: Vec<String> = env::args().collect();
    if args.len() != 2 {
        let prog = args.first().map(String::as_str).unwrap_or("word_count");
        eprintln!("usage: {} <file>", prog);
        return ExitCode::from(2);
    }

    let path = &args[1];
    let contents = match fs::read_to_string(path) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("word_count: {}: {}", path, e);
            return ExitCode::from(1);
        }
    };

    // Lines: count newline-terminated segments. `lines()` skips a trailing
    // newline so a file ending in "\n" doesn't report a phantom empty line
    // (matches GNU wc -l).
    let lines = contents.lines().count();

    // Words: any non-empty run of non-whitespace. `split_whitespace` already
    // collapses runs and ignores empties (wc -w behaviour).
    let words = contents.split_whitespace().count();

    // Chars: byte length. wc -c reports bytes; for true Unicode-character
    // counts you'd want `contents.chars().count()` instead.
    let chars = contents.len();

    println!("{:>8} {:>8} {:>8} {}", lines, words, chars, path);
    ExitCode::SUCCESS
}
