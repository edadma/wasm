# Rust example — word_count

A real `wasm32-wasip1` binary built by rustc. Reads a path passed as argv[1] from a wasi preopen, prints `wc -lwc`-style counts.

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

sbt 'cliJVM/run --preopen ./data:/data examples/rust/word_count.wasm /data/input.txt'
#        2       17       86 /data/input.txt
```

The `--preopen ./data:/data` flag mounts the host's `./data` directory at the wasi-visible `/data`. The trailing `/data/input.txt` is forwarded to the program as `argv[1]` — wasm-cli treats any positional args after the wasm file as WASI argv. Use `--` ahead of these if any value starts with `-`:

```bash
sbt 'cliJVM/run --preopen ./data:/data examples/rust/word_count.wasm -- /data/input.txt -v'
```

## What this demonstrates

- A real rustc-built WASI binary running on this interpreter, not a hand-written WAT toy.
- `std::env::args` lowered into `args_get` + `args_sizes_get`. The wasm CLI populates argv as `[<file-basename>, <trailing-args>…]`.
- `std::fs::read_to_string` lowered into `path_open` + `fd_read` + `fd_close` against the host's `./data` directory through `HostPreopen.fromDir`.
- `println!` / `eprintln!` lowered into `fd_write` against fd 1 / fd 2.
- A non-zero `ExitCode` lowered into `proc_exit`, which the CLI surfaces as the process exit code.
