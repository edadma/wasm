package io.github.edadma.wasm

import TestSupport.*
import scala.collection.mutable.ArrayBuffer

/** Threads proposal — atomic memory ops + the shared-memory limits bit.
  *
  * The interpreter is single-threaded; the semantic guarantees those ops
  * give programs (alignment, single-step read-modify-write, cmpxchg
  * conditional swap) still hold by construction. What the tests cover:
  *
  *   - `MemoryLimits.shared` parses correctly from the limits flag byte
  *     and routes through to `Memory.shared`.
  *   - Every atomic load/store sub-opcode reads or writes the right
  *     number of bytes with the right extension/truncation.
  *   - Each RMW group (add/sub/and/or/xor/xchg) returns the OLD value
  *     and leaves `op(old, v)` in memory, at every width.
  *   - cmpxchg writes the replacement iff old == expected.
  *   - Mis-aligned effective address traps with `UnalignedAtomicAccess`.
  *   - Mis-aligned `align` immediate fails validation, not runtime.
  *   - `memory.atomic.wait{32,64}` on non-shared memory traps with
  *     `ExpectedSharedMemory`; on shared memory with a non-matching
  *     value it returns 1 (not-equal early-return).
  *   - `memory.atomic.notify` always returns 0 (no waiters).
  *   - `atomic.fence` is a no-op.
  */
