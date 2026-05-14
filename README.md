# wasm

A WebAssembly interpreter for Scala 3 with a WASI Preview 1 host shim and a small CLI. All three pieces are cross-platform (JVM, Scala.js, Scala Native) and share one codebase.

![Maven Central](https://img.shields.io/maven-central/v/io.github.edadma/wasm_sjs1_3)
[![Last Commit](https://img.shields.io/github/last-commit/edadma/wasm)](https://github.com/edadma/wasm/commits)
![GitHub](https://img.shields.io/github/license/edadma/wasm)
![Scala Version](https://img.shields.io/badge/Scala-3.8.3-blue.svg)
![ScalaJS Version](https://img.shields.io/badge/Scala.js-1.21.0-blue.svg)
![Scala Native Version](https://img.shields.io/badge/Scala_Native-0.5.11-blue.svg)

## Documentation

Full reference, getting-started guide, WASI surface, CLI flags, and supported-opcode list:
**https://edadma.github.io/wasm/**

## Sub-projects

The repo splits into three sub-projects so the published libraries stay dependency-free:

- **`interp/`** — the interpreter itself. **Zero external dependencies** (Scala stdlib only). Published to Maven Central as `wasm`.
- **`wasi/`** — the WASI Preview 1 host shim (24 syscalls — fd/path/args/environ/clock/random/proc_exit). Also zero external deps; depends on `interp`. Published as `wasm-wasi`.
- **`cli/`** — a command-line runner using [scopt](https://github.com/scopt/scopt). Depends on `interp` + `wasi`; not published.

## What the interpreter implements

Enough to run real `wasm32-wasip1` binaries produced by rustc end-to-end. Three rustc-built integration fixtures are committed and pass in CI: a `Hello, WASI!` program, a `std::fs::read_to_string` reader, and a `std::fs::write` writer.

**Numeric (full MVP, all four scalar types):** every `i32` / `i64` / `f32` / `f64` opcode — const, all compares (signed and unsigned for ints, ordered for floats), full arithmetic + bitwise + shift + rotate, conversion + reinterpret, IEEE-754 deterministic across JVM/JS/Native.

**Variables:** `local.get`, `local.set`, `local.tee`, `global.get`, `global.set` (mutable and immutable globals).

**Control flow:** `block`, `loop`, `if`/`else`/`end`, `br`, `br_if`, `br_table`, `return`, `call`, `call_indirect`, `unreachable`, `nop`. Multi-value blocks/loops/ifs supported (block params re-fed on `br` to a loop, multi-result `br_if` carry, etc.).

**Memory:** all `i32` / `i64` / `f32` / `f64` load/store width variants (8/16/32-bit signed and unsigned for ints; 32/64-bit for floats). `memory.size` / `memory.grow` with optional `max` limit honoured. `memory.copy` / `memory.fill` (bulk-memory subset).

**Tables + functions:** Section 4 + Section 9 funcref tables, `call_indirect` with signature check at the call site, indirect-call trap on type mismatch.

**Sign-extension proposal:** `i32.extend8_s`, `i32.extend16_s`, `i64.extend8_s`, `i64.extend16_s`, `i64.extend32_s`.

**Stack:** `drop`, `select`.

**Validation pass:** every imported module runs through a separate validator before any code executes — abstract operand-stack tracking, control-frame depth, opcode-by-opcode signature check. Bad binaries fail at `Runtime.instantiate` with `InvalidModule("function <N>: byte offset 0x<hex>: <details>")` rather than at run time.

Binary sections recognised: Type (1), Import (2), Function (3), Table (4), Memory (5), Global (6), Export (7), Start (8), Element (9), Code (10), Data (11). LEB128 (signed and unsigned, up to 64 bits) is implemented from scratch.

**Not yet implemented (Phase 8+):** bulk-memory remainder (`memory.init`, `data.drop`, `table.copy`, `table.init`, `elem.drop`); non-trapping float-to-int (`trunc_sat_*`); reference types (`externref`, `ref.is_null`, `ref.func`); SIMD; threads/atomics; exception handling; GC proposal; multi-memory; component model. Each is independently scoped and can be added without touching the others.

## WASI Preview 1

The `wasi` module implements 24 `wasi_snapshot_preview1` host functions:

| Group | Syscalls |
|---|---|
| Process | `proc_exit`, `args_get` / `args_sizes_get`, `environ_get` / `environ_sizes_get` |
| Clock + entropy | `clock_time_get` (realtime + monotonic), `random_get` |
| Stdio | `fd_write` (routes by fd to stdout/stderr/file) |
| Preopens | `fd_prestat_get`, `fd_prestat_dir_name` |
| File I/O | `path_open`, `fd_read`, `fd_seek`, `fd_close`, `fd_filestat_get`, `fd_fdstat_get`, `fd_fdstat_set_flags`, `fd_sync`, `fd_datasync` |
| Filesystem | `path_filestat_get`, `path_unlink_file`, `path_create_directory`, `fd_readdir` |

Three flavours of preopen behind the same `WasiContext.Preopen` trait:

- **`Preopen.named(name)`** — directory name advertised, but `path_open` against it returns `ENOTCAPABLE`. For programs that probe preopens without actually using them.
- **`Preopen.inMemory(name, files)`** — a sandboxed in-memory directory backed by a `Map[String, Array[Byte]]`. Real file/dir entries, real OFLAGS dispatch (`CREAT` / `EXCL` / `TRUNC` / `DIRECTORY`), real `path_unlink_file` / `path_create_directory` / `fd_readdir`. What tests use.
- **`HostPreopen.fromDir(hostPath, virtualName)`** — a sandboxed real on-disk directory. JVM and Scala Native back it with `java.nio.file` + `FileChannel`; Scala.js backs it with Node `fs.*Sync`. Path sandboxing rejects absolute paths, NUL bytes, and `..`-escape attempts.

Programs see the same wasi-preview1 surface no matter which preopen kind is in play.

## Library API (`interp/` + `wasi/`)

```scala
import io.github.edadma.wasm.*
import io.github.edadma.wasm.wasi.{Wasi, WasiContext, HostPreopen}

val bytes: Array[Byte] = /* a .wasm binary */

// Plain command-mode wasi run: clean exit returns 0, proc_exit(N) returns N,
// trap returns Left(WasmError).
val ctx = WasiContext.default.copy(
  args     = Seq("myprog", "--flag", "value"),
  envs     = Seq("HOME" -> "/root"),
  preopens = Seq(HostPreopen.fromDir("/var/data", "/data")),
)

Runtime.instantiate(bytes, Seq(EnvModule.default, Wasi.preview1(ctx))) match
  case Right(inst) =>
    Wasi.run(inst, "_start") match
      case Right(code) => println(s"exit $code")
      case Left(err)   => System.err.println(s"runtime error: $err")
  case Left(err) =>
    System.err.println(s"instantiate failed: $err")
```

For non-wasi modules, invoke an export directly:

```scala
Runtime.instantiate(bytes, Seq(EnvModule.default)) match
  case Right(inst) =>
    inst.invoke("fact", Seq(I32(5))) match
      case Right(Seq(I32(v))) => println(s"got $v")
      case Right(_)           => println("no result")
      case Left(err)          => println(s"runtime error: $err")
  case Left(err) => sys.error(s"failed to instantiate: $err")
```

All public APIs return `Either[WasmError, T]` — no thrown exceptions cross the API boundary.

### Host imports

```scala
trait HostModule:
  def name: String
  def functions: Map[String, HostFunc]

type HostFunc = (Memory, Seq[Value]) => Seq[Value]
```

`EnvModule.default` ships a single `env.putchar(i32) → ()` import that writes the low byte to `System.out`. For tests, use `EnvModule.withWriter(c => ...)` to redirect.

### Errors

```scala
sealed trait WasmError
object WasmError:
  case object InvalidMagic                                  extends WasmError
  case object TypeMismatch                                  extends WasmError
  case object UnreachableExecuted                           extends WasmError
  case object MemoryOutOfBounds                             extends WasmError
  final case class UnknownOpcode(byte: Int)                 extends WasmError
  final case class UnknownImport(module: String, name: String) extends WasmError
  final case class ExportNotFound(name: String)             extends WasmError
  final case class InvalidModule(message: String)           extends WasmError
```

Runtime traps that aren't memory bounds — divide-by-zero, integer-overflow on signed `div`, `trunc` of NaN / out-of-range float, indirect-call type mismatch, undefined table element, branch index out of range — surface as `InvalidModule(msg)` with a human-readable message naming the operation that trapped. Validator-stage failures (bad operand stack, malformed binary, type-section mismatch) use the same variant with a `function <N>: byte offset 0x<hex>: <details>` prefix.

## Using the CLI

```text
$ wasm --help
wasm 0.0.1
Usage: wasm [options] <file>

  <file>                                       path to a .wasm module
  -i, --invoke <export>                        name of the export to invoke (default: _start if exported, else main)
  -a, --args n1,n2,...                         comma-separated decimal i32 arguments to the export
  --list-exports                               print exported function names and exit (no invocation)
  -p, --preopen <host-path>:<virtual-name>     mount a host directory as a wasi preopen (repeatable)
  --help                                       print this help message
  --version                                    print version and exit
```

### Hand-written WAT example (no WASI)

```bash
sbt 'cliJVM/run examples/hello.wasm'
# Hello, world!
```

### Real WASI binary with a host-backed preopen

```bash
mkdir -p /tmp/sandbox
echo "Hello from the host filesystem" > /tmp/sandbox/hello.txt

# real_rust_fileread.wasm is a rustc-built wasm32-wasip1 binary that
# calls std::fs::read_to_string("/sandbox/hello.txt") and prints it.
sbt 'cliJVM/run --preopen /tmp/sandbox:/sandbox \
                wasi/shared/src/test/resources/fixtures/real_rust_fileread.wasm'
# Hello from the host filesystem
```

The split is on the *last* `:` in the spec, so Windows-style host paths like `C:\data:/data` still parse correctly. Multiple `--preopen` flags accumulate.

Default dispatch: if the module exports `_start`, it's treated as a WASI command-mode binary and run through `Wasi.run` (so a `proc_exit(N)` call becomes the process exit code); otherwise `main` is invoked with the supplied `--args`. `-i` / `--invoke` overrides both.

### Native CLI binary

```bash
sbt cliNative/nativeLink
./cli/native/target/scala-3.8.3/wasm-cli-out --preopen /tmp/sandbox:/sandbox real_rust_fileread.wasm
```

### Scala.js CLI (Node.js)

sbt's command-line parser doesn't forward positional args to scalajs's `run` task, so the JS CLI runs through Node directly:

```bash
sbt cliJS/fastLinkJS
node cli/js/target/scala-3.8.3/wasm-cli-fastopt/main.js \
     --preopen /tmp/sandbox:/sandbox \
     real_rust_fileread.wasm
```

## Project layout

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

`Fixtures.scala` (a generated Scala object exposing each `.wasm` as an `Array[Byte]`) is regenerated into each platform's `target/.../src_managed/test/` on every compile by a `sourceGenerator` in `build.sbt`, so it always derives from the committed binaries — no committed copy to drift. Fixtures are base64-encoded string constants decoded once at class init (the previous `Array[Byte](b0, b1, ...)` literal form trips the JVM 64KB method-size limit on real-world fixtures like rustc-built wasi binaries).

## Running the tests

The interpreter has no test framework — tests are `@main`-style objects with a hand-rolled PASS/FAIL runner. The same code runs on all three backends:

```bash
sbt 'interpJVM/Test/run'    # 247 interpreter tests
sbt 'interpJS/Test/run'
sbt 'interpNative/Test/run'

sbt 'wasiJVM/Test/run'      # 157 WASI tests (incl. host-backed preopen
sbt 'wasiJS/Test/run'       #                   on real temp dirs)
sbt 'wasiNative/Test/run'
```

Total: **404 tests** across the project, all three backends green. Three of those are end-to-end integration tests against real rustc-built `wasm32-wasip1` binaries.

## Regenerating fixtures

The `.wasm` test fixtures are produced from hand-written `.wat` files with `wat2wasm` (from [WABT](https://github.com/WebAssembly/wabt)). After editing any `.wat`:

```bash
cd interp/shared/src/test/resources/fixtures
for f in *.wat; do wat2wasm "$f" -o "${f%.wat}.wasm"; done

cd wasi/shared/src/test/resources/fixtures
for f in *.wat; do wat2wasm "$f" -o "${f%.wat}.wasm"; done
```

The committed `.wasm` binaries let anyone run the tests without WABT installed. `Fixtures.scala` / `WasiFixtures.scala` are regenerated automatically on every `sbt compile`. The three rustc-built fixtures are committed with their `.rs` source alongside as documentation.

## License

ISC — see [LICENSE](LICENSE).
