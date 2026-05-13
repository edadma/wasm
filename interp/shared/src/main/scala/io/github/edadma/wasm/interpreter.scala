package io.github.edadma.wasm

import scala.collection.mutable.ArrayBuffer

/** Linear memory — a flat byte array sized in 64KiB pages.
  *
  * The MVP doesn't surface `memory.grow`, so the byte array is allocated
  * once at instantiation and never resized.
  */
final class Memory(initialPages: Int):
  val data: Array[Byte] = new Array[Byte](initialPages * Memory.PageSize)
  def size: Int = data.length

object Memory:
  val PageSize: Int = 65536

/** The interpreter's static and dynamic state, packaged together so the eval
  * loop can be a simple `while` over the current frame.
  *
  * One `Interpreter` corresponds to one in-progress invocation of an exported
  * function. `Runtime.invoke` creates a fresh interpreter per call.
  */
object Interpreter:

  // === Per-function pre-computed control-flow metadata =====================

  enum BlockKind:
    case Block, Loop, If

  /** Branch target / fall-through information for one structured block.
    *
    *   - `bodyStartPC` = first instruction inside the block (used as branch target for `Loop`)
    *   - `endPC` = byte just past the matching `end` (used as branch target for `Block` / `If`,
    *               and as the fall-through position on normal end)
    *   - `elsePC` = byte just past the matching `else` (jumped to on a false `if` condition), or -1
    *   - `resultArity` = 0 or 1, taken from the blocktype byte
    */
  final case class BlockInfo(
      kind: BlockKind,
      bodyStartPC: Int,
      endPC: Int,
      elsePC: Int,
      resultArity: Int,
  )

  /** Lookup table keyed by the PC of the `block`/`loop`/`if` opcode itself. */
  final case class BodyMeta(blocks: Map[Int, BlockInfo])

  // === Resolved (linked) functions =========================================

  sealed trait ResolvedFunc { def signature: FuncType }

  /** A WebAssembly function defined inside the module being instantiated. */
  final case class WasmFunc(
      signature: FuncType,
      /** Total locals including parameters — params come first, declared locals follow. */
      paramCount: Int,
      localCount: Int,
      body: Array[Byte],
      meta: BodyMeta,
  ) extends ResolvedFunc

  /** A function supplied by the host. */
  final case class HostBound(signature: FuncType, fn: HostFunc) extends ResolvedFunc

  // === Pre-compute the matching-end lookup for one body ====================

  /** Scan a function body once and emit a map from each block/loop/if opcode
    * PC to its `BlockInfo`. The scanner walks the byte stream tracking nesting
    * and uses `skipImmediates` to step over operands of plain opcodes.
    *
    * `else` is recorded as the PC of the byte right after the `else` opcode
    * (i.e., where execution resumes when the if-condition was false).
    */
  def computeBodyMeta(body: Array[Byte]): Either[WasmError, BodyMeta] =
    val out = scala.collection.mutable.HashMap.empty[Int, BlockInfo]
    // Open block stack: (opcodePos, kind, bodyStartPC, resultArity, elsePC)
    val stack = ArrayBuffer.empty[(Int, BlockKind, Int, Int, Int)]
    var pc    = 0
    try
      while pc < body.length do
        val op = body(pc) & 0xff
        op match
          case 0x02 | 0x03 | 0x04 =>
            val kind = op match
              case 0x02 => BlockKind.Block
              case 0x03 => BlockKind.Loop
              case _    => BlockKind.If
            val startPC = pc
            val (arity, afterBT) = readBlocktype(body, pc + 1)
            stack += ((startPC, kind, afterBT, arity, -1))
            pc = afterBT
          case 0x05 =>
            // `else` belongs to the topmost open If
            if stack.isEmpty then return Left(WasmError.InvalidModule("`else` outside any block"))
            val (sPC, kind, sBody, arity, _) = stack.last
            if kind != BlockKind.If then
              return Left(WasmError.InvalidModule("`else` matched a non-If block"))
            stack(stack.size - 1) = (sPC, kind, sBody, arity, pc + 1)
            pc += 1
          case 0x0b =>
            if stack.isEmpty then
              // outermost end — function body terminator
              pc += 1
            else
              val (sPC, kind, sBody, arity, ePC) = stack.remove(stack.size - 1)
              out(sPC) = BlockInfo(kind, sBody, pc + 1, ePC, arity)
              pc += 1
          case other =>
            pc = skipImmediates(body, pc, other) match
              case Left(e)  => return Left(e)
              case Right(p) => p
      if stack.nonEmpty then return Left(WasmError.InvalidModule("unterminated structured block"))
      Right(BodyMeta(out.toMap))
    catch
      case _: ArrayIndexOutOfBoundsException =>
        Left(WasmError.InvalidModule("unexpected end of function body"))

  /** Decode a blocktype byte (MVP supports 0x40 empty and 0x7F i32 only).
    * Returns `(resultArity, posAfter)`. */
  private def readBlocktype(body: Array[Byte], pos: Int): (Int, Int) =
    (body(pos) & 0xff) match
      case 0x40 => (0, pos + 1)            // empty
      case 0x7f => (1, pos + 1)            // i32 result
      case b    => throw new RuntimeException(s"unsupported blocktype 0x${b.toHexString}") // TODO: i64/f32/f64/multi-value

  /** Advance past one full instruction (opcode + immediates). Used by the
    * pre-scanner to skip non-structural opcodes when looking for matching ends.
    *
    * Adding a new opcode without updating this table would silently break
    * block-end discovery — keep it in lockstep with the dispatch in `step`.
    */
  private def skipImmediates(body: Array[Byte], pc: Int, op: Int): Either[WasmError, Int] =
    op match
      case 0x00 | 0x01 | 0x0f | 0x1a | 0x1b =>             // unreachable, nop, return, drop, select
        Right(pc + 1)
      case 0x0c | 0x0d | 0x10 | 0x20 | 0x21 | 0x22 =>      // br, br_if, call, local.{get,set,tee}
        Leb128.readU32(body, pc + 1).map(_._2)
      case 0x28 | 0x2c | 0x2d | 0x36 | 0x3a =>             // memory ops with align+offset
        for
          (_, p1) <- Leb128.readU32(body, pc + 1)
          (_, p2) <- Leb128.readU32(body, p1)
        yield p2
      case 0x41 =>                                         // i32.const
        Leb128.readS32(body, pc + 1).map(_._2)
      case b if b >= 0x45 && b <= 0x75 =>                  // i32 unary/binary/compare/shift
        Right(pc + 1)
      case other =>
        Left(WasmError.UnknownOpcode(other))

  // === Runtime state =======================================================

  private final class ExecFail(val err: WasmError) extends RuntimeException(null, null, false, false)

  /** One activation record. `stackBase` is the value-stack height at the
    * moment this frame's first instruction starts; on `return` or normal
    * function exit we trim back to `stackBase + resultArity`. */
  private final class Frame(
      val func: WasmFunc,
      val locals: Array[Value],
      val stackBase: Int,
  ):
    var pc: Int = 0
    val labels: ArrayBuffer[Label] = ArrayBuffer.empty[Label]

  /** Active structured-control entry. `branchArity` is the count of values
    * preserved across a `br`; for `Block`/`If` it equals the block's result
    * arity, for `Loop` it's always 0 in MVP (no loop params). */
  private final case class Label(
      kind: BlockKind,
      targetPC: Int,
      branchArity: Int,
      stackHeight: Int,
  )

