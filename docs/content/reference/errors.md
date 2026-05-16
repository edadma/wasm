---
title: Errors
summary: The `WasmError` ADT in one table — every variant, what causes it, where it surfaces.
weight: 20
---

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
  final case class UncaughtException(tagIdx: Int, args: Seq[Value]) extends WasmError
```

## Lookup

| Variant                  | Origin             | Trigger                                               |
|--------------------------|--------------------|-------------------------------------------------------|
| `InvalidMagic`           | parser             | First 4 bytes aren't `\0asm`.                          |
| `TypeMismatch`           | runtime            | A typed slot received a `Value` of the wrong tag — almost always an interpreter or host-binding bug. |
| `UnreachableExecuted`    | runtime            | Guest executed an `unreachable` opcode.                |
| `MemoryOutOfBounds`      | runtime            | Load/store/`memory.copy`/`memory.fill`/`memory.init` accessed past linear memory's end. |
| `UnknownOpcode(b)`       | parser             | Byte stream contained an opcode this interpreter doesn't recognise. |
| `UnknownImport(m, n)`    | instantiate        | Module imports `m.n`, but no `HostModule` provides it. |
| `ExportNotFound(name)`   | runtime            | `inst.invoke(name, …)` or `Wasi.run(inst, name)` looked up a non-existent export. |
| `InvalidModule(message)` | validator + runtime traps | Static type-check failure, **or** a runtime trap that isn't a memory bound — divide-by-zero, signed-`div` overflow, `trunc` of NaN, `call_indirect` signature mismatch, branch index out of range, etc. The message names the operation. |
| `UncaughtException(tag, args)` | runtime | A `throw` propagated past the outermost call without a matching `catch tagidx` / `catch_all`. `tag` is the unified tagidx (imports first, then defs); `args` is the payload, top-of-stack at throw site = last element. |

## InvalidModule prefixes

Validator-stage messages always start with the function index and byte offset:

```text
function 7: byte offset 0x1a3: i32.add expected i32 i32 on stack, found i32 f64
function 7: byte offset 0x205: br target depth 3 out of range (max 2)
```

Runtime-trap messages don't include byte offsets — they name the trapping operation:

```text
i32.div_s: divide by zero
i32.trunc_f32_s: value out of range
call_indirect: signature mismatch
call_indirect: undefined table element
br_table: index out of range
```

You can pattern-match on the prefix if you need to distinguish them programmatically — but the standard recovery is `case Left(InvalidModule(msg)) => log(msg)` without sub-parsing.

## Pattern-match template

```scala
import io.github.edadma.wasm.WasmError.*

result match
  case Right(value) =>
    handle(value)

  // -- failure to even get to running guest code --
  case Left(InvalidMagic)               => println("not a wasm binary")
  case Left(UnknownOpcode(b))           => println(f"parser: unknown opcode 0x$b%02x")
  case Left(UnknownImport(m, n))        => println(s"missing import: $m.$n")
  case Left(InvalidModule(msg))         => println(s"module rejected: $msg")

  // -- failure during guest execution --
  case Left(UnreachableExecuted)        => println("guest hit unreachable")
  case Left(MemoryOutOfBounds)          => println("guest read/wrote out of bounds")
  case Left(ExportNotFound(name))       => println(s"no such export: $name")
  case Left(UncaughtException(t, args)) => println(s"uncaught wasm exception: tag=$t payload=$args")

  // -- host or interpreter bug --
  case Left(TypeMismatch)                => println("internal type tag mismatch (bug)")
```

The first group (parse / instantiate failures) surfaces only from `Runtime.instantiate`. The second group surfaces from `inst.invoke(...)` or `Wasi.run(...)`. The third group can surface anywhere and indicates something is wrong with the interpreter or host-import wiring, not the guest.

## WASI errnos vs. WasmError

WASI syscalls return their own errno space (`Wasi.ENOENT = 44`, `Wasi.EBADF = 8`, `Wasi.ENOTCAPABLE = 76`, …) inside the wasm-level return value of the syscall. Those are *not* `WasmError` values — they're `Int`s the guest reads. A WASI program that tries to open `/notfound` gets `EBADF`-or-`ENOENT` *from the syscall*, and the host shim returns `Right(...)` to the interpreter; the wasm execution continues normally. `Left(WasmError)` is reserved for cases where the host can't make further progress at all.
