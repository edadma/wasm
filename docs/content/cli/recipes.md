---
title: Recipes
summary: Hand-written WAT, rustc-built WASI binaries, native CLI builds.
weight: 20
---

## Hand-written WAT, no WASI

The committed `examples/hello.wasm` is a hand-written WAT module that calls `env.putchar` 13 times. No WASI imports, no preopens needed:

```bash
sbt 'cliJVM/run examples/hello.wasm'
# Hello, world!
```

The matching `.wat` source is at `examples/hello.wat`. After editing, regenerate the binary with `wat2wasm`:

```bash
wat2wasm examples/hello.wat -o examples/hello.wasm
```

## Freestanding C, no libc

`examples/c/hello.wasm` is a freestanding C program — no libc, no wasi-sdk. The source declares `fd_write` directly as a wasi import and defines `_start` as the entry point; the build is just `clang --target=wasm32 -nostdlib` plus `wasm-ld`. Useful for understanding what a wasi binary actually is once you strip the libc convenience layer off.

```bash
sbt 'cliJVM/run examples/c/hello.wasm'
# Hello from freestanding C!
```

The matching `examples/c/hello.c` source and `Makefile` are committed alongside; see [`examples/c/README.md`](https://github.com/edadma/wasm/tree/dev/examples/c) for the build invocation.

## Real WASI binary with a host-backed preopen

`examples/rust/word_count.wasm` is a rustc-built `wasm32-wasip1` binary that reads a path passed as `argv[1]` from a wasi preopen and prints `wc -lwc`-style counts:

```bash
mkdir -p ./data
echo "The quick brown fox jumps over the lazy dog." > ./data/input.txt
echo "Pack my box with five dozen liquor jugs."    >> ./data/input.txt

sbt 'cliJVM/run --preopen ./data:/data examples/rust/word_count.wasm /data/input.txt'
#        2       17       86 /data/input.txt
```

The `--preopen` flag points the wasi-libc startup walk at the host's `./data` directory and tells the guest the visible name is `/data`. The trailing `/data/input.txt` is forwarded to the program as `argv[1]` — wasm-cli treats any positional args after the wasm file as WASI argv. From the rust binary's perspective, `/data/input.txt` resolves; absolute paths outside `/data` don't.

The matching `examples/rust/src/main.rs` source and `Cargo.toml` are committed; rebuild with `cargo build --release --target=wasm32-wasip1 --manifest-path=examples/rust/Cargo.toml`.

## Real WASI binary with file writes

The test-suite fixture `real_rust_filewrite.wasm` writes a small file under its preopen — useful for confirming write capability against a host directory:

```bash
rm -rf /tmp/wasm-write
mkdir -p /tmp/wasm-write
sbt 'cliJVM/run --preopen /tmp/wasm-write:/sandbox \
                wasi/shared/src/test/resources/fixtures/real_rust_filewrite.wasm'
cat /tmp/wasm-write/output.txt
```

The path-sandbox rules apply: the rust binary asking for `/sandbox/output.txt` writes to `/tmp/wasm-write/output.txt`; the same binary asking for `../escape` returns `ENOTCAPABLE`.

## Native CLI binary

`cliNative` builds a standalone Scala Native binary — no JVM startup overhead, useful when you want to run `wasm` in a shell pipeline:

```bash
sbt cliNative/nativeLink
./cli/native/target/scala-3.8.3/wasm-cli-out \
    --preopen /tmp/sandbox:/sandbox \
    real_rust_fileread.wasm
```

The Scala Native build uses the same `HostPreopen.fromDir` shape as the JVM build but backs it with `java.nio.file` (via Scala Native's JVM-compatibility layer).

## Scala.js CLI (Node.js)

sbt's command-line parser doesn't forward positional args to `scalajs`'s `run` task, so the JS CLI runs through Node directly:

```bash
sbt cliJS/fastLinkJS
node cli/js/target/scala-3.8.3/wasm-cli-fastopt/main.js \
     --preopen /tmp/sandbox:/sandbox \
     real_rust_fileread.wasm
```

The Scala.js build backs `HostPreopen.fromDir` with Node's `fs.*Sync` APIs. Same trait surface, different syscalls underneath.

## Inspecting a module without running it

`--list-exports` prints every exported function without instantiating:

```bash
sbt 'cliJVM/run --list-exports my-module.wasm'
```

Useful when picking an `--invoke` target, or when sanity-checking that a build pipeline produced the symbols you expect.