end Interpreter

/** Live runtime state for one in-progress invocation. Kept as a class so we
  * can mutate stacks and `pc` cheaply inside the eval loop without threading
  * state through every helper.
  */
final class Interpreter private[wasm] (
    private val funcs: IndexedSeq[Interpreter.ResolvedFunc],
    val memory: Memory,
):
  import Interpreter.*

  private val valueStack = ArrayBuffer.empty[Value]
  private val frames     = ArrayBuffer.empty[Frame]

  // --- entry point ---------------------------------------------------------

  def invoke(funcIdx: Int, args: Seq[Value]): Either[WasmError, Seq[Value]] =
    try
      if funcIdx < 0 || funcIdx >= funcs.length then
        return Left(WasmError.InvalidModule(s"invalid function index $funcIdx"))
      // Push args onto the value stack and call. callFunction will pop them
      // back into locals — this keeps the calling convention uniform between
      // the top-level entry and intra-module `call` instructions.
      args.foreach(valueStack += _)
      callFunction(funcIdx)
      while frames.nonEmpty do step()
      val resultArity = funcs(funcIdx).signature.results.size
      val results     = valueStack.takeRight(resultArity).toSeq
      // Drain whatever's left so a reused interpreter would start clean (defensive).
      valueStack.clear()
      Right(results)
    catch
      case e: ExecFail                       => Left(e.err)
      case _: ArrayIndexOutOfBoundsException => Left(WasmError.InvalidModule("VM ran off end of body"))

  // --- helpers -------------------------------------------------------------

  private inline def fail(err: WasmError): Nothing = throw new ExecFail(err)

  private inline def frame: Frame = frames.last

  private inline def pushI32(v: Int): Unit = valueStack += I32(v)
  private inline def popI32(): Int =
    if valueStack.isEmpty then fail(WasmError.TypeMismatch)
    valueStack.remove(valueStack.size - 1) match
      case I32(v) => v
      // case _      => fail(WasmError.TypeMismatch)  // unreachable in MVP — only i32 values exist

  private inline def popValue(): Value =
    if valueStack.isEmpty then fail(WasmError.TypeMismatch)
    valueStack.remove(valueStack.size - 1)

  /** Execute one opcode of the topmost frame. The dispatch is large but flat
    * — fast inline-friendly code is more valuable here than DRY. */
  private def step(): Unit =
    val f    = frame
    val body = f.func.body
    if f.pc >= body.length then
      // running past the body's end without an explicit `end` is malformed
      fail(WasmError.InvalidModule("missing function-body end"))
    val op = body(f.pc) & 0xff

    op match

      // === control flow ===================================================

      case 0x00 => fail(WasmError.UnreachableExecuted)
      case 0x01 => f.pc += 1                                                              // nop

      case 0x02 | 0x03 | 0x04 =>
        val startPC = f.pc
        val info    = f.func.meta.blocks.getOrElse(startPC, fail(WasmError.InvalidModule(s"no block meta at PC $startPC")))
        op match
          case 0x02 => // block — branch target is after end
            f.labels += Label(BlockKind.Block, info.endPC, info.resultArity, valueStack.size)
            f.pc = info.bodyStartPC
          case 0x03 => // loop — branch target is body start
            f.labels += Label(BlockKind.Loop, info.bodyStartPC, 0, valueStack.size)
            f.pc = info.bodyStartPC
          case _    => // if
            val cond = popI32()
            // When we take a branch we push the If label so the matching `end`
            // (or `else` fall-through) can pop it. When the condition is false
            // *and there is no else branch*, there's no `end` instruction in
            // our path either — so we skip straight past it without pushing.
            if cond != 0 then
              f.labels += Label(BlockKind.If, info.endPC, info.resultArity, valueStack.size)
              f.pc = info.bodyStartPC
            else if info.elsePC >= 0 then
              f.labels += Label(BlockKind.If, info.endPC, info.resultArity, valueStack.size)
              f.pc = info.elsePC
            else
              f.pc = info.endPC

      case 0x05 =>
        // We reached `else` by falling through the true-branch — the if-label
        // is still on the stack and the `end` instruction at the bottom of the
        // else-branch isn't on our path. Pop the label explicitly and resume
        // after the matching end.
        if f.labels.isEmpty then fail(WasmError.InvalidModule("`else` without matching if"))
        val lbl = f.labels.remove(f.labels.size - 1)
        f.pc = lbl.targetPC

      case 0x0b =>                                                                        // end
        if f.labels.nonEmpty then
          f.labels.remove(f.labels.size - 1)
          f.pc += 1
        else
          // function-level end — fall through into return semantics
          returnFromFunction()

      case 0x0c =>                                                                        // br L
        val (n, p) = readU32At(f, f.pc + 1)
        f.pc = p
        branchTo(n)

      case 0x0d =>                                                                        // br_if L
        val (n, p) = readU32At(f, f.pc + 1)
        f.pc = p
        val cond = popI32()
        if cond != 0 then branchTo(n)

      case 0x0f => returnFromFunction()                                                   // return

      case 0x10 =>                                                                        // call funcidx
        val (idx, p) = readU32At(f, f.pc + 1)
        f.pc = p
        callFunction(idx)

      // === parametric ====================================================

      case 0x1a => popValue(); f.pc += 1                                                  // drop
      case 0x1b =>                                                                        // select
        val cond = popI32()
        val b    = popValue()
        val a    = popValue()
        valueStack += (if cond != 0 then a else b)
        f.pc += 1

      // === variables =====================================================

      case 0x20 =>                                                                        // local.get
        val (i, p) = readU32At(f, f.pc + 1)
        f.pc = p
        if i < 0 || i >= f.locals.length then fail(WasmError.InvalidModule(s"local.get $i out of range"))
        valueStack += f.locals(i)

      case 0x21 =>                                                                        // local.set
        val (i, p) = readU32At(f, f.pc + 1)
        f.pc = p
        if i < 0 || i >= f.locals.length then fail(WasmError.InvalidModule(s"local.set $i out of range"))
        f.locals(i) = popValue()

      case 0x22 =>                                                                        // local.tee
        val (i, p) = readU32At(f, f.pc + 1)
        f.pc = p
        if i < 0 || i >= f.locals.length then fail(WasmError.InvalidModule(s"local.tee $i out of range"))
        if valueStack.isEmpty then fail(WasmError.TypeMismatch)
        f.locals(i) = valueStack.last

      // === memory ========================================================

      case 0x28 =>                                                                        // i32.load
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushI32(loadI32(addr + offset))

      case 0x2c =>                                                                        // i32.load8_s
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushI32(loadByte(addr + offset).toByte.toInt)                                     // sign-extend

      case 0x2d =>                                                                        // i32.load8_u
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushI32(loadByte(addr + offset) & 0xff)                                           // zero-extend

      case 0x36 =>                                                                        // i32.store
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val v    = popI32()
        val addr = popI32().toLong & 0xffffffffL
        storeI32(addr + offset, v)

      case 0x3a =>                                                                        // i32.store8
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val v    = popI32()
        val addr = popI32().toLong & 0xffffffffL
        storeByte(addr + offset, v & 0xff)

      // === i32 numeric ===================================================

      case 0x41 =>                                                                        // i32.const
        val (v, p) = Leb128.readS32(body, f.pc + 1) match
          case Right(t) => t
          case Left(e)  => fail(e)
        f.pc = p
        pushI32(v)

      case 0x45 => unop(x => if x == 0 then 1 else 0); f.pc += 1                          // i32.eqz
      case 0x46 => binop((a, b) => if a == b then 1 else 0); f.pc += 1                    // i32.eq
      case 0x47 => binop((a, b) => if a != b then 1 else 0); f.pc += 1                    // i32.ne
      case 0x48 => binop((a, b) => if a <  b then 1 else 0); f.pc += 1                    // i32.lt_s
      case 0x4a => binop((a, b) => if a >  b then 1 else 0); f.pc += 1                    // i32.gt_s
      case 0x4c => binop((a, b) => if a <= b then 1 else 0); f.pc += 1                    // i32.le_s
      case 0x4e => binop((a, b) => if a >= b then 1 else 0); f.pc += 1                    // i32.ge_s

      case 0x6a => binop(_ + _);  f.pc += 1                                               // i32.add
      case 0x6b => binop(_ - _);  f.pc += 1                                               // i32.sub
      case 0x6c => binop(_ * _);  f.pc += 1                                               // i32.mul

      case 0x6d =>                                                                        // i32.div_s
        val b = popI32(); val a = popI32()
        if b == 0 then fail(WasmError.InvalidModule("integer divide by zero"))
        if a == Int.MinValue && b == -1 then fail(WasmError.InvalidModule("integer overflow in div_s"))
        pushI32(a / b); f.pc += 1

      case 0x6f =>                                                                        // i32.rem_s
        val b = popI32(); val a = popI32()
        if b == 0 then fail(WasmError.InvalidModule("integer divide by zero"))
        // WASM: rem_s for MIN_INT % -1 is defined as 0 (no trap, despite Java's behaviour).
        pushI32(if a == Int.MinValue && b == -1 then 0 else a % b); f.pc += 1

      case 0x71 => binop(_ & _); f.pc += 1                                                // i32.and
      case 0x72 => binop(_ | _); f.pc += 1                                                // i32.or
      case 0x73 => binop(_ ^ _); f.pc += 1                                                // i32.xor
      case 0x74 => binop((a, b) => a << (b & 31)); f.pc += 1                              // i32.shl
      case 0x75 => binop((a, b) => a >> (b & 31)); f.pc += 1                              // i32.shr_s
      // TODO: i32.shr_u (0x76), i32.rotl/rotr, i32.clz/ctz/popcnt — leave space for the rest of the i32 op set.

      // === unsupported ===================================================

      // TODO: i64.* (0x42, 0x50–0x6E shifted forms, 0x7C–0xA6) — extend popValue and add I64 variant.
      // TODO: f32/f64 — different push/pop discipline.
      // TODO: 0x11 call_indirect — needs tables.
      // TODO: 0x3F memory.size, 0x40 memory.grow.
      case other => fail(WasmError.UnknownOpcode(other))

  // === Control-flow helpers ===============================================

  private def branchTo(n: Int): Unit =
    val f       = frame
    val nLabels = f.labels.size
    if n > nLabels then
      fail(WasmError.InvalidModule(s"branch index $n out of range (have $nLabels labels)"))
    if n == nLabels then
      // br to the implicit function-level label — equivalent to `return`
      returnFromFunction()
    else
      val target = f.labels(nLabels - 1 - n)
      // Save the values that the branch carries (= branchArity).
      val saved = new Array[Value](target.branchArity)
      var k     = target.branchArity - 1
      while k >= 0 do
        if valueStack.isEmpty then fail(WasmError.TypeMismatch)
        saved(k) = valueStack.remove(valueStack.size - 1)
        k -= 1
      // Clear back to the label's entry stack height.
      while valueStack.size > target.stackHeight do valueStack.remove(valueStack.size - 1)
      // Restore the carried values.
      var j = 0
      while j < saved.length do { valueStack += saved(j); j += 1 }
      // Pop labels above and including the target.
      var pops = n + 1
      while pops > 0 do { f.labels.remove(f.labels.size - 1); pops -= 1 }
      // Re-enter loops by re-pushing the label (loops branch to their start).
      if target.kind == BlockKind.Loop then f.labels += target
      f.pc = target.targetPC

  private def returnFromFunction(): Unit =
    val f       = frame
    val arity   = f.func.signature.results.size
    val results = new Array[Value](arity)
    var k       = arity - 1
    while k >= 0 do
      if valueStack.isEmpty then fail(WasmError.TypeMismatch)
      results(k) = valueStack.remove(valueStack.size - 1)
      k -= 1
    while valueStack.size > f.stackBase do valueStack.remove(valueStack.size - 1)
    var j = 0
    while j < results.length do { valueStack += results(j); j += 1 }
    frames.remove(frames.size - 1)

  // === call ================================================================

  /** Push a new frame for `funcIdx`, popping its argument count of values
    * from the value stack into the new frame's locals. Host functions are
    * invoked synchronously here and don't get a frame of their own. */
  private def callFunction(funcIdx: Int): Unit =
    if funcIdx < 0 || funcIdx >= funcs.length then
      fail(WasmError.InvalidModule(s"invalid function index $funcIdx"))
    funcs(funcIdx) match
      case HostBound(sig, fn) =>
        val n = sig.params.size
        if valueStack.size < n then fail(WasmError.TypeMismatch)
        val args = new Array[Value](n)
        var k    = n - 1
        while k >= 0 do { args(k) = valueStack.remove(valueStack.size - 1); k -= 1 }
        val results = fn(memory, args.toSeq)
        if results.size != sig.results.size then fail(WasmError.TypeMismatch)
        results.foreach(valueStack += _)

      case wf @ WasmFunc(sig, paramCount, localCount, _, _) =>
        if valueStack.size < paramCount then fail(WasmError.TypeMismatch)
        val locals = new Array[Value](localCount)
        // Pop params right-to-left so locals[0..paramCount-1] hold them in declared order.
        var k = paramCount - 1
        while k >= 0 do { locals(k) = valueStack.remove(valueStack.size - 1); k -= 1 }
        // Declared locals zero-initialize. For MVP everything is i32 → all I32(0).
        var j = paramCount
        while j < localCount do { locals(j) = I32(0); j += 1 }
        frames += new Frame(wf, locals, stackBase = valueStack.size)

  // === memory access ======================================================

  private inline def boundsCheck(addr: Long, n: Int): Unit =
    if addr < 0 || addr + n > memory.data.length then fail(WasmError.MemoryOutOfBounds)

  private def loadByte(addr: Long): Int =
    boundsCheck(addr, 1)
    memory.data(addr.toInt) & 0xff

  private def storeByte(addr: Long, v: Int): Unit =
    boundsCheck(addr, 1)
    memory.data(addr.toInt) = v.toByte

  /** Little-endian 32-bit load. */
  private def loadI32(addr: Long): Int =
    boundsCheck(addr, 4)
    val a = addr.toInt
    val d = memory.data
    (d(a) & 0xff) | ((d(a + 1) & 0xff) << 8) | ((d(a + 2) & 0xff) << 16) | ((d(a + 3) & 0xff) << 24)

  private def storeI32(addr: Long, v: Int): Unit =
    boundsCheck(addr, 4)
    val a = addr.toInt
    val d = memory.data
    d(a)     = (v         & 0xff).toByte
    d(a + 1) = ((v >>>  8) & 0xff).toByte
    d(a + 2) = ((v >>> 16) & 0xff).toByte
    d(a + 3) = ((v >>> 24) & 0xff).toByte

  // === misc helpers =======================================================

  private inline def readU32At(f: Frame, pos: Int): (Int, Int) =
    Leb128.readU32(f.func.body, pos) match
      case Right(t) => t
      case Left(e)  => fail(e)

  private inline def binop(op: (Int, Int) => Int): Unit =
    val b = popI32(); val a = popI32(); pushI32(op(a, b))

  private inline def unop(op: Int => Int): Unit =
    val a = popI32(); pushI32(op(a))
