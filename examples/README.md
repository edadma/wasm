# Examples

End-to-end programs that exercise the wasm interpreter from real-world toolchains. Each one is a self-contained directory with source, build steps, and a pre-built `.wasm` so you can run it without the matching toolchain installed.

| Example | Toolchain | Bytes | What it shows |
|---|---|---:|---|
| [`hello.wat`](./hello.wat) | hand-written WAT | 90 | The minimal non-WASI module: imports `env.putchar`, calls it 14 times, exports `main`. |
| [`c/`](./c/) | Homebrew LLVM (`clang` + `wasm-ld`) | 500 | Freestanding wasi C — no libc, no wasi-sdk; declares `fd_write` directly as an import. |
| [`rust/`](./rust/) | `rustc --target=wasm32-wasip1` | 62 KB | Real rustc-built wasi binary: `std::fs::read_to_string` + `println!` against a `--preopen` host directory. |

## Running

The simplest is `hello.wat` / `hello.wasm` — no preopens, no args:

```bash
sbt 'cliJVM/run examples/hello.wasm'
# Hello, world!
```

The C example uses fd 1 (stdout) only:

```bash
sbt 'cliJVM/run examples/c/hello.wasm'
# Hello from freestanding C!
```

The Rust example needs a host directory mounted as a WASI preopen, with an input file inside:

```bash
mkdir -p ./data
echo "The quick brown fox jumps over the lazy dog." > ./data/input.txt
echo "Pack my box with five dozen liquor jugs."    >> ./data/input.txt

sbt 'cliJVM/run --preopen ./data:/data examples/rust/word_count.wasm'
#        2       17       86 /data/input.txt
```

## Rebuilding from source

Each subdirectory has a README with build instructions:

- [`c/README.md`](./c/README.md) — Homebrew LLVM clang + `make`.
- [`rust/README.md`](./rust/README.md) — `cargo build --release --target=wasm32-wasip1`.

The pre-built `.wasm` files are committed so the examples are runnable on a fresh checkout. Re-run the build command after editing source.

## What's NOT here (and why)

- **A wasi-sdk C example.** The freestanding C example shows the ABI without depending on any sysroot; a wasi-sdk example would add a fairly heavy toolchain dependency for marginal pedagogical value. See [https://github.com/WebAssembly/wasi-sdk] if you want to try one.
- **A zig example.** Zig's `wasm32-wasi` target works on this interpreter (similar to rustc's), but the build adds another toolchain. Easy to add — just hasn't been a request.