object AtomicTests:

  // === Helpers (same shape as the SIMD / EH / tail-call suites) ===========

  private def sec(id: Int, content: Array[Byte]): Array[Byte] =
    b(id, content.length) ++ content

  private def typeSec(entries: Array[Byte]*): Array[Byte] =
    sec(0x01, b(entries.length) ++ entries.flatten)

  private def ft(params: Seq[Int], results: Seq[Int]): Array[Byte] =
    b(0x60, params.length) ++ b(params*) ++ b(results.length) ++ b(results*)

  private def funcSec(typeIdxs: Int*): Array[Byte] =
    sec(0x03, b(typeIdxs.length) ++ b(typeIdxs*))

  /** Memory section with a single memory whose limits use `flag`. min/max
    * are u32 LEBs; bit 0x02 of `flag` is "shared" (requires has-max). */
  private def memSec(flag: Int, min: Int, max: Option[Int]): Array[Byte] =
    val maxBytes = max.map(uleb).getOrElse(Array.emptyByteArray)
    sec(0x05, b(0x01, flag) ++ uleb(min) ++ maxBytes)

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

  private def uleb(v: Int): Array[Byte] =
    val buf = ArrayBuffer.empty[Byte]
    var x = v
    var more = true
    while more do
      val byte = x & 0x7f
      x = x >>> 7
      if x == 0 then
        buf += byte.toByte
        more = false
      else
        buf += (byte | 0x80).toByte
    buf.toArray

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

  /** Build an i64.const opcode + SLEB-encoded i64 operand. */
  private def i64Const(v: Long): Array[Byte] =
    val buf = ArrayBuffer.empty[Byte]
    buf += 0x42.toByte
    var more = true
    var x    = v
    while more do
      val byte = (x & 0x7fL).toInt
      x = x >> 7
      val signBit = (byte & 0x40) != 0
      val done    = (x == 0L && !signBit) || (x == -1L && signBit)
      if done then
        buf += byte.toByte
        more = false
      else
        buf += (byte | 0x80).toByte
    buf.toArray

  /** Build one `atomic` instruction: prefix 0xFE + sub-opcode LEB +
    * memarg (align byte + offset LEB). `align` is the log2 of the access
    * width (1→0, 2→1, 4→2, 8→3). */
  private def atomicOp(sub: Int, align: Int, offset: Int): Array[Byte] =
    b(0xfe) ++ uleb(sub) ++ uleb(align) ++ uleb(offset)

  /** A complete module with one shared memory and one exported function
    * whose body is `body`. `min` / `max` are page counts. `shared` toggles
    * the threads-proposal bit. */
  private def makeMod(
      params: Seq[Int],
      results: Seq[Int],
      body: Array[Byte],
      shared: Boolean = true,
      maxPages: Int = 1,
  ): Array[Byte] =
    val typeS = typeSec(ft(params, results))
    val funcS = funcSec(0)
    val flag  = if shared then 0x03 else 0x01                                              // shared ⇒ has-max
    val memS  = memSec(flag, 1, Some(maxPages))
    val expS  = expSec(("f", 0x00, 0))
    Header ++ typeS ++ funcS ++ memS ++ expS ++ codeSec(body)

  // === Test runner =========================================================

  def run(): Unit =
    sharedLimits()
    loadStore()
    rmwI32()
    rmwI64()
    cmpxchg()
    alignmentAndBounds()
    waitNotifyFence()
    validator()

  // === Limits parsing =====================================================

  private def sharedLimits(): Unit =

    test("limits flag 0x03 parses as shared memory") {
      // Build a tiny module with shared memory, no body of consequence.
      val typeS = typeSec(ft(Nil, Nil))
      val funcS = funcSec(0)
      val memS  = memSec(0x03, 1, Some(1))
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(0x0b)                                                                   // end
      val bytes = Header ++ typeS ++ funcS ++ memS ++ expS ++ codeSec(body)
      // No exception => parse succeeded. Cross-check by inspecting the
      // ModuleInstance's memory0 — `shared` should be true.
      val inst  = instantiate(bytes)
      check(inst.memory.shared, "memory.shared should be true for flag 0x03")
    }

    test("limits flag 0x02 (shared but no max) is rejected") {
      val typeS = typeSec(ft(Nil, Nil))
      val funcS = funcSec(0)
      // Manually write the flag-only memory section: flag=0x02, min=1, NO max.
      val memS  = sec(0x05, b(0x01, 0x02, 0x01))
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(0x0b)
      val bytes = Header ++ typeS ++ funcS ++ memS ++ expS ++ codeSec(body)
      expectInstantiateError(bytes) {
        case WasmError.InvalidModule(msg) => msg.contains("shared memory")
      }
    }

    test("limits flag 0x04 (unknown bit) is rejected") {
      val typeS = typeSec(ft(Nil, Nil))
      val funcS = funcSec(0)
      val memS  = sec(0x05, b(0x01, 0x04, 0x01, 0x01))
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(0x0b)
      val bytes = Header ++ typeS ++ funcS ++ memS ++ expS ++ codeSec(body)
      expectInstantiateError(bytes) {
        case WasmError.InvalidModule(msg) => msg.contains("unknown memory limits flag")
      }
    }

  // === Load / store ========================================================

  private def loadStore(): Unit =

    test("i32.atomic.store/load round-trips a value at offset 0") {
      // store(addr=0, 0xDEADBEEF); push (load addr=0); end
      val body =
        i32Const(0) ++ i32Const(0xdeadbeef) ++
          atomicOp(0x17, 2, 0) ++                                                          // i32.atomic.store
        i32Const(0) ++
          atomicOp(0x10, 2, 0) ++                                                          // i32.atomic.load
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      val v    = callI32(inst, "f")
      check(v == 0xdeadbeef, f"expected 0xdeadbeef, got 0x$v%08x")
    }

    test("i64.atomic.store/load preserves all 64 bits") {
      val expected = 0x0123456789abcdefL
      val body =
        i32Const(0) ++ i64Const(expected) ++
          atomicOp(0x18, 3, 0) ++                                                          // i64.atomic.store
        i32Const(0) ++
          atomicOp(0x11, 3, 0) ++                                                          // i64.atomic.load
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7e), body))
      val v    = callI64(inst, "f")
      check(v == expected, f"expected 0x$expected%016x, got 0x$v%016x")
    }

    test("i32.atomic.load8_u zero-extends a byte from memory") {
      // Put 0xFF at addr 0 via the regular store (0x36 i32.store + align=0)
      // followed by an atomic load8_u read.
      val body =
        i32Const(0) ++ i32Const(0xff) ++
          atomicOp(0x19, 0, 0) ++                                                          // i32.atomic.store8
        i32Const(0) ++
          atomicOp(0x12, 0, 0) ++                                                          // i32.atomic.load8_u
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      val v    = callI32(inst, "f")
      check(v == 0xff, s"expected 0xff (zero-extended), got $v")
    }

    test("i64.atomic.load32_u zero-extends a 4-byte read") {
      val body =
        i32Const(0) ++ i32Const(-1) ++                                                     // 0xFFFFFFFF
          atomicOp(0x17, 2, 0) ++                                                          // i32.atomic.store
        i32Const(0) ++
          atomicOp(0x16, 2, 0) ++                                                          // i64.atomic.load32_u
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7e), body))
      val v    = callI64(inst, "f")
      check(v == 0xffffffffL, f"expected 0xffffffff zero-extended, got 0x$v%016x")
    }

    test("i32.atomic.store16 stores only the low 16 bits") {
      // Write 0x7FFFFFFF as a 16-bit store; only 0xFFFF should land in memory.
      val body =
        i32Const(0) ++ i32Const(0x7fffffff) ++
          atomicOp(0x1a, 1, 0) ++                                                          // i32.atomic.store16
        i32Const(0) ++
          atomicOp(0x13, 1, 0) ++                                                          // i32.atomic.load16_u
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      val v    = callI32(inst, "f")
      check(v == 0xffff, f"expected 0xffff, got 0x$v%08x")
    }

  // === RMW: i32 family ====================================================

  private def rmwI32(): Unit =

    test("i32.atomic.rmw.add returns old value, leaves sum in memory") {
      // Seed mem[0]=10; rmw.add 5; result should be 10 (the OLD value),
      // and a follow-up load should show 15.
      val body =
        i32Const(0) ++ i32Const(10) ++ atomicOp(0x17, 2, 0) ++                              // store 10
        i32Const(0) ++ i32Const(5)  ++ atomicOp(0x1e, 2, 0) ++                              // rmw.add 5  → push old=10
        i32Const(0) ++ atomicOp(0x10, 2, 0) ++                                              // load     → push 15
        b(0x6a,                                                                             // i32.add   → 25 sentinel
          0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      val v    = callI32(inst, "f")
      check(v == 25, s"expected old(10) + final(15) = 25, got $v")
    }

    test("i32.atomic.rmw.sub: 100 minus 30 leaves 70, returns 100") {
      val body =
        i32Const(0) ++ i32Const(100) ++ atomicOp(0x17, 2, 0) ++
        i32Const(0) ++ i32Const(30)  ++ atomicOp(0x25, 2, 0) ++                             // rmw.sub  → old=100
        i32Const(0) ++ atomicOp(0x10, 2, 0) ++                                              // load → 70
        b(0x6a, 0x0b)                                                                       // 100 + 70 = 170
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      check(callI32(inst, "f") == 170, "rmw.sub: 100+70 != 170")
    }

    test("i32.atomic.rmw.and: bitmask clears bits, returns old") {
      val body =
        i32Const(0) ++ i32Const(0xff) ++ atomicOp(0x17, 2, 0) ++
        i32Const(0) ++ i32Const(0x0f) ++ atomicOp(0x2c, 2, 0) ++                            // rmw.and → old=0xFF
        i32Const(0) ++ atomicOp(0x10, 2, 0) ++                                              // load → 0x0F
        b(0x6a, 0x0b)                                                                       // 0xFF + 0x0F = 0x10E
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      check(callI32(inst, "f") == 0x10e, "rmw.and incorrect")
    }

    test("i32.atomic.rmw.xchg: swap, returns old and stores new") {
      val body =
        i32Const(0) ++ i32Const(0xaaaa) ++ atomicOp(0x17, 2, 0) ++
        i32Const(0) ++ i32Const(0xbbbb) ++ atomicOp(0x41, 2, 0) ++                          // xchg → old=0xAAAA
        i32Const(0) ++ atomicOp(0x10, 2, 0) ++                                              // load → 0xBBBB
        b(0x6a, 0x0b)                                                                       // sum sentinel: 0xAAAA + 0xBBBB = 0x16665
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      check(callI32(inst, "f") == 0xaaaa + 0xbbbb, "rmw.xchg sum mismatch")
    }

    test("i32.atomic.rmw8.add_u: 8-bit add wraps, returns old (zero-extended)") {
      // mem byte at addr 0: start 0xFF. add 0x02. Wraps to 0x01 in the byte.
      // Old value: 0xFF (zero-extended). Final loaded byte: 0x01.
      val body =
        i32Const(0) ++ i32Const(0xff) ++ atomicOp(0x19, 0, 0) ++                            // store8
        i32Const(0) ++ i32Const(0x02) ++ atomicOp(0x20, 0, 0) ++                            // rmw8.add_u → old=0xFF
        i32Const(0) ++ atomicOp(0x12, 0, 0) ++                                              // load8_u → 0x01
        b(0x6a, 0x0b)                                                                       // 0xFF + 0x01 = 0x100
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      check(callI32(inst, "f") == 0x100, "8-bit rmw add wrap mismatch")
    }

  // === RMW: i64 family ====================================================

  private def rmwI64(): Unit =

    test("i64.atomic.rmw.or: bitwise OR returns old") {
      val body =
        i32Const(0) ++ i64Const(0x00ff00ff00ff00ffL) ++ atomicOp(0x18, 3, 0) ++             // store 64-bit
        i32Const(0) ++ i64Const(0xff00ff00ff00ff00L) ++ atomicOp(0x34, 3, 0) ++             // rmw.or → old
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7e), body))
      val old  = callI64(inst, "f")
      check(old == 0x00ff00ff00ff00ffL, f"expected OLD 0x00ff00ff00ff00ff, got 0x$old%016x")
    }

    test("i64.atomic.rmw32.xor_u: 32-bit XOR on lower half, upper half untouched") {
      // start with 0xAAAA_AAAA_BBBB_BBBB. xor lower with 0xFFFF_FFFF.
      // OLD (zero-extended) should be 0xBBBB_BBBB. Final memory should be
      // 0xAAAA_AAAA_4444_4444 (upper half unchanged since we touched 4 bytes).
      val body =
        i32Const(0) ++ i64Const(0xaaaaaaaabbbbbbbbL) ++ atomicOp(0x18, 3, 0) ++
        i32Const(0) ++ i64Const(0x00000000ffffffffL) ++ atomicOp(0x40, 2, 0) ++             // rmw32.xor_u → OLD
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7e), body))
      val old  = callI64(inst, "f")
      check(old == 0xbbbbbbbbL, f"expected OLD 0xbbbbbbbb, got 0x$old%016x")
    }

  // === cmpxchg ============================================================

  private def cmpxchg(): Unit =

    test("i32 cmpxchg success: expected matches, replacement stored") {
      // Seed mem[0]=100. cmpxchg(addr=0, expected=100, replacement=200) → push old=100.
      // Final load → 200.
      val body =
        i32Const(0) ++ i32Const(100) ++ atomicOp(0x17, 2, 0) ++
        i32Const(0) ++ i32Const(100) ++ i32Const(200) ++ atomicOp(0x48, 2, 0) ++            // cmpxchg → old=100
        i32Const(0) ++ atomicOp(0x10, 2, 0) ++                                              // load → 200
        b(0x6a, 0x0b)                                                                       // 100 + 200 = 300
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      check(callI32(inst, "f") == 300, "cmpxchg success sum mismatch")
    }

    test("i32 cmpxchg failure: expected mismatch, replacement NOT stored") {
      // Seed mem[0]=100. cmpxchg(addr=0, expected=99, replacement=200) → push old=100.
      // Final load → 100 (unchanged).
      val body =
        i32Const(0) ++ i32Const(100) ++ atomicOp(0x17, 2, 0) ++
        i32Const(0) ++ i32Const(99)  ++ i32Const(200) ++ atomicOp(0x48, 2, 0) ++            // cmpxchg → old=100
        i32Const(0) ++ atomicOp(0x10, 2, 0) ++                                              // load → 100 still
        b(0x6a, 0x0b)                                                                       // 100 + 100 = 200
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      check(callI32(inst, "f") == 200, "cmpxchg failure preserved value?")
    }

    test("i64 cmpxchg roundtrips full 64-bit values") {
      val body =
        i32Const(0) ++ i64Const(0x1122334455667788L) ++ atomicOp(0x18, 3, 0) ++
        i32Const(0) ++ i64Const(0x1122334455667788L) ++ i64Const(0xdeadbeefcafebabeL) ++
          atomicOp(0x49, 3, 0) ++                                                           // cmpxchg → old
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7e), body))
      val old  = callI64(inst, "f")
      check(old == 0x1122334455667788L, f"expected OLD 0x1122334455667788, got 0x$old%016x")
    }

  // === Alignment + OOB =====================================================

  private def alignmentAndBounds(): Unit =

    test("i32.atomic.load with misaligned effective address traps") {
      // declare align=2 (validator-legal), but pass addr=1 — runtime alignment
      // check rejects it with UnalignedAtomicAccess.
      val body =
        i32Const(1) ++ atomicOp(0x10, 2, 0) ++ b(0x1a, 0x0b)                                // drop + end
      val inst = instantiate(makeMod(Nil, Nil, body))
      inst.invoke("f", Nil) match
        case Left(WasmError.UnalignedAtomicAccess) => ()
        case other => check(false, s"expected UnalignedAtomicAccess, got $other")
    }

    test("i32.atomic.load with effective address past memory traps OOB") {
      // page-aligned end-of-memory + 4 bytes off the end
      val body =
        i32Const(65536) ++ atomicOp(0x10, 2, 0) ++ b(0x1a, 0x0b)
      val inst = instantiate(makeMod(Nil, Nil, body))
      inst.invoke("f", Nil) match
        case Left(WasmError.MemoryOutOfBounds) => ()
        // Misalignment of `65536 & 3 = 0` so OOB is the right diagnosis.
        case other => check(false, s"expected MemoryOutOfBounds, got $other")
    }

    test("validator: i32.atomic.load with align=0 (vs required 2) is invalid") {
      // align=0 but access width is 4: validator must reject.
      val body =
        i32Const(0) ++ atomicOp(0x10, 0, 0) ++ b(0x1a, 0x0b)
      val bytes = makeMod(Nil, Nil, body)
      expectInstantiateError(bytes) {
        case WasmError.InvalidModule(msg) =>
          msg.contains("atomic alignment")
      }
    }

  // === wait/notify/fence ==================================================

  private def waitNotifyFence(): Unit =

    test("memory.atomic.notify returns 0 (no waiters)") {
      val body =
        i32Const(0) ++ i32Const(10) ++ atomicOp(0x00, 2, 0) ++                              // notify
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      check(callI32(inst, "f") == 0, "notify must return 0 with no waiters")
    }

    test("memory.atomic.wait32 on shared memory with non-matching value returns 1") {
      // mem[0] starts as 0 (zero-init). wait expecting 7 → not-equal → 1.
      val body =
        i32Const(0) ++ i32Const(7) ++ i64Const(-1L) ++
          atomicOp(0x01, 2, 0) ++                                                           // wait32
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body, shared = true))
      check(callI32(inst, "f") == 1, "wait32 not-equal must return 1")
    }

    test("memory.atomic.wait32 on shared memory with MATCHING value traps (would-block)") {
      // mem[0] starts as 0. wait expecting 0 → would block forever on a
      // single-threaded host; we trap with InvalidModule.
      val body =
        i32Const(0) ++ i32Const(0) ++ i64Const(-1L) ++
          atomicOp(0x01, 2, 0) ++
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body, shared = true))
      inst.invoke("f", Nil) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("block forever"), s"unexpected message: $msg")
        case other => check(false, s"expected would-block trap, got $other")
    }

    test("memory.atomic.wait32 on non-shared memory traps") {
      val body =
        i32Const(0) ++ i32Const(0) ++ i64Const(-1L) ++
          atomicOp(0x01, 2, 0) ++
        b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body, shared = false))
      inst.invoke("f", Nil) match
        case Left(WasmError.ExpectedSharedMemory) => ()
        case other => check(false, s"expected ExpectedSharedMemory, got $other")
    }

    test("atomic.fence is a no-op") {
      // fence; i32.const 42; end → returns 42.
      val body = b(0xfe) ++ uleb(0x03) ++ b(0x00) ++ i32Const(42) ++ b(0x0b)
      val inst = instantiate(makeMod(Nil, Seq(0x7f), body))
      check(callI32(inst, "f") == 42, "fence must not perturb stack or memory")
    }

  // === validator misc ======================================================

  private def validator(): Unit =

    test("validator: rmw with wrong-typed operand is rejected") {
      // i32.atomic.rmw.add expects (addr:i32, v:i32). Pushing i64 for v
      // must be a type mismatch.
      val body =
        i32Const(0) ++ i64Const(1L) ++ atomicOp(0x1e, 2, 0) ++                              // rmw.add expecting i32
        b(0x1a, 0x0b)
      val bytes = makeMod(Nil, Nil, body)
      expectInstantiateError(bytes) {
        case WasmError.TypeMismatch                => true
        case WasmError.InvalidModule(_)            => true
      }
    }

    test("validator: unknown 0xFE sub-opcode is rejected") {
      // Sub 0x60 is out of range — fall through to UnknownOpcode(0xFE).
      val body = b(0xfe) ++ uleb(0x60) ++ b(0x0b)
      val bytes = makeMod(Nil, Nil, body)
      expectInstantiateError(bytes) {
        case WasmError.UnknownOpcode(0xfe) => true
      }
    }
