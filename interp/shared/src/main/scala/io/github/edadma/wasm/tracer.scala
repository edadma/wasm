package io.github.edadma.wasm

/** Instrumentation hook for [[ModuleInstance.invoke]] / `Wasi.run` /
  * any direct [[Interpreter]] construction. Implementors receive callbacks
  * at well-defined points in the interpreter loop without paying any
  * per-instruction allocation cost — the callbacks default to no-ops, the
  * JIT inlines [[Tracer.NoOp]] away to nothing for the no-tracer case.
  *
  * The trait is deliberately narrow: top-level opcode bytes (sub-opcodes
  * of `0xFC` / `0xFD` are not surfaced), function-frame transitions, and
  * the two terminal events (throw + trap). For finer-grained tracing
  * (block entries, branch counts, memory accesses) build your own
  * profiler around this surface and a `WasiContext.collecting`-style
  * collecting sink — the test suite's `Tracer.Counting` is a worked
  * example.
  *
  * Lifetime: a Tracer is bound to a single invocation. Reuse across
  * invocations is allowed if the Tracer's internal state is reset by the
  * caller — the runtime never touches counters between calls.
  */
trait Tracer:

  /** Called once per opcode dispatch, just before the opcode runs. `op`
    * is the top-level byte: `0xFC` / `0xFD` are surfaced as themselves,
    * not split into their sub-opcode values. */
  def onOp(op: Int): Unit = ()

  /** Called when a fresh wasm-defined function frame is pushed. `funcIdx`
    * is in the module's unified function space (imports first, then
    * defined). For host-function invocations (no frame pushed) the
    * runtime fires [[onHostCall]] instead. */
  def onCall(funcIdx: Int): Unit = ()

  /** Called when a host function is dispatched in-place — host functions
    * don't get a wasm frame, so they fire this hook instead of
    * [[onCall]] / [[onReturn]]. */
  def onHostCall(funcIdx: Int): Unit = ()

  /** Called when a wasm frame is popped via normal `return` or the
    * function's implicit body-end. Tail calls fire [[onReturn]] for the
    * caller and [[onCall]] for the callee (or [[onHostCall]] if the
    * tail-callee is a host function). */
  def onReturn(funcIdx: Int): Unit = ()

  /** Called when a `throw tagidx` or `throw_ref` raises an exception
    * (just before the runtime starts the handler-search walk). Fires
    * once per raise — not once per popped frame during unwinding. */
  def onThrow(tagIdx: Int): Unit = ()

  /** Called when a trap (any non-throw runtime error — unreachable,
    * out-of-bounds memory, div-by-zero, etc.) terminates the
    * invocation. The interpreter still returns
    * `Left(err)` to the caller; this hook is informational only. */
  def onTrap(err: WasmError): Unit = ()

object Tracer:

  /** The zero-overhead Tracer — every callback is the default empty
    * implementation. Used as the default for `Interpreter` /
    * `ModuleInstance.invoke` / `Wasi.run` so callers that don't ask for
    * tracing pay nothing. */
  object NoOp extends Tracer

  /** Convenience constructor for the bundled [[Counting]] tracer. */
  def counting: Counting = new Counting

  /** A drop-in Tracer that totals opcode executions, function calls, and
    * tracks the maximum nesting depth observed during the invocation.
    * Inspect the public fields after the invoke returns.
    *
    * `maxDepth` counts wasm frames only (host calls don't push a frame,
    * so they don't increase depth). A purely-iterative top-level function
    * sees `maxDepth == 1`; a tail-recursive loop also sees `maxDepth == 1`
    * regardless of iteration count.
    */
  final class Counting extends Tracer:
    var ops:       Long = 0L
    var calls:     Long = 0L
    var hostCalls: Long = 0L
    var throws:    Long = 0L
    var traps:     Long = 0L
    /** Max wasm-frame depth observed at any point during the invocation. */
    var maxDepth:  Int  = 0

    private var depth: Int = 0

    override def onOp(op: Int): Unit            = ops += 1
    override def onCall(funcIdx: Int): Unit     =
      calls += 1
      depth += 1
      if depth > maxDepth then maxDepth = depth
    override def onReturn(funcIdx: Int): Unit   =
      depth -= 1
    override def onHostCall(funcIdx: Int): Unit = hostCalls += 1
    override def onThrow(tagIdx: Int): Unit     = throws += 1
    override def onTrap(err: WasmError): Unit   = traps += 1
