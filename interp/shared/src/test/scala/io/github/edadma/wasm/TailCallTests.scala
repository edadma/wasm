package io.github.edadma.wasm

import TestSupport.*
import scala.collection.mutable.ArrayBuffer

/** Tail-call proposal — `return_call` (`0x12`) and `return_call_indirect`
  * (`0x13`). The callee replaces the current frame on the call stack:
  * its result type must equal the current function's, and its results
  * become the current function's return values. Frame-reuse is observable
  * via deep recursion that would otherwise grow `frames` unboundedly.
  *
  * Wire shape:
  *   - `0x12 u32:funcidx`
  *   - `0x13 u32:typeidx u32:tableidx`
  */
object TailCallTests:

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
      val withLocals = b(0x00) ++ body                   // zero local groups
      b(withLocals.length) ++ withLocals
    }
    sec(0x0a, payload)

  /** Build an i32.const opcode + its SLEB128-encoded operand. Hand-built
    * fixtures repeatedly trip over the "bit 6 = sign bit" rule when
    * encoding values ≥ 64 as a single byte; this helper sidesteps it. */
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

  // Blocktype byte aliases.
  private val BT_I32: Int = 0x7f
  private val BT_VOID: Int = 0x40

  // === Test runner =========================================================

  def run(): Unit =
    runtime()
    deepRecursion()
    indirect()
    hostTailCall()
    validator()

  // === Runtime ============================================================

  private def runtime(): Unit =

    test("return_call: simple round-trip returns callee's result through to caller") {
      // Two functions, both () -> i32.
      //   f0: return_call $f1
      //   f1: i32.const 42; end
      // The exported entry is f0; its result should be 42.
      val typeS = typeSec(ft(Nil, Seq(BT_I32)))   // type 0: () -> i32
      val funcS = funcSec(0, 0)                   // two funcs, both type 0
      val expS  = expSec(("f", 0x00, 0))          // export f0
      val f0    = b(0x12, 0x01, 0x0b)             // return_call $f1; end
      val f1    = i32Const(42) ++ b(0x0b)         // i32.const 42; end
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(f0, f1)
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 42, s"expected 42, got $v")
    }

    test("return_call: caller and callee both have one i32 param + one i32 result") {
      // f0(x) = return_call $f1(x + 1)
      // f1(x) = x * 2
      // f0(10) should equal 22.
      val typeS = typeSec(ft(Seq(BT_I32), Seq(BT_I32)))   // type 0: (i32) -> i32
      val funcS = funcSec(0, 0)
      val expS  = expSec(("f", 0x00, 0))
      val f0    = b(0x20, 0x00) ++ i32Const(1) ++ b(0x6a, 0x12, 0x01, 0x0b)
      //          local.get 0 ;  i32.const 1 ; i32.add ; return_call $f1 ; end
      val f1    = b(0x20, 0x00) ++ i32Const(2) ++ b(0x6c, 0x0b)
      //          local.get 0 ; i32.const 2 ; i32.mul ; end
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(f0, f1)
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f", 10)
      check(v == 22, s"expected 22, got $v")
    }

  private def deepRecursion(): Unit =

    test("return_call: deep tail recursion (100_000 iterations) holds without blowing the call stack") {
      // f(n, acc) = if n == 0 then acc else return_call f(n-1, acc+1)
      // entry()   = return_call f(100000, 0)
      //
      // With tail-call frame reuse this completes in constant `frames`
      // memory and seconds-level time. Without it, `frames` would grow
      // to 100k entries.
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),                       // 0: () -> i32 (entry)
        ft(Seq(BT_I32, BT_I32), Seq(BT_I32)),       // 1: (i32, i32) -> i32 (recursive worker)
      )
      val funcS = funcSec(0, 1)                     // entry: type 0; worker: type 1
      val expS  = expSec(("entry", 0x00, 0))
      val entry = i32Const(100000) ++ i32Const(0) ++ b(0x12, 0x01, 0x0b)
      //          push 100000, push 0, return_call $worker, end
      val worker =
        b(0x20, 0x00, 0x45,                         // local.get 0; i32.eqz
          0x04, BT_I32,                             // if (result i32)
            0x20, 0x01,                             //   local.get 1 (acc — return it)
          0x05,                                     // else
            0x20, 0x00) ++ i32Const(1) ++ b(0x6b,   //   local.get 0; i32.const 1; i32.sub
            0x20, 0x01) ++ i32Const(1) ++ b(0x6a,   //   local.get 1; i32.const 1; i32.add
            0x12, 0x01,                             //   return_call $worker
          0x0b,                                     // end if
          0x0b)                                     // end func
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(entry, worker)
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "entry")
      check(v == 100000, s"expected 100000, got $v")
    }

  private def indirect(): Unit =

    test("return_call_indirect: dispatches through a funcref table") {
      // Two worker funcs, both (i32) -> i32 — one doubles, one negates.
      // f0(x, sel) = return_call_indirect table[sel](x)
      // Both worker funcs share type 1; the table has them at slots 0/1.
      val typeS = typeSec(
        ft(Seq(BT_I32, BT_I32), Seq(BT_I32)),   // 0: (x, sel) -> i32   (entry)
        ft(Seq(BT_I32), Seq(BT_I32)),           // 1: (i32) -> i32      (workers + table type)
      )
      val funcS = funcSec(0, 1, 1)              // entry, worker_dbl, worker_neg
      val tableS = sec(0x04,
        b(0x01,                                  // 1 table
          0x70,                                   //   reftype funcref
          0x00, 0x02))                            //   limits: min=2
      val expS  = expSec(("entry", 0x00, 0))
      // Element section: active funcref segment populating table 0 slots 0..1.
      // Flag 0 (active, table 0, offset, vec<funcidx>).
      val elemS = sec(0x09,
        b(0x01,                                   // 1 segment
          0x00) ++                                 //   flag 0 (active, default table)
        b(0x41, 0x00, 0x0b) ++                    //   offset expr: i32.const 0; end
        b(0x02, 0x01, 0x02))                      //   vec: funcidx 1 (dbl) + funcidx 2 (neg)
      // entry(x, sel):
      //   local.get 0      (x)
      //   local.get 1      (sel)
      //   return_call_indirect type=1 table=0
      val entry = b(0x20, 0x00, 0x20, 0x01, 0x13, 0x01, 0x00, 0x0b)
      // worker_dbl(x): x * 2
      val dbl   = b(0x20, 0x00) ++ i32Const(2) ++ b(0x6c, 0x0b)
      // worker_neg(x): 0 - x
      val neg   = i32Const(0) ++ b(0x20, 0x00, 0x6b, 0x0b)
      val bytes = Header ++ typeS ++ funcS ++ tableS ++ expS ++ elemS ++ codeSec(entry, dbl, neg)
      val inst  = instantiate(bytes)
      check(callI32(inst, "entry", 5, 0) == 10,  s"sel=0 (dbl): expected 10, got ${callI32(inst, "entry", 5, 0)}")
      check(callI32(inst, "entry", 5, 1) == -5,  s"sel=1 (neg): expected -5, got ${callI32(inst, "entry", 5, 1)}")
    }

  // === Host fn tail-call ==================================================

  private def hostTailCall(): Unit =

    test("return_call to an imported host function — host's result becomes caller's result") {
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),                   // 0: () -> i32 (host import + caller)
      )
      val importS = sec(0x02,
        b(0x01,                                 // 1 import
          0x03, 'e'.toInt, 'n'.toInt, 'v'.toInt,
          0x06, 'p'.toInt, 'i'.toInt, 'c'.toInt, 'k'.toInt, '_'.toInt, '7'.toInt,
          0x00, 0x00))                           // kind=func, typeidx=0
      val funcS = funcSec(0)                    // one defined func (funcIdx 1 — imports come first)
      val expS  = expSec(("f", 0x00, 0x01))
      val body  = b(0x12, 0x00, 0x0b)           // return_call $host_import; end
      val bytes = Header ++ typeS ++ importS ++ funcS ++ expS ++ codeSec(body)
      val env: HostModule = new HostModule:
        val name: String = "env"
        override val functions: Map[String, HostFunc] = Map(
          "pick_7" -> ((_: Memory, _: Seq[Value]) => Seq[Value](I32(7))),
        )
      val inst = runRight(Runtime.instantiate(bytes, Seq(env)))
      val v    = callI32(inst, "f")
      check(v == 7, s"expected 7 (host returned it via tail call), got $v")
    }

  // === Validator ===========================================================

  private def validator(): Unit =

    test("return_call validator: callee result arity mismatch rejected") {
      // f0: () -> (i32), tail-calls f1: () -> (i64). Different results
      // → validator rejects (i32 != i64).
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),                   // 0: () -> i32
        ft(Nil, Seq(0x7e)),                     // 1: () -> i64
      )
      val funcS = funcSec(0, 1)
      val expS  = expSec(("f", 0x00, 0))
      val f0    = b(0x12, 0x01, 0x0b)
      val f1    = b(0x42, 0x00, 0x0b)           // i64.const 0; end
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(f0, f1)
      Parser.parse(bytes).flatMap(m => Validator.validate(m).map(_ => m)) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("must equal current function") || msg.contains("results"),
                s"unexpected message: $msg")
        case other =>
          check(false, s"expected InvalidModule(result arity), got $other")
    }

    test("return_call validator: bad function index rejected") {
      val typeS = typeSec(ft(Nil, Seq(BT_I32)))
      val funcS = funcSec(0)
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(0x12, 0x09, 0x0b)            // return_call funcidx=9 (only 1 func exists)
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(body)
      Parser.parse(bytes).flatMap(m => Validator.validate(m).map(_ => m)) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("function index 9"), s"unexpected message: $msg")
        case other =>
          check(false, s"expected InvalidModule(func index 9), got $other")
    }

    test("return_call_indirect validator: non-funcref table rejected") {
      // externref table (reftype 0x6F). return_call_indirect through it
      // should be rejected just like call_indirect is.
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),                   // 0: () -> i32 (caller)
        ft(Nil, Seq(BT_I32)),                   // 1: () -> i32 (signature for call_indirect)
      )
      val funcS = funcSec(0)
      val tableS = sec(0x04,
        b(0x01, 0x6f, 0x00, 0x02))               // 1 table, externref, min=2
      val expS  = expSec(("f", 0x00, 0))
      // body: i32.const 0 ; return_call_indirect type=1 table=0 ; end
      val body  = i32Const(0) ++ b(0x13, 0x01, 0x00, 0x0b)
      val bytes = Header ++ typeS ++ funcS ++ tableS ++ expS ++ codeSec(body)
      Parser.parse(bytes).flatMap(m => Validator.validate(m).map(_ => m)) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("externref") || msg.contains("must be funcref"),
                s"unexpected message: $msg")
        case other =>
          check(false, s"expected InvalidModule(externref table), got $other")
    }
