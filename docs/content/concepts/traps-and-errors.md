---
title: Traps and errors
summary: The `WasmError` ADT, what causes each variant, and how runtime traps surface.
weight: 30
---

Every public API in `interp` and `wasi` returns `Either[WasmError, T]`. No thrown exceptions cross the boundary. The ADT is small and exhaustive:

```scala
sealed trait WasmError

object WasmError:
  case object InvalidMagic                                          extends WasmError
  case object TypeMismatch                                          extends WasmError
  case object UnreachableExecuted                                   extends WasmError
  case object MemoryOutOfBounds                                     extends WasmError
  final case class UnknownOpcode(byte: Int)                         extends WasmError
  final case class UnknownImport(module: String, name: String)      extends WasmError
  final case class ExportNotFound(name: String)                     extends WasmError
  final case class InvalidModule(message: String)                   extends WasmError
```

Eight variants total. The first four are simple value objects with no payload (the variant *is* the diagnostic); the next four carry a payload that says *where* or *what*.

## When each variant fires

| Variant | Origin | Typical cause |
|---|---|---|
| `InvalidMagic`         | parser  | First four bytes aren't `\0asm`. |
| `TypeMismatch`         | runtime | A typed slot received a `Value` of the wrong tag — almost always an interpreter or host-binding bug, not a guest bug. |
| `UnreachableExecuted`  | runtime | Guest executed an `unreachable` opcode. Often rustc's panic landing pad. |
| `MemoryOutOfBounds`    | runtime | Load, store, `memory.copy`, `memory.fill`, or `memory.init` accessed past the end of linear memory. |
| `UnknownOpcode(b)`     | parser  | The byte stream contained an opcode the interpreter doesn't recognise. |
| `UnknownImport(m, n)`  | instantiate | The module imports `m.n` but no `HostModule` provides it. |
| `ExportNotFound(name)` | runtime | `inst.invoke(name, …)` or `Wasi.run(inst, name)` looked up an export the module doesn't have. |
| `InvalidModule(msg)`   | validator + runtime traps | See below. |

## InvalidModule is a dual-purpose variant

`InvalidModule(message)` carries an ad-hoc string and shows up in two situations:

- **Validator failures.** Any abstract-type-check failure during the validator's pre-instantiate pass. The message is prefixed `function <N>: byte offset 0x<hex>: <details>` so it lines up with `wasm-objdump -d` output.
- **Runtime traps that aren't memory bounds.** Divide-by-zero, integer overflow on signed `div_s` / `rem_s`, `i32.trunc_f32_s` of NaN or out-of-range, `call_indirect` signature mismatch, undefined table element, `br_table` index past the table length.

The message names the operation that trapped. You don't have to pattern-match it to recover, but you can grep it.

## Worked example

```scala
import io.github.edadma.wasm.*
import io.github.edadma.wasm.WasmError.*

Runtime.instantiate(bytes, Seq(EnvModule.default)) match
  case Right(inst) =>
    inst.invoke("divide", Seq(I32(10), I32(0))) match
      case Right(Seq(I32(v))) => println(s"= $v")
      case Right(other)       => println(s"unexpected: $other")
      case Left(InvalidModule(msg))        => println(s"trap: $msg")
      case Left(MemoryOutOfBounds)         => println("memory bounds")
      case Left(UnreachableExecuted)       => println("unreachable")
      case Left(ExportNotFound(name))      => println(s"no export: $name")
      case Left(err)                       => println(s"other: $err")
  case Left(InvalidMagic)                  => println("not a wasm binary")
  case Left(UnknownImport(m, n))           => println(s"missing import: $m.$n")
  case Left(InvalidModule(msg))            => println(s"validator: $msg")
  case Left(err)                           => println(s"other: $err")
```

`inst.invoke("divide", Seq(I32(10), I32(0)))` returns `Left(InvalidModule("i32.div_s: divide by zero"))`. The `Right`-side `Seq[Value]` is whatever the export's signature says — a 0-result export returns `Right(Seq.empty)`.

## WASI exit codes

`Wasi.run(inst, "_start")` adds one shape on top of the `Either`:

- `Right(0)` — guest's `_start` returned normally, no `proc_exit` call.
- `Right(N)` — guest called `proc_exit(N)`. The host treats this as a clean exit, not a trap; whatever `N` is, it surfaces as `Right(N)`.
- `Left(err)` — anything that was a trap during execution of `_start`.

So `Wasi.run` lets a guest distinguish "I crashed" (Left) from "I exited cleanly with status N" (Right(N)) without you having to special-case `proc_exit`-the-syscall in your own host shim.
