---
title: ModuleInstance
summary: What `Runtime.instantiate` returns and the public surface for invoking exports, reading globals, and touching linear memory.
weight: 5
---

`Runtime.instantiate(bytes, hostModules)` returns `Either[WasmError, ModuleInstance]`. The `Right` side is a `ModuleInstance` — a single, fully-resolved module: imports bound to host functions, data + element segments copied into their target memories / tables, the start function (if any) already invoked. Everything you can do with the module after that goes through this one object.

This page covers the full public surface — what you can read, what you can call, and how host code talks to wasm memory.

## The surface

```scala
final class ModuleInstance:
  /** All linear memories the module declared, indexed by memidx.
    * Always at least length 1 (zero-memory modules get a synthetic placeholder
    * at index 0). Multi-memory modules carry one entry per declared memory. */
  val memories: Array[Memory]

  /** Convenience accessor for `memories(0)`. Most modules have exactly one
    * memory; this stays the friendly name. */
  val memory: Memory

  /** Invoke an exported function. Returns `Right(results)` on a normal
    * return, `Left(WasmError)` on any trap or signature mismatch. */
  def invoke(name: String, args: Seq[Value] = Seq.empty): Either[WasmError, Seq[Value]]

  /** Total number of functions the module owns (imported + defined). */
  def functionCount: Int

  /** Sorted list of every exported function name. */
  def exportedFunctionNames: Seq[String]

  /** Read an exported global by name. Returns the current value
    * (mutable globals reflect prior `global.set` writes). */
  def globalValue(name: String): Either[WasmError, Value]

  /** Resolve an exported memory by name. For single-memory modules
    * that export memory 0, this is equivalent to `Right(memory)`. */
  def exportedMemory(name: String): Either[WasmError, Memory]
```

Everything else on the class is `private[wasm]` and exists for the interpreter's eval loop.

## Invoking exports

`invoke` is the workhorse. Arguments are wrapped `Value`s (`I32`, `I64`, `F32`, `F64`, `V128`, `RefFunc`, `RefExtern`, `RefNull`); results come back as a `Seq[Value]` in the function's declared order:

```scala
inst.invoke("add", Seq(I32(2), I32(3))) match
  case Right(Seq(I32(sum)))     => println(s"add = $sum")
  case Right(other)             => println(s"unexpected shape: $other")
  case Left(WasmError.ExportNotFound(n)) => println(s"no export named '$n'")
  case Left(err)                => println(s"trap or signature mismatch: $err")
```

Failure modes:

- **`ExportNotFound(name)`** — the export doesn't exist (or names a non-function export).
- **`TypeMismatch`** — the args don't match the function's declared signature.
- **Any other `WasmError`** — a runtime trap inside the function (out-of-bounds memory, division by zero, indirect call type mismatch, etc.). See [Traps and errors](/concepts/traps-and-errors/).

`exportedFunctionNames` gives you the full export list, sorted, when you don't know what's available up front:

```scala
inst.exportedFunctionNames.foreach(println)
```

This is what `wasm --list-exports` uses internally.

## Reading globals

Mutable globals are live state — `invoke` calls can modify them, and `globalValue` reads them at whatever value they happen to hold right now:

```scala
inst.globalValue("counter") match
  case Right(I32(n)) => println(s"counter = $n")
  case Right(other)  => println(s"unexpected type: $other")
  case Left(err)     => println(s"no such global: $err")

inst.invoke("bump")               // module mutates `counter`
inst.globalValue("counter")       // new value visible here
```

Only globals listed in the module's export section are reachable through `globalValue`. Module-internal globals stay private.

## Reading + writing memory

`memory` exposes the underlying `Memory` for memory 0. Multi-memory modules can reach the rest via `memories(idx)` or `exportedMemory(name)`:

```scala
final class Memory:
  /** The full byte buffer. Mutable — host code may read or write directly.
    * Resized in place when wasm code runs `memory.grow`. */
  var data: Array[Byte]

  /** Bytes currently allocated (= currentPages * 65536). */
  def size: Int

  /** Current and maximum page counts. A page is 64 KiB. */
  var currentPages: Int
  val maxPages: Option[Int]

  /** Grow by `delta` 64-KiB pages. Returns the previous page count on
    * success, or -1 if the grow would exceed `maxPages` or the platform
    * cap. `delta == 0` is a permitted no-op. */
  def grow(delta: Int): Int
```

Memory is byte-addressable and little-endian, matching the WebAssembly spec. Host code can read or write `data` directly — the interpreter holds no separate "live view" of memory; what you see is what wasm sees:

```scala
// Plant a UTF-8 string at offset 0
val msg = "Hello, host!\n".getBytes("UTF-8")
System.arraycopy(msg, 0, inst.memory.data, 0, msg.length)

// Now wasm can read from address 0 with i32.load8_u + a length argument
inst.invoke("print_at", Seq(I32(0), I32(msg.length)))
```

Conversely, after a module writes through `memory.store`, the host reads the same bytes:

```scala
inst.invoke("compute_into", Seq(I32(64)))     // writes 16 bytes at offset 64
val out = java.util.Arrays.copyOfRange(inst.memory.data, 64, 80)
```

`memories` (the full vector) and `exportedMemory(name)` matter only for multi-memory modules — single-memory programs can stay on `.memory` forever.

## Concurrency

A `ModuleInstance` is **not** thread-safe. `invoke` mutates the interpreter's per-instance value stack, frame stack, and memory; running two `invoke` calls against the same instance from two threads will corrupt that state. If you need concurrency, instantiate the same `bytes` multiple times — instantiation is cheap and produces independent state.

The `bytes` array passed to `Runtime.instantiate` is read-only after parsing; sharing it across multiple `instantiate` calls is safe and is the recommended pattern for "fork an isolated copy of this module per request".

## Lifecycle

There is no explicit `close` or `dispose` on `ModuleInstance`. Drop the reference and the GC reclaims it — the underlying `Memory.data` arrays are plain `Array[Byte]`, no off-heap allocation. Host functions registered through `HostModule` are also pure Scala values; they're collected when no instance references them.

## Where to go next

- [Host imports](/concepts/host-imports/) — wire your own functions into a module.
- [Traps and errors](/concepts/traps-and-errors/) — the `WasmError` model that surfaces through `invoke`.
- [Reference → Errors](/reference/errors/) — every error variant in one table.
