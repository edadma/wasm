package io.github.edadma.wasm

import TestSupport.*
import scala.collection.mutable.ArrayBuffer

/** Instrumentation / profiling hooks. The [[Tracer]] API surfaces:
  *   - per-opcode `onOp`
  *   - frame transitions `onCall` / `onHostCall` / `onReturn`
  *   - exception raises `onThrow`
  *   - trap surface `onTrap`
  *
  * Each test sets up a tiny module and confirms the bundled
  * [[Tracer.Counting]] (or a custom Tracer impl) observes the right
  * events. The "untraced" invariant — `Tracer.NoOp` does nothing
  * observable — is covered implicitly: every other test file in the
  * suite runs without a tracer and stays green.
  */
object TracerTests:

  // === Helpers ============================================================

  private def sec(id: Int, content: Array[Byte]): Array[Byte] =
    b(id, content.length) ++ content

  private def typeSec(entries: Array[Byte]*): Array[Byte] =
    sec(0x01, b(entries.length) ++ entries.flatten)

  private def ft(params: Seq[Int], results: Seq[Int]): Array[Byte] =
    b(0x60, params.length) ++ b(params*) ++ b(results.length) ++ b(results*)

  private def funcSec(typeIdxs: Int*): Array[Byte] =
    sec(0x03, b(typeIdxs.length) ++ b(typeIdxs*))

  private def expSec(entries: (String, Int, Int)*): Array[Byte] =
    val body = b(entries.length) ++ entries.toArray.flatMap { case (name, kind, idx) =>
      val nb = name.getBytes("UTF-8")
      b(nb.length) ++ nb ++ b(kind, idx)
    }
    sec(0x07, body)

  private def codeSec(bodies: Array[Byte]*): Array[Byte] =
    val payload = b(bodies.length) ++ bodies.flatMap { body =>
      val withLocals = b(0x00) ++ body
      b(withLocals.length) ++ withLocals
    }
    sec(0x0a, payload)

  /** SLEB128 i32.const helper — see TailCallTests for rationale. */
  private def i32Const(v: Int): Array[Byte] =
    val buf = ArrayBuffer.empty[Byte]
    buf += 0x41.toByte
    var more = true
    var x    = v
    while more do
      val byte = x & 0x7f
      x = x >> 7
      val signBit = (byte & 0x40) != 0
      val done    = (x == 0 && !signBit) || (x == -1 && signBit)
      if done then
        buf += byte.toByte
        more = false
      else
        buf += (byte | 0x80).toByte
    buf.toArray

  // === Test runner =========================================================

  def run(): Unit =
    counting()
    customTracer()

  // === Tracer.Counting ====================================================

  private def counting(): Unit =

    test("Tracer.Counting: ops + returns total correctly on a simple constant function") {
      // f0(): i32.const 5; end — two opcodes step (i32.const, end).
      val typeS = typeSec(ft(Nil, Seq(0x7f)))
      val funcS = funcSec(0)
      val expS  = expSec(("f", 0x00, 0))
      val body  = i32Const(5) ++ b(0x0b)
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(body)
      val inst  = instantiate(bytes)
      val tr    = Tracer.counting
      runOk(inst.invoke("f", Seq.empty, tr))
      check(tr.ops      == 2L, s"expected 2 ops (i32.const + end), got ${tr.ops}")
      check(tr.calls    == 1L, s"expected 1 call (f), got ${tr.calls}")
      check(tr.maxDepth == 1,  s"expected maxDepth=1, got ${tr.maxDepth}")
      check(tr.throws   == 0L, "no throws expected")
      check(tr.traps    == 0L, "no traps expected")
    }

    test("Tracer.Counting: nested calls grow maxDepth correctly") {
      // f0() = call f1(); end
      // f1() = call f2(); end
      // f2() = i32.const 9; end
      // Expected: maxDepth = 3, calls = 3, hostCalls = 0.
      val typeS = typeSec(ft(Nil, Seq(0x7f)))
      val funcS = funcSec(0, 0, 0)
      val expS  = expSec(("f", 0x00, 0))
      val f0    = b(0x10, 0x01, 0x0b)             // call $1; end
      val f1    = b(0x10, 0x02, 0x0b)             // call $2; end
      val f2    = i32Const(9) ++ b(0x0b)
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(f0, f1, f2)
      val inst  = instantiate(bytes)
      val tr    = Tracer.counting
      runOk(inst.invoke("f", Seq.empty, tr))
      check(tr.calls    == 3L, s"expected 3 calls, got ${tr.calls}")
      check(tr.maxDepth == 3,  s"expected maxDepth=3, got ${tr.maxDepth}")
    }

    test("Tracer.Counting: deep tail recursion keeps maxDepth at 1") {
      // Same shape as the tail-call deep-recursion test, but smaller (1000
      // iterations) for speed. maxDepth = 1 throughout proves frame reuse.
      val typeS = typeSec(
        ft(Nil, Seq(0x7f)),
        ft(Seq(0x7f, 0x7f), Seq(0x7f)),
      )
      val funcS = funcSec(0, 1)
      val expS  = expSec(("entry", 0x00, 0))
      val entry = i32Const(1000) ++ i32Const(0) ++ b(0x12, 0x01, 0x0b)
      val worker =
        b(0x20, 0x00, 0x45,
          0x04, 0x7f,
            0x20, 0x01,
          0x05,
            0x20, 0x00) ++ i32Const(1) ++ b(0x6b,
            0x20, 0x01) ++ i32Const(1) ++ b(0x6a,
            0x12, 0x01,
          0x0b,
          0x0b)
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(entry, worker)
      val inst  = instantiate(bytes)
      val tr    = Tracer.counting
      runOk(inst.invoke("entry", Seq.empty, tr))
      check(tr.maxDepth == 1, s"expected tail-call maxDepth=1, got ${tr.maxDepth}")
      // calls counts every fresh wasm-frame push (entry + every recursive
      // call). 1 entry + 1000 worker iterations + 1 final base case = 1002.
      check(tr.calls == 1002L, s"expected 1002 calls (1 entry + 1001 worker entries), got ${tr.calls}")
    }

    test("Tracer.Counting: host call fires onHostCall, not onCall") {
      val typeS = typeSec(ft(Nil, Seq(0x7f)))      // 0: () -> i32
      val importS = sec(0x02,
        b(0x01,
          0x03, 'e'.toInt, 'n'.toInt, 'v'.toInt,
          0x04, 'p'.toInt, 'i'.toInt, 'c'.toInt, 'k'.toInt,
          0x00, 0x00))
      val funcS = funcSec(0)                       // defined funcIdx = 1
      val expS  = expSec(("f", 0x00, 0x01))
      val body  = b(0x10, 0x00, 0x0b)              // call import (funcIdx 0); end
      val bytes = Header ++ typeS ++ importS ++ funcS ++ expS ++ codeSec(body)
      val env: HostModule = new HostModule:
        val name: String = "env"
        override val functions: Map[String, HostFunc] = Map(
          "pick" -> ((_: Memory, _: Seq[Value]) => Seq[Value](I32(11))),
        )
      val inst = runRight(Runtime.instantiate(bytes, Seq(env)))
      val tr   = Tracer.counting
      runOk(inst.invoke("f", Seq.empty, tr))
      check(tr.calls     == 1L, s"expected 1 wasm call (f), got ${tr.calls}")
      check(tr.hostCalls == 1L, s"expected 1 host call (env.pick), got ${tr.hostCalls}")
      check(tr.maxDepth  == 1,  s"host call must not grow depth, got ${tr.maxDepth}")
    }

    test("Tracer.Counting: throw fires onThrow with the right tagIdx") {
      // Tag $t with i32 payload. Function throws $t.
      val typeS = typeSec(
        ft(Nil, Seq(0x7f)),
        ft(Seq(0x7f), Nil),
      )
      val funcS = funcSec(0)
      val tagS  = sec(13, b(0x01, 0x00, 0x01))      // 1 tag, attr=0, typeidx=1
      val expS  = expSec(("f", 0x00, 0))
      val body  = i32Const(42) ++ b(0x08, 0x00, 0x0b)
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeSec(body)
      val inst  = instantiate(bytes)
      val tr    = Tracer.counting
      inst.invoke("f", Seq.empty, tr) match
        case Left(WasmError.UncaughtException(0, _)) => ()
        case other => check(false, s"expected uncaught throw, got $other")
      check(tr.throws == 1L, s"expected 1 throw, got ${tr.throws}")
      check(tr.traps  == 0L, "uncaught throw must not double-count as trap")
    }

    test("Tracer.Counting: explicit unreachable fires onTrap") {
      // function: unreachable; end — runs the 0x00 op, triggers trap.
      val typeS = typeSec(ft(Nil, Nil))
      val funcS = funcSec(0)
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(0x00, 0x0b)                     // unreachable; end
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(body)
      val inst  = instantiate(bytes)
      val tr    = Tracer.counting
      inst.invoke("f", Seq.empty, tr) match
        case Left(WasmError.UnreachableExecuted) => ()
        case other => check(false, s"expected UnreachableExecuted trap, got $other")
      check(tr.traps == 1L, s"expected 1 trap, got ${tr.traps}")
    }

  // === Custom Tracer ======================================================

  private def customTracer(): Unit =

    test("Tracer (custom): observes a precise opcode sequence on a tiny module") {
      // Build a small module and confirm the Tracer sees opcodes in
      // execution order (not declaration order).
      val typeS = typeSec(ft(Nil, Seq(0x7f)))
      val funcS = funcSec(0)
      val expS  = expSec(("f", 0x00, 0))
      // Body: i32.const 7 ; i32.const 3 ; i32.add ; end
      // Opcodes observed should be: 0x41 0x41 0x6A 0x0B.
      val body  = i32Const(7) ++ i32Const(3) ++ b(0x6a, 0x0b)
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(body)
      val inst  = instantiate(bytes)
      val seen  = ArrayBuffer.empty[Int]
      val tr: Tracer = new Tracer:
        override def onOp(op: Int): Unit = seen += op
      val result = runRight(inst.invoke("f", Seq.empty, tr))
      check(result == Seq(I32(10)), s"unexpected result: $result")
      check(seen.toSeq == Seq(0x41, 0x41, 0x6a, 0x0b),
            s"unexpected op sequence: ${seen.map(o => f"0x$o%02x").mkString(",")}")
    }
