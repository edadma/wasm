---
title: Tracer
summary: Instrumentation hooks for opcode counts, function transitions, throws, and traps — wire one in via the optional `tracer` parameter on `invoke` / `Wasi.run`.
weight: 50
---

`Tracer` is the interpreter's instrumentation hook. Every `ModuleInstance.invoke` and `Wasi.run` takes an optional `tracer: Tracer = Tracer.NoOp` parameter; pass anything else to receive callbacks at well-defined points during the invocation.

```scala
trait Tracer:
  def onOp(op: Int):           Unit = ()   // every opcode dispatch
  def onCall(funcIdx: Int):    Unit = ()   // wasm frame pushed
  def onHostCall(funcIdx: Int): Unit = ()  // host fn dispatched (no frame)
  def onReturn(funcIdx: Int):  Unit = ()   // wasm frame popped
  def onThrow(tagIdx: Int):    Unit = ()   // exception raised
  def onTrap(err: WasmError):  Unit = ()   // non-throw runtime error
```

Default methods are no-ops. The bundled `Tracer.NoOp` extends the trait without overriding anything; the JIT inlines its empty methods, so untraced invocations pay no per-opcode cost.

## `Tracer.Counting`

The library ships a drop-in counting tracer:

```scala
import io.github.edadma.wasm.*

val tracer = Tracer.counting
inst.invoke("_start", Seq.empty, tracer) match
  case Right(_) =>
    println(s"ops:       ${tracer.ops}")
    println(s"calls:     ${tracer.calls}")      // wasm frames pushed
    println(s"hostCalls: ${tracer.hostCalls}")  // host fns dispatched
    println(s"maxDepth:  ${tracer.maxDepth}")   // peak wasm-frame depth
    println(s"throws:    ${tracer.throws}")
    println(s"traps:     ${tracer.traps}")
  case Left(err) =>
    System.err.println(s"failed: $err")
```

`maxDepth` counts wasm frames only (host calls don't push a frame, so they don't grow depth). A tail-recursive loop runs at constant depth regardless of iteration count — useful for confirming that a compiler emits proper `return_call` for recursive code.

## Custom Tracer

For finer-grained tracing — opcode histograms, branch-direction profiles, per-function timing, IR-level matching against execution — implement the trait directly:

```scala
import scala.collection.mutable.ArrayBuffer

val ops = ArrayBuffer.empty[Int]

val tracer = new Tracer:
  override def onOp(op: Int): Unit = ops += op

inst.invoke("compute", Seq(I32(42)), tracer)
println(s"opcodes executed: ${ops.size}")
println(s"distinct opcodes: ${ops.toSet}")
```

The hooks fire in execution order. `onOp` runs just before the opcode dispatches; `onCall` runs after the new frame is pushed; `onReturn` runs after the frame is popped. Tail calls fire `onReturn(caller)` followed by `onCall(callee)` — the depth net change is zero, which is what `Tracer.Counting.maxDepth` observes.

## What the tracer does NOT see

- **Sub-opcodes of `0xFC` / `0xFD`** — `onOp` surfaces the prefix byte (`0xFC` / `0xFD`), not the sub-opcode. If you want per-sub-op visibility, read the next byte off the function body yourself or maintain a side counter.
- **Block / loop / if entries** — these are control-flow opcodes (`0x02` / `0x03` / `0x04`) and surface through `onOp` like everything else, but the tracer doesn't fire a dedicated "block entered" callback. Same for `br` / `br_if` / `br_table` — they're just opcodes.
- **Memory accesses** — load/store ops fire `onOp` like everything else. The tracer doesn't surface the resolved address, the touched memory, or the byte payload.
- **Validator-time events** — the tracer is bound to *runtime* execution. Validation runs at `Runtime.instantiate` and emits errors via `Left(WasmError)` directly.

For all four of these cases the tracer is a hook point you'd build on top of — combine `onOp` with the module's BlockInfo / function body to derive the higher-level events you need.

## Lifetime + reuse

A Tracer is bound to a single invocation. Reuse across invocations is allowed if your Tracer's internal state is reset by the caller — the runtime never touches Tracer fields between calls. `Tracer.Counting` instances are safe to construct once and inspect after each invoke; reset the counters yourself if you want fresh totals per call.

The runtime is single-threaded (one in-flight invoke at a time); a Tracer never sees concurrent callbacks.
