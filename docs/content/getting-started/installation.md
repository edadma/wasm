---
title: Installation
summary: Add the library as an sbt dependency once it lands on Maven Central, or build from source today.
weight: 10
---

## Status

The `interp` and `wasi` libraries are designed to publish to Maven Central as `io.github.edadma:wasm` and `io.github.edadma:wasm-wasi`, but no release has been cut yet — the project is still in pre-release while SIMD (Phase 8.E) is on the menu. Bulk-memory remainder, non-trapping float-to-int, reference types (including typed `select t*`), multi-memory (with the `HostFuncMulti` surface for hosts that need memidx > 0), and SIMD foundations + loads/stores + lane access + integer arithmetic (`V128` value type, `v128.const`, `v128.load{,8x8_s,8x8_u,16x4_s,16x4_u,32x2_s,32x2_u,8_splat,16_splat,32_splat,64_splat,32_zero,64_zero}`, `v128.store`, `*.splat` / `*.extract_lane` / `*.replace_lane` for every lane shape, `i8x16.shuffle`, `i8x16.swizzle`, plus per-shape `add` / `sub` / `neg` / `abs` / `mul` and saturating `add_sat_s,u` / `sub_sat_s,u` / `avgr_u` for i8x16 and i16x8) are all shipped. The from-source path below is the supported way to use it today.

## From source

```bash
git clone https://github.com/edadma/wasm.git
cd wasm
sbt test                                              # whole tree, all backends
sbt 'interpJVM/Test/run'                              # 419 interpreter tests, JVM
sbt 'wasiJVM/Test/run'                                # 157 WASI tests, JVM
sbt 'cliJVM/Test/run'                                 # 9 CLI tests
```

The aggregate `sbt test` runs the interpreter and WASI suites on JVM, Scala.js (Node 20+), and Scala Native, plus the CLI suite on the JVM. **585 tests** total on JVM; the interpreter and WASI also pass on JS and Native.

## Linking against a local checkout

Until Maven Central is wired up, the way to depend on `wasm` from another sbt project is a local checkout plus `dependsOn` in your own `build.sbt`, e.g.:

```scala
lazy val wasm = ProjectRef(file("../wasm"), "interpJVM")
lazy val wasi = ProjectRef(file("../wasm"), "wasiJVM")

lazy val myProject = project
  .dependsOn(wasm, wasi)
```

The same trick works for `cliJVM`, `interpJS`, `interpNative`, etc.

## Cross-build matrix

| Target          | Version           | Runtime requirement |
|-----------------|-------------------|---------------------|
| Scala           | 3.8.3             | —                   |
| JVM             | —                 | Java 17 or newer    |
| Scala.js        | 1.21.0            | Node.js 20+         |
| Scala Native    | 0.5.11            | Clang               |

## Verifying the install

Drop this in any `@main` source file inside the cloned repo, run with `sbt 'interpJVM/run'`, and you should see `120`:

```scala
import io.github.edadma.wasm.*

@main def smoke(): Unit =
  // The committed `fact.wasm` fixture is a factorial export — fact(5) = 120.
  val bytes = java.nio.file.Files.readAllBytes(
    java.nio.file.Paths.get("interp/shared/src/test/resources/fixtures/fact.wasm")
  )
  Runtime.instantiate(bytes, Seq(EnvModule.default)) match
    case Right(inst) =>
      inst.invoke("fact", Seq(I32(5))) match
        case Right(Seq(I32(v))) => println(v)
        case Right(_)           => println("no result")
        case Left(err)          => println(s"trap: $err")
    case Left(err) => println(s"instantiate failed: $err")
```

If you see `120`, you're done. Move on to the [Quickstart](/getting-started/quickstart/) for a tour of the API surface most callers actually use.
