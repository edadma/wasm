//! word_count — read `/data/input.txt` from a wasi preopen and print line / word / char counts.
//!
//! Usage from the wasm CLI:
//!
//!     sbt 'cliJVM/run --preopen ./data:/data examples/rust/word_count.wasm'
//!
//! The `--preopen ./data:/data` flag mounts the host's `./data` directory at the
//! wasi-visible path `/data`. The program then opens `/data/input.txt` through
//! the standard rust filesystem API; under the hood, rustc's stdlib lowers that
//! into `path_open`, `fd_read`, and friends, all served by the wasm interpreter's
//! WASI shim.

use std::fs;
use std::process::ExitCode;

const INPUT_PATH: &str = "/data/input.txt";

fn main() -> ExitCode {
    let contents = match fs::read_to_string(INPUT_PATH) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("word_count: {}: {}", INPUT_PATH, e);
            return ExitCode::from(1);
        }
    };

    // Lines: count newline-terminated segments. `lines()` skips a trailing
    // newline so a file ending in "\n" doesn't report a phantom empty line —
    // this is the GNU wc -l shape.
    let lines = contents.lines().count();

    // Words: any non-empty run of non-whitespace. `split_whitespace` already
    // collapses runs and ignores empties (wc -w behaviour).
    let words = contents.split_whitespace().count();

    // Chars: byte length. wc -c reports bytes; for true Unicode-character
    // counts you'd want `contents.chars().count()` instead.
    let chars = contents.len();

    println!("{:>8} {:>8} {:>8} {}", lines, words, chars, INPUT_PATH);
    ExitCode::SUCCESS
}
