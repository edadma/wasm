# wasm

A WebAssembly MVP interpreter for Scala 3 — JVM, Scala.js, and Scala Native — with **zero external dependencies**. The whole thing is one sbt cross-project that compiles against only the Scala standard library.

[![Last Commit](https://img.shields.io/github/last-commit/edadma/wasm)](https://github.com/edadma/wasm/commits)
![GitHub](https://img.shields.io/github/license/edadma/wasm)
![Scala Version](https://img.shields.io/badge/Scala-3.8.3-blue.svg)

## What it implements

This is a working subset — enough to run a hand-written `.wasm` module that does arithmetic, calls itself recursively, loops, and writes characters out through a host import. Not enough to run anything from the wild.

**Numeric (i32 only):** `i32.const`, `add`, `sub`, `mul`, `div_s`, `rem_s`, `and`, `or`, `xor`, `shl`, `shr_s`, `eq`, `ne`, `lt_s`, `gt_s`, `le_s`, `ge_s`, `eqz`.
**Variables:** `local.get`, `local.set`, `local.tee`.
**Control flow:** `block`, `loop`, `if`/`else`/`end`, `br`, `br_if`, `return`, `unreachable`, `nop`.
**Functions:** direct `call` only (no `call_indirect`, no tables).
**Memory:** `i32.load`, `i32.store`, `i32.load8_s`, `i32.load8_u`, `i32.store8`. One memory, no `memory.grow`.
**Stack:** `drop`, `select`.

Binary sections recognised: Type, Import, Function, Memory, Export, Code, Data. Everything else is skipped silently. LEB128 (signed and unsigned) is implemented from scratch.

Explicitly left out (and marked with `TODO` comments at the relevant dispatch points): `i64`, `f32`, `f64`, `call_indirect`, tables, multiple memories, WASI, multi-value, reference types, SIMD, exceptions.

## API

```scala
import io.github.edadma.wasm.*

val bytes: Array[Byte] = /* a .wasm binary */

// Parse + link in one step. All public APIs return Either[WasmError, T] — no exceptions.
val instance: ModuleInstance =
  Runtime.instantiate(bytes, Seq(EnvModule.default)) match
    case Right(m) => m
    case Left(e)  => sys.error(s"failed to instantiate: $e")

instance.invoke("some_export", Seq(I32(42))) match
  case Right(Seq(I32(v))) => println(s"got $v")
  case Right(_)           => println("no result")
  case Left(e)            => println(s"runtime error: $e")
```

### Host imports

```scala
trait HostModule:
  def name: String
  def functions: Map[String, HostFunc]

type HostFunc = (Memory, Seq[Value]) => Seq[Value]
```

`EnvModule.default` ships a single import — `env.putchar(i32) → ()` — that writes the low byte to `System.out`. For tests, use `EnvModule.withWriter(c => ...)` to redirect the byte stream anywhere you like (no `setOut` games).

### Errors

```scala
sealed trait WasmError
object WasmError:
  case object InvalidMagic                        extends WasmError
  case object TypeMismatch                        extends WasmError
  case object UnreachableExecuted                 extends WasmError
  case object MemoryOutOfBounds                   extends WasmError
  case class  UnknownOpcode(byte: Int)            extends WasmError
  case class  UnknownImport(module: String, name: String) extends WasmError
  case class  ExportNotFound(name: String)        extends WasmError
  case class  InvalidModule(message: String)      extends WasmError
```

## Project layout

```
build.sbt                                also defines the Fixtures.scala sourceGenerator
project/
  build.properties
  plugins.sbt
shared/                                  cross-platform library code
  src/main/scala/io/github/edadma/wasm/
    types.scala         values, types, module model, errors
    leb128.scala        ULEB128 / SLEB128 decoders
    parser.scala        binary .wasm parser
    host.scala          HostModule trait + EnvModule
    interpreter.scala   Memory, eval loop, control flow
    runtime.scala       linking, instantiation, exports
  src/test/scala/io/github/edadma/wasm/
    InterpreterTest.scala  zero-dep PASS/FAIL test runner
  src/test/resources/fixtures/
    *.wat               canonical human-readable source
    *.wasm              wat2wasm output (committed)
jvm/    js/    native/  per-platform sbt cross-project shells (no code)
```

The library code is in `shared/`; the per-platform directories exist only so sbt-crossproject's `Full` layout is satisfied. `Fixtures.scala` (a Scala object exposing each `.wasm` as an `Array[Byte]` constant) is *generated* into each platform's `target/.../src_managed/test/` by a `sourceGenerator` task in `build.sbt`, so it's always derived from the committed binaries — no committed copy that can drift.

## Running the tests

The library has no test framework — tests are a `@main`-style `object` with its own tiny PASS/FAIL runner. The same code runs on all three backends:

```bash
sbt 'wasmJVM/Test/run'
sbt 'wasmJS/Test/run'
sbt 'wasmNative/Test/run'
```

There are 33 tests covering every implemented instruction plus the obvious traps (division by zero, MIN_INT/-1, MIN_INT%-1, unreachable, memory out-of-bounds), import linking failures, and bad-magic parse errors.

## Regenerating fixtures

The `.wasm` test fixtures are produced from hand-written `.wat` files with `wat2wasm` (from [WABT](https://github.com/WebAssembly/wabt)). After editing any `.wat`:

```bash
cd shared/src/test/resources/fixtures
for f in *.wat; do wat2wasm "$f" -o "${f%.wat}.wasm"; done
```

The `.wasm` binaries are committed so anyone can run the tests without WABT installed. The `Fixtures.scala` constants are regenerated automatically on every `sbt compile` from the committed `.wasm` files.

## License

ISC — see [LICENSE](LICENSE).
