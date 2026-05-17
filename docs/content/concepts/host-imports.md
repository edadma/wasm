---
title: Host imports
summary: How guest modules reach into Scala — the `HostModule` surface for functions, globals, memories, and tables.
weight: 20
---

WebAssembly modules can declare imports of four kinds: functions, globals, memories, and tables. The `interp` library exposes all four through the `HostModule` trait — guest declarations like `(import "env" "foo" (func ...))`, `(import "env" "bar" (global ...))`, `(import "env" "mem" (memory ...))`, and `(import "env" "tab" (table ...))` all resolve against the host modules you hand to `Runtime.instantiate`.

```scala
trait HostModule:
  def name: String
  def functions:      Map[String, HostFunc]        = Map.empty
  def functionsMulti: Map[String, HostFuncMulti]   = Map.empty
  def globals:        Map[String, HostGlobal]      = Map.empty
  def memories:       Map[String, Memory]          = Map.empty
  def tables:         Map[String, RuntimeTable]    = Map.empty

type HostFunc      = (Memory,             Seq[Value]) => Seq[Value]
type HostFuncMulti = (IndexedSeq[Memory], Seq[Value]) => Seq[Value]

// HostGlobal is internally backed by a GlobalCell. Two factories:
//   - HostGlobal(vt, mut, value)        — wraps the value in a fresh cell
//   - HostGlobal.live(vt, mut, cell)    — shares an externally-owned cell

final class GlobalCell(var value: Value)
```

A `HostFunc` takes the guest's `Memory` instance (memidx 0) plus a sequence of `Value` arguments matching the import's declared signature, and returns a sequence of `Value` results matching the import's declared results. Pure functions, no `Future` / `IO` wrapping.

A `HostFuncMulti` takes the guest's full vector of memories (length ≥ 1) instead of just memidx 0 — useful only for multi-memory modules. Single-memory programs should stay on `HostFunc`; multi-memory hosts that need to inspect or write a non-zero memidx use `HostFuncMulti`. A name registered in *both* maps resolves to the multi-memory form.

