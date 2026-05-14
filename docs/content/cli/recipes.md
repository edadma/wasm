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

## Real WASI binary with a host-backed preopen

`real_rust_fileread.wasm` is a rustc-built `wasm32-wasip1` binary that calls `std::fs::read_to_string("/sandbox/hello.txt")` and prints the contents. The matching `.rs` source is committed alongside as documentation.

```bash
mkdir -p /tmp/sandbox
echo "Hello from the host filesystem" > /tmp/sandbox/hello.txt

sbt 'cliJVM/run --preopen /tmp/sandbox:/sandbox \
                wasi/shared/src/test/resources/fixtures/real_rust_fileread.wasm'
# Hello from the host filesystem
```

The `--preopen` flag points the wasi-libc startup walk at `/tmp/sandbox` (real disk) and tells the guest the directory's visible name is `/sandbox`. From the rust binary's perspective, `/sandbox/hello.txt` resolves; absolute paths outside `/sandbox` don't.

## Real WASI binary with file writes

`real_rust_filewrite.wasm` writes a small file under its preopen. To run it against a real host directory, point a fresh directory at `/sandbox`:

```bash
rm -rf /tmp/wasm-write && mkdir -p /tmp/wasm-write
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
