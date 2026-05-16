package io.github.edadma.wasm

/** Threads-proposal atomic-opcode dispatch — `stepFe` plus the read/modify/
  * write helpers underneath it.
  *
  * Single-threaded interpreter, so the "atomic" part is purely structural:
  * every op is an alignment-checked load, store, read-modify-write, or
  * compare-and-swap, all serialised through the same interpreter loop.
  * Sharing observable across threads doesn't apply — the threads proposal's
  * memory model collapses to "happens before everything that follows it"
  * because there is no concurrent thread. What we still must enforce:
  *
  *   1. The effective address (base + memarg.offset) is naturally aligned
  *      to the access width. Misalignment traps with
  *      [[WasmError.UnalignedAtomicAccess]].
  *   2. `memory.atomic.wait{32,64}` is illegal on a non-shared memory
  *      ([[WasmError.ExpectedSharedMemory]]). The wait operand spec is
  *      "block if current value matches expected, else return 1
  *      (not-equal)". With no other threads to wake us, the block case
  *      would deadlock forever, so we trap on it instead — the only
  *      observable wait result is the not-equal early-return.
  *   3. `memory.atomic.notify` returns 0 (no waiters) — there cannot
  *      be any in a single-threaded run.
  *   4. `atomic.fence` is a no-op.
  *
  * The dispatch is split into three helper methods (`stepFeLoadStore`,
  * `stepFeRmw`, `stepFeCmpxchg`) so each fits comfortably under the JVM's
  * 64KB per-method bytecode ceiling — Phase 8.E's SIMD chunks established
  * the precedent that 60+ contiguous opcode arms must be extracted.
  */
