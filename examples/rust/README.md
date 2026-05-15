# Rust example — word_count

A real `wasm32-wasip1` binary built by rustc. Reads `/data/input.txt` from a wasi preopen, prints `wc -lwc`-style counts.

## Build

```bash
cd examples/rust
cargo build --release --target=wasm32-wasip1
cp target/wasm32-wasip1/release/word_count.wasm ./word_count.wasm
```

If `wasm32-wasip1` isn't installed yet:

```bash
rustup target add wasm32-wasip1
```

## Run

From the repo root:

```bash
mkdir -p ./data
echo "The quick brown fox jumps over the lazy dog." > ./data/input.txt
echo "Pack my box with five dozen liquor jugs."    >> ./data/input.txt

sbt 'cliJVM/run --preopen ./data:/data examples/rust/word_count.wasm'
#        2       18       85 /data/input.txt
```

The `--preopen ./data:/data` flag mounts the host's `./data` directory at the wasi-visible `/data`. The program opens `/data/input.txt`; everything inside `./data` is reachable, anything outside is not.

## What this demonstrates

- A real rustc-built WASI binary running on this interpreter, not a hand-written WAT toy.
- `std::fs::read_to_string` lowered into `path_open` + `fd_read` + `fd_close` against the host's `./data` directory through `HostPreopen.fromDir`.
- `println!` / `eprintln!` lowered into `fd_write` against fd 1 / fd 2.
- A non-zero `ExitCode` lowered into `proc_exit`, which the CLI surfaces as the process exit code.

The path is hardcoded inside the program because the current CLI (`wasm-cli` 0.1.1) doesn't expose a way to pass argv to a WASI binary — that's tracked as a small follow-up. Changing the path is a recompile away.
