# wasm

A WebAssembly MVP interpreter for Scala 3, plus a small CLI built on top. Both are cross-platform (JVM, Scala.js, Scala Native).

[![Last Commit](https://img.shields.io/github/last-commit/edadma/wasm)](https://github.com/edadma/wasm/commits)
![GitHub](https://img.shields.io/github/license/edadma/wasm)
![Scala Version](https://img.shields.io/badge/Scala-3.8.3-blue.svg)

The repo splits into two sub-projects so the published library stays dependency-free:

- **`interp/`** — the interpreter itself. **Zero external dependencies** (Scala stdlib only). This is the artifact published to Maven Central as `wasm-interp`.
- **`cli/`** — a small command-line runner using [scopt](https://github.com/scopt/scopt) for argument parsing. Depends on `interp`; not published.

## What the interpreter implements

A working subset — enough to run a hand-written `.wasm` module that does arithmetic, calls itself recursively, loops, and writes characters out through a host import. Not enough to run anything from the wild.

**Numeric (i32 only):** `i32.const`, `add`, `sub`, `mul`, `div_s`, `rem_s`, `and`, `or`, `xor`, `shl`, `shr_s`, `eq`, `ne`, `lt_s`, `gt_s`, `le_s`, `ge_s`, `eqz`.
**Variables:** `local.get`, `local.set`, `local.tee`.
**Control flow:** `block`, `loop`, `if`/`else`/`end`, `br`, `br_if`, `return`, `unreachable`, `nop`.
**Functions:** direct `call` only (no `call_indirect`, no tables).
**Memory:** `i32.load`, `i32.store`, `i32.load8_s`, `i32.load8_u`, `i32.store8`. One memory, no `memory.grow`.
**Stack:** `drop`, `select`.

Binary sections recognised: Type, Import, Function, Memory, Export, Code, Data. Everything else is skipped silently. LEB128 (signed and unsigned) is implemented from scratch.

Explicitly left out (and marked with `TODO` comments at the relevant dispatch points): `i64`, `f32`, `f64`, `call_indirect`, tables, multiple memories, WASI, multi-value, reference types, SIMD, exceptions.

## Library API (`interp/`)

```scala
import io.github.edadma.wasm.*

val bytes: Array[Byte] = /* a .wasm binary */

// Parse + link in one step. All public APIs return Either[WasmError, T] — no exceptions.
Runtime.instantiate(bytes, Seq(EnvModule.default)) match
  case Right(inst) =>
    inst.invoke("some_export", Seq(I32(42))) match
      case Right(Seq(I32(v))) => println(s"got $v")
      case Right(_)           => println("no result")
      case Left(e)            => println(s"runtime error: $e")
  case Left(e) => sys.error(s"failed to instantiate: $e")
```

### Host imports

```scala
trait HostModule:
  def name: String
  def functions: Map[String, HostFunc]

type HostFunc = (Memory, Seq[Value]) => Seq[Value]
```

`EnvModule.default` ships a single import — `env.putchar(i32) → ()` — that writes the low byte to `System.out`. For tests, use `EnvModule.withWriter(c => ...)` to redirect the byte stream anywhere you like.

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

## Using the CLI (`cli/`)

```text
$ sbt 'cliJVM/run --help'
wasm 0.0.1
Usage: wasm [options] <file>

  <file>                 path to a .wasm module
  -i, --invoke <export>  name of the export to invoke (default: main)
  -a, --args n1,n2,...   comma-separated decimal i32 arguments to the export
  --list-exports         print exported function names and exit (no invocation)
  --help                 print this help message
  --version              print version and exit
```

```bash
sbt 'cliJVM/run examples/hello.wasm'
# Hello, world!

sbt 'cliJVM/run -i fact -a 5 examples/factorial.wasm'
# 120
```

### Native CLI

```bash
sbt 'cliNative/run examples/hello.wasm'
```

Or build a standalone native binary:

```bash
sbt cliNative/nativeLink
./cli/native/target/scala-3.8.3/wasm-cli-out examples/hello.wasm
```

### Scala.js CLI (Node.js)

sbt's command-line parser doesn't forward positional args to scalajs's `run` task, so the JS CLI runs through Node directly:

```bash
sbt cliJS/fastLinkJS
node cli/js/target/scala-3.8.3/wasm-cli-fastopt/main.js examples/hello.wasm
```

## Project layout

```
build.sbt                              two crossProjects + Fixtures.scala generator
project/
interp/                                cross-platform library (zero deps)
  shared/src/
    main/scala/io/github/edadma/wasm/  types, parser, interpreter, runtime, host
    test/scala/io/github/edadma/wasm/  InterpreterTest.scala
    test/resources/fixtures/           .wat sources + wat2wasm-produced .wasm
  jvm/  js/  native/                   empty platform shells
cli/                                   cross-platform CLI (depends on interp + scopt)
  shared/src/main/scala/.../cli/       Cli.scala (scopt config + dispatch)
  jvm/    src/main/scala/.../cli/      Main.scala (java.nio file read)
  js/     src/main/scala/.../cli/      Main.scala (Node fs + process.argv)
  native/ src/main/scala/.../cli/      Main.scala (java.nio file read)
examples/
  hello.wat / hello.wasm               canonical Hello, world! module
```

`Fixtures.scala` (a Scala object exposing each `.wasm` as an `Array[Byte]` constant) is *generated* into each platform's `target/.../src_managed/test/` by a `sourceGenerator` task in `build.sbt`, so it's always derived from the committed binaries — no committed copy that can drift.

## Running the tests

The interpreter has no test framework — tests are a `@main`-style object with its own tiny PASS/FAIL runner. The same code runs on all three backends:

```bash
sbt 'interpJVM/Test/run'
sbt 'interpJS/Test/run'
sbt 'interpNative/Test/run'
```

There are 38 tests covering every implemented instruction plus the obvious edge cases (wrap-around arithmetic, division traps, MIN_INT%-1, shift count mod 32, sign-extension on `load8_s`, store8 truncation, `if` without `else`, branching back to a `loop` label, `br N` with N > 0, memory load + store OOB, plus the parse/link error variants).

## Regenerating fixtures

The `.wasm` test fixtures are produced from hand-written `.wat` files with `wat2wasm` (from [WABT](https://github.com/WebAssembly/wabt)). After editing any `.wat`:

```bash
cd interp/shared/src/test/resources/fixtures
for f in *.wat; do wat2wasm "$f" -o "${f%.wat}.wasm"; done
```

The `.wasm` binaries are committed so anyone can run the tests without WABT installed. The `Fixtures.scala` constants are regenerated automatically on every `sbt compile` from the committed `.wasm` files.

## License

ISC — see [LICENSE](LICENSE).
