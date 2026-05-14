---
title: Quickstart
summary: Instantiate a module, invoke an export, run a WASI command-mode binary — the three things every caller does.
weight: 20
---

This walkthrough shows the three things you'll do with the library 95 % of the time: instantiate a non-WASI module and invoke a function, run a WASI command-mode binary, and customize the host environment.

## 1. Instantiate and invoke

The minimal case — a module with no WASI imports, exporting one function you'd like to call:

```scala
import io.github.edadma.wasm.*

val bytes: Array[Byte] = /* contents of a .wasm file */

Runtime.instantiate(bytes, Seq(EnvModule.default)) match
  case Right(inst) =>
    inst.invoke("fact", Seq(I32(5))) match
      case Right(Seq(I32(v))) => println(s"fact(5) = $v")
      case Right(other)       => println(s"unexpected result: $other")
      case Left(err)          => println(s"trap: $err")
  case Left(err) =>
    println(s"failed to instantiate: $err")
```

`EnvModule.default` ships a single `env.putchar(i32) → ()` import that writes the low byte to `System.out`. Modules compiled by lightweight toolchains (hand-written `.wat`, Emscripten with `-s STANDALONE_WASM=0`) often expect it; modules that don't import `env.putchar` are unaffected.

`Runtime.instantiate` validates the binary before any code runs. Bad modules return `Left(InvalidModule("function <N>: byte offset 0x<hex>: <details>"))` — see [Concepts → Validation](/concepts/validation/) for what the validator checks.

## 2. Run a WASI command-mode binary

For real `wasm32-wasip1` binaries — anything compiled by rustc, zig, or Clang's WASI sysroot — you want `Wasi.run`, which handles `_start`, command-line args, environment variables, preopens, and `proc_exit` for you:

```scala
import io.github.edadma.wasm.*
import io.github.edadma.wasm.wasi.{Wasi, WasiContext, HostPreopen}

val bytes: Array[Byte] = /* a rustc-built wasm32-wasip1 binary */

val ctx = WasiContext.default.copy(
  args     = Seq("myprog", "--flag", "value"),
  envs     = Seq("HOME" -> "/root", "LANG" -> "C.UTF-8"),
  preopens = Seq(HostPreopen.fromDir("/var/data", "/data")),
)

Runtime.instantiate(bytes, Seq(EnvModule.default, Wasi.preview1(ctx))) match
  case Right(inst) =>
    Wasi.run(inst, "_start") match
      case Right(code) => sys.exit(code)
      case Left(err)   => System.err.println(s"runtime error: $err"); sys.exit(1)
  case Left(err) =>
    System.err.println(s"instantiate failed: $err"); sys.exit(1)
```

Clean exit returns `Right(0)`. A `proc_exit(N)` call returns `Right(N)`. A trap returns `Left(WasmError)`. No thrown exceptions cross the API boundary.

## 3. Customize the host environment

`WasiContext` is a plain case class — copy-and-modify the bits you care about:

```scala
import io.github.edadma.wasm.wasi.{WasiContext, HostPreopen}
import WasiContext.Preopen

val ctx = WasiContext.default.copy(
  args     = Seq("prog", "input.txt"),
  envs     = Seq("PATH" -> "/usr/bin"),
  // Three preopen flavours, mix and match:
  preopens = Seq(
    HostPreopen.fromDir("/tmp/sandbox", "/sandbox"),                    // real host directory
    Preopen.inMemory("/cfg",                                            // in-memory map
      Map("settings.toml" -> "verbose = true\n".getBytes)),
    Preopen.named("/devices"),                                          // probe-only stub
  ),
  // Inject your own clock or randomness for deterministic tests:
  clock    = WasiContext.systemClock,                                   // or your own `Clock` impl
  random   = (n: Int) => Array.fill(n)(0x42.toByte),                    // not random at all — fine for tests
)
```

`HostPreopen.fromDir` is sandboxed by construction: absolute paths from the guest, NUL bytes, and `..` segments are all rejected at the `path_open` boundary. See [WASI → Preopens](/wasi/preopens/) for the full sandboxing model.

## Where to go next

- [Concepts → Validation](/concepts/validation/) — what the validator catches and what it doesn't.
- [Concepts → Host imports](/concepts/host-imports/) — wire your own functions into a module.
- [WASI → Syscalls](/wasi/syscalls/) — the 24 syscalls implemented and their semantics.
- [CLI](/cli/) — when you want a runner more than a library.
- [Reference → Errors](/reference/errors/) — every `WasmError` variant and what causes it.
