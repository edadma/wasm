---
title: Installation
summary: Add `io.github.edadma:wasm:0.1.1` (and optionally `wasm-wasi`) to your sbt build, or work from a local checkout for development.
weight: 10
---

## Maven Central

Released artifacts (Scala 3, cross-built on JVM / Scala.js / Scala Native):

```scala
libraryDependencies ++= Seq(
  "io.github.edadma" %%% "wasm"      % "0.1.1",
  "io.github.edadma" %%% "wasm-wasi" % "0.1.1",  // optional — only if you want the WASI shim
)
```

`%%%` (triple `%`) is the [sbt-crossproject](https://github.com/portable-scala/sbt-crossproject) form that picks the right artifact for each platform — drop one `%` if you're on a non-cross build. The CLI (`wasm-cli`) is built from this repo but is not published; it's a runnable example, not a library.

| Coordinate                                | What you get                                              |
|-------------------------------------------|-----------------------------------------------------------|
| `io.github.edadma:wasm:0.1.1`             | The interpreter — `Runtime.instantiate`, `ModuleInstance`. |
| `io.github.edadma:wasm-wasi:0.1.1`        | The WASI Preview 1 host shim — depends on `wasm`.         |

The `wasm` artifact has zero external runtime dependencies; the `wasm-wasi` artifact depends only on `wasm`.

## From source

If you want to build the repo locally — to run the test suite, hack on the interpreter, or use the CLI runner — clone and run:

```bash
git clone https://github.com/edadma/wasm.git
cd wasm
sbt test                                              # whole tree, all backends
sbt 'interpJVM/Test/run'                              # 521 interpreter tests, JVM
sbt 'wasiJVM/Test/run'                                # 157 WASI tests, JVM
sbt 'cliJVM/Test/run'                                 # 9 CLI tests
```

The aggregate `sbt test` runs the interpreter and WASI suites on JVM, Scala.js (Node 20+), and Scala Native, plus the CLI suite on the JVM. **687 tests** total on JVM; the interpreter and WASI also pass on JS and Native.

## Linking against a local checkout

If you're hacking on the interpreter and want a downstream project to pick up your changes without a `publishSigned` round-trip, point the downstream sbt build at the local source tree:

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
