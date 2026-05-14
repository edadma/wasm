---
title: wasm
heroTitle: A Scala 3
heroHighlight: WebAssembly interpreter
summary: WebAssembly Core MVP plus sign-extension, full bulk-memory, non-trapping float-to-int, reference types (including typed select), and multi-memory (with a multi-memory host-function surface) — runs real rustc-built wasm32-wasip1 binaries end-to-end through a 24-syscall WASI Preview 1 shim. Zero runtime dependencies across JVM, Scala.js, and Scala Native.
---

## What it is

`io.github.edadma.wasm` is a Scala 3 WebAssembly interpreter, a WASI Preview 1 host shim, and a small CLI runner — three libraries sharing one codebase, all cross-built on JVM, Scala.js, and Scala Native.

```scala
import io.github.edadma.wasm.*
import io.github.edadma.wasm.wasi.{Wasi, WasiContext, HostPreopen}

val bytes: Array[Byte] = /* a .wasm binary */
val ctx = WasiContext.default.copy(
  preopens = Seq(HostPreopen.fromDir("/var/data", "/data")),
)

Runtime.instantiate(bytes, Seq(EnvModule.default, Wasi.preview1(ctx))) match
  case Right(inst) => Wasi.run(inst, "_start")  // → Right(exitCode) or Left(WasmError)
  case Left(err)   => System.err.println(err)
```

Three rustc-built `wasm32-wasip1` fixtures are committed and pass in CI — a `println!`, a `std::fs::read_to_string`, and a `std::fs::write` — so "runs real rust binaries" isn't an aspiration, it's covered by the test suite. The full [sysl](https://github.com/edadma/trisc) standard-library test suite (973 cases) also runs end-to-end on this interpreter as sysl's `wasm32-WASI` backend — large mixed workload, zero divergence from the reference run on wasmtime.

## Why this one?

- **Zero runtime dependencies.** `interp` uses only the Scala stdlib. `wasi` depends only on `interp`. Both are publishable to Maven Central without dragging in a third-party transitive surface.
- **One codebase, three platforms.** JVM, Scala.js, and Scala Native all share the same `shared/` interpreter and WASI shim. Platform-specific code is limited to the `HostPreopen.fromDir` implementation (`java.nio.file` on JVM/Native, `fs.*Sync` on JS).
- **Deterministic numerics.** Every `i32` / `i64` / `f32` / `f64` opcode produces bit-identical results across all three platforms, including IEEE-754 edge cases.
- **Validation up front.** Every imported module runs through a separate validator before any code executes. Bad binaries fail at `Runtime.instantiate` with a `function <N>: byte offset 0x<hex>: <details>` error, not at run time.
- **WASI sandboxed by default.** `HostPreopen.fromDir` rejects absolute paths, NUL bytes, and `..`-escape attempts; programs see preopens through the same `WasiContext.Preopen` trait whether they're backed by a real directory, an in-memory map, or a name-only stub.

## Cross-platform

| Target                              | Status |
|-------------------------------------|--------|
| JVM (Scala 3.8.3, Java 17+)         | ✓      |
| Scala.js 1.21.0 (Node 20+)          | ✓      |
| Scala Native 0.5.11                 | ✓      |

**532 tests** on the JVM (366 interpreter + 157 WASI + 9 CLI), all green; the interpreter and WASI test suites also pass on Scala.js and Scala Native.

## Try it

```bash
git clone https://github.com/edadma/wasm
cd wasm
sbt 'cliJVM/run examples/hello.wasm'
# Hello, world!
```

Or with a real rustc-built WASI binary against a host directory:

```bash
mkdir -p /tmp/sandbox
echo "Hello from the host filesystem" > /tmp/sandbox/hello.txt

sbt 'cliJVM/run --preopen /tmp/sandbox:/sandbox \
                wasi/shared/src/test/resources/fixtures/real_rust_fileread.wasm'
# Hello from the host filesystem
```

## Where to go next

- [Getting Started](/getting-started/) — install the artifacts, run your first module.
- [Concepts](/concepts/) — the validator, host imports, traps and errors.
- [WASI](/wasi/) — the 24 syscalls implemented and the three preopen flavours.
- [CLI](/cli/) — `--preopen`, `--invoke`, `--args`, and the dispatch rules.
- [Reference](/reference/) — supported opcodes, binary sections, error variants.
