---
title: Architecture
summary: Three sub-projects (`interp`, `wasi`, `cli`) so the published libraries stay dependency-free, with platform shims confined to thin per-target source roots.
weight: 10
---

## Three sub-projects

The repo splits into three sub-projects so the published libraries stay dependency-free:

| Sub-project | Published as            | Depends on        | External runtime deps |
|-------------|-------------------------|-------------------|-----------------------|
| `interp/`   | `wasm`                  | Scala stdlib only | None                  |
| `wasi/`     | `wasm-wasi`             | `interp`          | None                  |
| `cli/`      | *(not published)*       | `interp` + `wasi` | `scopt`               |

- **`interp/`** — the interpreter itself: parser, validator, runtime, interpreter loop, host-import surface. Cross-built on JVM / Scala.js / Scala Native; the same `shared/` sources compile for every backend.
- **`wasi/`** — the WASI Preview 1 host shim (29 syscalls). Also zero external deps. Pulls `interp` in transitively.
- **`cli/`** — a small command-line runner. Depends on `scopt` for argument parsing; not published because it's a runnable example, not a library. JVM / Scala.js (Node) / Scala Native builds are all wired up.

## Source-tree layout

```
build.sbt                              three crossProjects + Fixtures.scala generator
project/
interp/                                cross-platform interpreter library (zero deps)
  shared/src/
    main/scala/io/github/edadma/wasm/  types, parser, validator, interpreter, runtime, host
    test/scala/io/github/edadma/wasm/  category-split tests + zero-dep runner
    test/resources/fixtures/           .wat sources + wat2wasm-produced .wasm
  jvm/  js/  native/                   empty platform shells
wasi/                                  cross-platform WASI Preview 1 shim (zero deps)
  shared/src/main/scala/.../wasi/      Wasi.preview1 + WasiContext + HostBackedPreopen
  jvm/    src/main/scala/.../wasi/     HostPreopen.fromDir   (java.nio.file + FileChannel)
  native/ src/main/scala/.../wasi/     HostPreopen.fromDir   (same surface as JVM)
  js/     src/main/scala/.../wasi/     HostPreopen.fromDir   (Node fs.*Sync)
  shared/src/test/                     WasiFd / WasiArgs / WasiClock / WasiFs / WasiHostFs / WasiRealRust tests
cli/                                   cross-platform CLI (depends on interp + wasi + scopt)
  shared/src/main/scala/.../cli/       Cli.scala (scopt config + dispatch)
  jvm/    src/main/scala/.../cli/      Main.scala (java.nio file read + HostPreopen plumbing)
  js/     src/main/scala/.../cli/      Main.scala (Node fs + process.argv + HostPreopen plumbing)
  native/ src/main/scala/.../cli/      Main.scala (java.nio + HostPreopen plumbing)
examples/
  hello.wat / hello.wasm               canonical Hello, world! module
```

## How fixtures get into the test suite

`Fixtures.scala` is a generated Scala object exposing each committed `.wasm` binary as an `Array[Byte]`. It's regenerated into each platform's `target/.../src_managed/test/` on every compile by a `sourceGenerator` in `build.sbt`, so it always derives from the committed binaries — no committed copy to drift.

Fixtures are base64-encoded string constants decoded once at class init. The previous `Array[Byte](b0, b1, ...)` literal form trips the JVM 64KB method-size limit on real-world fixtures like rustc-built wasi binaries; the base64 form sidesteps it.

To refresh a fixture from its `.wat` source, see [Testing → Regenerating fixtures](/development/testing/#regenerating-fixtures).