A `HostGlobal` is a typed global the host exposes for guest modules to import as `(import "..." "..." (global <type>))`. The declared `valueType` and `mutable` flag must match the import declaration exactly. Internally, every `HostGlobal` is backed by a `GlobalCell` — a tiny mutable holder for one `Value`:

  - `HostGlobal(vt, mut, value)` wraps the literal in a *fresh* cell that nobody else holds a reference to. Guest `global.set` updates this private cell. Use this for immutable globals (where there's nothing to share anyway) and for mutable globals the host doesn't need to read back.
  - `HostGlobal.live(vt, mut, cell)` shares an externally-owned cell. The host keeps a reference; guest writes flow through the same storage; the host can read and write the cell directly. This is how to satisfy the wasm-3.0 spec's "imported mutable globals alias the exporter's storage" rule when forwarding one module's exports as another module's imports.

A `Memory` or `RuntimeTable` exposed via `memories` / `tables` forwards by reference — guest reads and writes hit the same backing array the host can inspect. Limits checking happens at instantiation: the host's current size must be at least the importing module's declared min, and the host's max (if any) must be at most the module's declared max (if any). Reftype must match for tables; shared-vs-unshared must match for memories.

## EnvModule.default

The interpreter ships one host module out of the box:

```scala
import io.github.edadma.wasm.*

EnvModule.default     // module name: "env", one function: "putchar"
```

`env.putchar(i32) → ()` writes the low byte of its argument to `System.out`. It's the minimal "I can print" surface — modules compiled by lightweight toolchains use it when no WASI runtime is available.

To redirect the output (tests, in-process capture, custom encoding):

```scala
val buf = new java.lang.StringBuilder
EnvModule.withWriter(c => buf.append(c.toChar))   // returns a HostModule
```

## Defining your own HostModule

```scala
import io.github.edadma.wasm.*

object Logger extends HostModule:
  def name: String = "logger"

  def functions: Map[String, HostFunc] = Map(
    "log_i32" -> { (_: Memory, args: Seq[Value]) =>
      val I32(level) +: I32(code) +: _ = args: @unchecked
      println(s"log level=$level code=$code")
      Seq.empty
    },

    "log_str" -> { (mem: Memory, args: Seq[Value]) =>
      // Convention: caller pushes (ptr: i32, len: i32) onto the stack.
      val I32(ptr) +: I32(len) +: _ = args: @unchecked
      val bytes = new Array[Byte](len)
      System.arraycopy(mem.data, ptr, bytes, 0, len)
      println(new String(bytes, "UTF-8"))
      Seq.empty
    },
  )

// Pass it alongside whatever else the module needs:
Runtime.instantiate(bytes, Seq(EnvModule.default, Logger)) match
  case Right(inst) => inst.invoke("main", Seq.empty)
  case Left(err)   => println(err)
```

The guest module's import section declares the imports by `(module, name)` pair:

```wat
(module
  (import "logger" "log_str" (func $log (param i32 i32)))
  ...
  (call $log (i32.const 100) (i32.const 13)))
```

The interpreter looks up `"logger"` in the supplied list of host modules, then `"log_str"` in that module's `functions` map. Missing module: `Left(UnknownImport("logger", "log_str"))` at instantiate time. Missing function in a found module: same error.

## Memory access from host code

`Memory.data` is a public `Array[Byte]` — host functions read and write the guest's linear memory directly through it. Bounds-checking is the host function's responsibility (the array's own `IndexOutOfBoundsException` is what fires on an out-of-range access, and the interpreter doesn't catch that on the host side). Use `mem.size` to find the current byte length; use `mem.currentPages` and `mem.maxPages` for the page-count view.

For multi-byte loads, wasm is little-endian:

```scala
def readI32(mem: Memory, addr: Int): Int =
  (mem.data(addr)     & 0xff)        |
  (mem.data(addr + 1) & 0xff) <<  8  |
  (mem.data(addr + 2) & 0xff) << 16  |
  (mem.data(addr + 3) & 0xff) << 24

def writeI32(mem: Memory, addr: Int, v: Int): Unit =
  mem.data(addr)     = (v        & 0xff).toByte
  mem.data(addr + 1) = ((v >>  8) & 0xff).toByte
  mem.data(addr + 2) = ((v >> 16) & 0xff).toByte
  mem.data(addr + 3) = ((v >> 24) & 0xff).toByte
```

The WASI shim wraps these patterns; if you find yourself writing them repeatedly, see [`wasi/Wasi.scala`](https://github.com/edadma/wasm/blob/stable/wasi/shared/src/main/scala/io/github/edadma/wasm/wasi/Wasi.scala) for production-quality versions.

## Why not a typed binding generator?

The host-import surface is deliberately a `Map[String, HostFunc]` of dynamically-typed functions rather than a typed binding generated from `.wit` or `.wasm`. Two reasons:

- **Zero external dependencies.** No code-gen step at compile time means no Scala-version constraint and no annotation-processor wiring. Drop the jar on the classpath and go.
- **Multi-platform shared code.** The same `HostModule` definition compiles for JVM, Scala.js, and Scala Native without `@JSExport` / `@ExportTopLevel` annotations — the `Value` types are platform-agnostic.

For a stronger-typed wrapper, a separate `wasm-bindgen`-style library could be layered on top without touching the `interp` core.

## Multi-memory host functions

When a guest module declares more than one linear memory (a multi-memory proposal feature; see [Opcodes → Multi-memory](/reference/opcodes/)), `HostFunc` only gets a handle to memidx 0. Register through `functionsMulti` instead to receive the whole `IndexedSeq[Memory]`:

```scala
import io.github.edadma.wasm.*

object DualBuffer extends HostModule:
  def name: String = "dual"

  override def functionsMulti: Map[String, HostFuncMulti] = Map(
    // Copies one byte from mem0 into mem1 at the same offset.
    "mirror" -> { (mems: IndexedSeq[Memory], args: Seq[Value]) =>
      val I32(addr) +: _ = args: @unchecked
      mems(1).data(addr) = mems(0).data(addr)
      Seq.empty
    },
  )
```

The vector is always length ≥ 1 — zero-memory modules get a synthetic placeholder at index 0 so `mems.head` is safe to dereference. Single-memory `HostFunc` registrations continue to work unchanged against single-memory modules; the runtime wraps them at import-resolution time so they always see `mems.head`.