private[wasm] trait AtomicDispatch:
  self: Interpreter =>

  import Interpreter.*

  /** Dispatch one 0xFE sub-opcode. Mirrors `stepFc` / `stepFd` in shape:
    * read the LEB sub-opcode, branch on it, advance `f.pc` past the
    * memarg (or the fence's reserved byte). Unknown sub-opcodes fall
    * through to `UnknownOpcode(0xFE)`. */
  private[wasm] def stepFe(f: Frame): Unit =
    val body      = f.func.body
    val (sub, p1) = readU32At(f, f.pc + 1)
    sub match
      case 0x00 =>                                                                          // memory.atomic.notify
        // Operands: [i32 addr, i32 count] → [i32 wokenWaiters]
        // Single-threaded: nobody is waiting, so count out is always 0.
        // Still validates alignment + bounds against a 4-byte access.
        val memArg = readMemArgAtPos(f, p1)
        val mem    = memArgMemory(memArg)
        val _      = popI32()                                                               // unused waiter count — no waiters
        val addr   = popI32().toLong & 0xffffffffL
        val ea     = addr + memArg.offset
        atomicCheck(mem, ea, 4)
        pushI32(0)

      case 0x01 =>                                                                          // memory.atomic.wait32
        // Operands: [i32 addr, i32 expected, i64 timeout] → [i32 result]
        // result: 0=ok (woken), 1=not-equal, 2=timed-out. With no peer
        // threads, "ok" is unreachable; "not-equal" is the only non-trap
        // observable. Block-equal traps (would deadlock forever).
        val memArg   = readMemArgAtPos(f, p1)
        val mem      = memArgMemory(memArg)
        val _        = popI64()                                                             // timeout — unused
        val expected = popI32()
        val addr     = popI32().toLong & 0xffffffffL
        val ea       = addr + memArg.offset
        atomicCheck(mem, ea, 4)
        if !mem.shared then fail(WasmError.ExpectedSharedMemory)
        val current = loadI32Direct(mem, ea)
        if current != expected then pushI32(1)                                              // not-equal early-return
        else fail(WasmError.InvalidModule(
          "memory.atomic.wait32 would block forever on a single-threaded host"))

      case 0x02 =>                                                                          // memory.atomic.wait64
        val memArg   = readMemArgAtPos(f, p1)
        val mem      = memArgMemory(memArg)
        val _        = popI64()                                                             // timeout — unused
        val expected = popI64()
        val addr     = popI32().toLong & 0xffffffffL
        val ea       = addr + memArg.offset
        atomicCheck(mem, ea, 8)
        if !mem.shared then fail(WasmError.ExpectedSharedMemory)
        val current = loadI64Direct(mem, ea)
        if current != expected then pushI32(1)
        else fail(WasmError.InvalidModule(
          "memory.atomic.wait64 would block forever on a single-threaded host"))

      case 0x03 =>                                                                          // atomic.fence
        // Single reserved byte (must be 0x00 today). With a single thread,
        // every preceding op already happens-before every following one
        // by construction — the fence is genuinely free.
        if p1 >= body.length then
          fail(WasmError.InvalidModule("truncated atomic.fence immediate"))
        f.pc = p1 + 1

      // Load/store family — width is hard-coded per opcode.
      case s if s >= 0x10 && s <= 0x1d => stepFeLoadStore(f, s, p1)
      // RMW: add/sub/and/or/xor/xchg are uniform "old = mem; mem = op(old, v); push old".
      case s if s >= 0x1e && s <= 0x47 => stepFeRmw(f, s, p1)
      // cmpxchg: "old = mem; if old == expected then mem = replacement; push old"
      case s if s >= 0x48 && s <= 0x4e => stepFeCmpxchg(f, s, p1)
      case _                           => fail(WasmError.UnknownOpcode(0xfe))

  // === load / store family ==================================================

  /** Atomic loads (subs 0x10..0x16) push the value with its declared
    * extension; atomic stores (subs 0x17..0x1d) consume the value with
    * its declared truncation. Alignment must equal the access width per
    * spec — `atomicCheck` enforces it. */
  private def stepFeLoadStore(f: Frame, sub: Int, immPos: Int): Unit =
    val memArg = readMemArgAtPos(f, immPos)
    val mem    = memArgMemory(memArg)
    sub match
      case 0x10 =>                                                                          // i32.atomic.load
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 4)
        pushI32(loadI32Direct(mem, ea))
      case 0x11 =>                                                                          // i64.atomic.load
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 8)
        pushI64(loadI64Direct(mem, ea))
      case 0x12 =>                                                                          // i32.atomic.load8_u
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 1)
        pushI32(loadByteDirect(mem, ea) & 0xff)
      case 0x13 =>                                                                          // i32.atomic.load16_u
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 2)
        pushI32(loadI16Direct(mem, ea) & 0xffff)
      case 0x14 =>                                                                          // i64.atomic.load8_u
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 1)
        pushI64((loadByteDirect(mem, ea) & 0xff).toLong)
      case 0x15 =>                                                                          // i64.atomic.load16_u
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 2)
        pushI64((loadI16Direct(mem, ea) & 0xffff).toLong)
      case 0x16 =>                                                                          // i64.atomic.load32_u
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 4)
        pushI64(loadI32Direct(mem, ea).toLong & 0xffffffffL)
      case 0x17 =>                                                                          // i32.atomic.store
        val v    = popI32()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 4)
        storeI32Direct(mem, ea, v)
      case 0x18 =>                                                                          // i64.atomic.store
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 8)
        storeI64Direct(mem, ea, v)
      case 0x19 =>                                                                          // i32.atomic.store8
        val v    = popI32()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 1)
        storeByteDirect(mem, ea, v & 0xff)
      case 0x1a =>                                                                          // i32.atomic.store16
        val v    = popI32()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 2)
        storeI16Direct(mem, ea, v & 0xffff)
      case 0x1b =>                                                                          // i64.atomic.store8
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 1)
        storeByteDirect(mem, ea, (v & 0xffL).toInt)
      case 0x1c =>                                                                          // i64.atomic.store16
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 2)
        storeI16Direct(mem, ea, (v & 0xffffL).toInt)
      case 0x1d =>                                                                          // i64.atomic.store32
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 4)
        storeI32Direct(mem, ea, (v & 0xffffffffL).toInt)
      case _    => fail(WasmError.UnknownOpcode(0xfe))

  // === RMW family ===========================================================
  //
  // Encoding is fully regular: subs 0x1e..0x47 decode as
  //   group  = (sub - 0x1e) / 7   ∈ {0..5}  → add / sub / and / or / xor / xchg
  //   width  = (sub - 0x1e) % 7   ∈ {0..6}  → width-and-result-type code:
  //     0: i32 full     (4-byte access, i32 result)
  //     1: i64 full     (8-byte access, i64 result)
  //     2: i32  8-bit   (1-byte access, i32 result)
  //     3: i32 16-bit   (2-byte access, i32 result)
  //     4: i64  8-bit   (1-byte access, i64 result)
  //     5: i64 16-bit   (2-byte access, i64 result)
  //     6: i64 32-bit   (4-byte access, i64 result)
  // Decoding here keeps the dispatch ~50 short cases instead of ~250.

  /** RMW dispatch — pops `(addr, v)`, reads old value at addr (alignment-
    * checked), writes `op(old, v)` back, pushes `old`. The 8-/16-bit forms
    * zero-extend the loaded value to the result type before applying `op`,
    * which matches every architecture's "atomic op on a sub-word
    * unsigned slice" semantics (the spec's `rmwX.OP_u` naming). */
  private def stepFeRmw(f: Frame, sub: Int, immPos: Int): Unit =
    val memArg = readMemArgAtPos(f, immPos)
    val mem    = memArgMemory(memArg)
    val group  = (sub - 0x1e) / 7                                                           // 0=add 1=sub 2=and 3=or 4=xor 5=xchg
    val width  = (sub - 0x1e) % 7
    width match
      case 0 =>                                                                             // i32 full
        val v    = popI32()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 4)
        val old = loadI32Direct(mem, ea)
        storeI32Direct(mem, ea, rmwI32(group, old, v))
        pushI32(old)
      case 1 =>                                                                             // i64 full
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 8)
        val old = loadI64Direct(mem, ea)
        storeI64Direct(mem, ea, rmwI64(group, old, v))
        pushI64(old)
      case 2 =>                                                                             // i32 8-bit
        val v    = popI32()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 1)
        val old = loadByteDirect(mem, ea) & 0xff
        storeByteDirect(mem, ea, rmwI32(group, old, v) & 0xff)
        pushI32(old)
      case 3 =>                                                                             // i32 16-bit
        val v    = popI32()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 2)
        val old = loadI16Direct(mem, ea) & 0xffff
        storeI16Direct(mem, ea, rmwI32(group, old, v) & 0xffff)
        pushI32(old)
      case 4 =>                                                                             // i64 8-bit
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 1)
        val old = (loadByteDirect(mem, ea) & 0xff).toLong
        storeByteDirect(mem, ea, (rmwI64(group, old, v) & 0xffL).toInt)
        pushI64(old)
      case 5 =>                                                                             // i64 16-bit
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 2)
        val old = (loadI16Direct(mem, ea) & 0xffff).toLong
        storeI16Direct(mem, ea, (rmwI64(group, old, v) & 0xffffL).toInt)
        pushI64(old)
      case 6 =>                                                                             // i64 32-bit
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        val ea   = addr + memArg.offset
        atomicCheck(mem, ea, 4)
        val old = loadI32Direct(mem, ea).toLong & 0xffffffffL
        storeI32Direct(mem, ea, (rmwI64(group, old, v) & 0xffffffffL).toInt)
        pushI64(old)
      case _ => fail(WasmError.UnknownOpcode(0xfe))

  /** Apply one RMW group (add/sub/and/or/xor/xchg) at i32 width. */
  private def rmwI32(group: Int, old: Int, v: Int): Int = group match
    case 0 => old + v
    case 1 => old - v
    case 2 => old & v
    case 3 => old | v
    case 4 => old ^ v
    case 5 => v                                                                             // xchg: store v, return old
    case _ => fail(WasmError.UnknownOpcode(0xfe))

  /** Apply one RMW group at i64 width. */
  private def rmwI64(group: Int, old: Long, v: Long): Long = group match
    case 0 => old + v
    case 1 => old - v
    case 2 => old & v
    case 3 => old | v
    case 4 => old ^ v
    case 5 => v
    case _ => fail(WasmError.UnknownOpcode(0xfe))

  // === cmpxchg family =======================================================
  //
  // Stack:    addr, expected, replacement → old
  // Semantics: read old at addr; if old == expected store replacement;
  //            push old either way. The sub-word forms (subs 0x4a..0x4e)
  //            compare on the zero-extended loaded value (same convention
  //            as the rmw_u sub-word ops).

  private def stepFeCmpxchg(f: Frame, sub: Int, immPos: Int): Unit =
    val memArg = readMemArgAtPos(f, immPos)
    val mem    = memArgMemory(memArg)
    sub match
      case 0x48 =>                                                                          // i32 full
        val replacement = popI32()
        val expected    = popI32()
        val addr        = popI32().toLong & 0xffffffffL
        val ea          = addr + memArg.offset
        atomicCheck(mem, ea, 4)
        val old = loadI32Direct(mem, ea)
        if old == expected then storeI32Direct(mem, ea, replacement)
        pushI32(old)
      case 0x49 =>                                                                          // i64 full
        val replacement = popI64()
        val expected    = popI64()
        val addr        = popI32().toLong & 0xffffffffL
        val ea          = addr + memArg.offset
        atomicCheck(mem, ea, 8)
        val old = loadI64Direct(mem, ea)
        if old == expected then storeI64Direct(mem, ea, replacement)
        pushI64(old)
      case 0x4a =>                                                                          // i32 8-bit
        val replacement = popI32()
        val expected    = popI32()
        val addr        = popI32().toLong & 0xffffffffL
        val ea          = addr + memArg.offset
        atomicCheck(mem, ea, 1)
        val old = loadByteDirect(mem, ea) & 0xff
        if old == (expected & 0xff) then storeByteDirect(mem, ea, replacement & 0xff)
        pushI32(old)
      case 0x4b =>                                                                          // i32 16-bit
        val replacement = popI32()
        val expected    = popI32()
        val addr        = popI32().toLong & 0xffffffffL
        val ea          = addr + memArg.offset
        atomicCheck(mem, ea, 2)
        val old = loadI16Direct(mem, ea) & 0xffff
        if old == (expected & 0xffff) then storeI16Direct(mem, ea, replacement & 0xffff)
        pushI32(old)
      case 0x4c =>                                                                          // i64 8-bit
        val replacement = popI64()
        val expected    = popI64()
        val addr        = popI32().toLong & 0xffffffffL
        val ea          = addr + memArg.offset
        atomicCheck(mem, ea, 1)
        val old = (loadByteDirect(mem, ea) & 0xff).toLong
        if old == (expected & 0xffL) then storeByteDirect(mem, ea, (replacement & 0xffL).toInt)
        pushI64(old)
      case 0x4d =>                                                                          // i64 16-bit
        val replacement = popI64()
        val expected    = popI64()
        val addr        = popI32().toLong & 0xffffffffL
        val ea          = addr + memArg.offset
        atomicCheck(mem, ea, 2)
        val old = (loadI16Direct(mem, ea) & 0xffff).toLong
        if old == (expected & 0xffffL) then storeI16Direct(mem, ea, (replacement & 0xffffL).toInt)
        pushI64(old)
      case 0x4e =>                                                                          // i64 32-bit
        val replacement = popI64()
        val expected    = popI64()
        val addr        = popI32().toLong & 0xffffffffL
        val ea          = addr + memArg.offset
        atomicCheck(mem, ea, 4)
        val old = loadI32Direct(mem, ea).toLong & 0xffffffffL
        if old == (expected & 0xffffffffL) then storeI32Direct(mem, ea, (replacement & 0xffffffffL).toInt)
        pushI64(old)
      case _ => fail(WasmError.UnknownOpcode(0xfe))

  // === atomic-specific helpers ==============================================

  /** Read a memarg whose first byte is at `pos` and advance `f.pc` past
    * it. Same shape as `readMemArgFrame` but parameterised on the start
    * position because 0xFE's sub-opcode is itself a LEB-decoded prefix.
    *
    * Not `inline` — `SimdDispatch` already inlines `Interpreter.readMemArg`
    * via its own helper, and a second inline accessor with the same call
    * signature would collide at the `Interpreter` class merge point with
    * an "inherits conflicting members" error. Per-call overhead is
    * irrelevant compared to interpreter dispatch.
    */
  private def readMemArgAtPos(f: Frame, pos: Int): MemArg =
    Interpreter.readMemArg(f.func.body, pos) match
      case Left(e) => fail(e)
      case Right((memArg, p)) =>
        f.pc = p
        memArg

  /** Alignment + bounds check for one atomic access. The threads proposal
    * mandates the effective address (base + offset) be naturally aligned
    * to the access width — a misaligned access traps. Bounds check
    * happens via the underlying `boundsCheck` helper on the load/store. */
  private inline def atomicCheck(mem: Memory, ea: Long, width: Int): Unit =
    if (ea & (width - 1)) != 0L then fail(WasmError.UnalignedAtomicAccess)
    if ea < 0L || ea + width > mem.data.length then fail(WasmError.MemoryOutOfBounds)

  // Reach into the Interpreter's little-endian load/store helpers for the
  // actual byte work. The bounds check inside each one duplicates the one
  // in `atomicCheck`; that's fine for correctness (the costs are negligible
  // next to interpreter dispatch) and keeps a single byte-order
  // implementation across regular + atomic memory ops.
  private inline def loadByteDirect (mem: Memory, addr: Long): Int  = self.loadByte(mem, addr)
  private inline def storeByteDirect(mem: Memory, addr: Long, v: Int): Unit = self.storeByte(mem, addr, v)
  private inline def loadI16Direct  (mem: Memory, addr: Long): Int  = self.loadI16(mem, addr)
  private inline def storeI16Direct (mem: Memory, addr: Long, v: Int): Unit = self.storeI16(mem, addr, v)
  private inline def loadI32Direct  (mem: Memory, addr: Long): Int  = self.loadI32(mem, addr)
  private inline def storeI32Direct (mem: Memory, addr: Long, v: Int): Unit = self.storeI32(mem, addr, v)
  private inline def loadI64Direct  (mem: Memory, addr: Long): Long = self.loadI64(mem, addr)
  private inline def storeI64Direct (mem: Memory, addr: Long, v: Long): Unit = self.storeI64(mem, addr, v)
