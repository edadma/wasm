# wasm

A WebAssembly interpreter for Scala 3 with a WASI Preview 1 host shim and a small CLI. All three pieces are cross-platform (JVM, Scala.js, Scala Native) and share one codebase.

![Maven Central](https://img.shields.io/maven-central/v/io.github.edadma/wasm_sjs1_3)
[![Last Commit](https://img.shields.io/github/last-commit/edadma/wasm)](https://github.com/edadma/wasm/commits)
![GitHub](https://img.shields.io/github/license/edadma/wasm)
![Scala Version](https://img.shields.io/badge/Scala-3.8.3-blue.svg)
![ScalaJS Version](https://img.shields.io/badge/Scala.js-1.21.0-blue.svg)
![Scala Native Version](https://img.shields.io/badge/Scala_Native-0.5.11-blue.svg)

## Documentation

Full reference at **<https://edadma.github.io/wasm/>** — installation, quickstart, supported opcodes, WASI surface, CLI flags, host imports, spec compliance, and contributor docs.

## Install

```scala
libraryDependencies ++= Seq(
  "io.github.edadma" %%% "wasm"      % "0.3.0",
  "io.github.edadma" %%% "wasm-wasi" % "0.3.0",  // optional — only if you want the WASI shim
)
```

## Try it

```bash
git clone https://github.com/edadma/wasm
cd wasm
sbt 'cliJVM/run examples/hello.wasm'
# Hello, world!
```

Or against a real rustc-built WASI binary with a host-backed preopen:

```bash
mkdir -p /tmp/sandbox
echo "Hello from the host filesystem" > /tmp/sandbox/hello.txt

sbt 'cliJVM/run --preopen /tmp/sandbox:/sandbox \
                wasi/shared/src/test/resources/fixtures/real_rust_fileread.wasm'
# Hello from the host filesystem
```

## Sub-projects

- **`interp/`** — the interpreter. Zero external dependencies (Scala stdlib only). Published as `wasm`.
- **`wasi/`** — WASI Preview 1 host shim (29 syscalls). Zero external deps, depends on `interp`. Published as `wasm-wasi`.
- **`cli/`** — a small command-line runner. Not published; runnable example.

See [Development → Architecture](https://edadma.github.io/wasm/development/architecture/) for the full source-tree layout, and [Development → Testing](https://edadma.github.io/wasm/development/testing/) for the unit suites, W3C testsuite runner, and fixture regeneration.

## Status

**881 tests** on JVM (653 interp + 209 wasi + 19 cli), all green; interp + wasi also green on Scala.js (Node 20+) and Scala Native (0.5.11). W3C testsuite slice: **133 of 142 manifests fully green**, 9 pinned in `KnownFailures` with documented residuals. See [docs/spec-compliance](https://edadma.github.io/wasm/reference/spec-compliance/) for details.

## License

ISC — see [LICENSE](LICENSE).
