package io.github.edadma.wasm

import scala.collection.mutable.ArrayBuffer
import java.lang as jl  // for Long.divideUnsigned / rotateLeft / numberOfLeadingZeros etc.

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
      /** Type of each local slot, indexed 0..localCount-1. Used at call time to
        * zero-initialize declared locals with the right `Value` variant. */
      localTypes: Vector[ValueType],
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
            readBlocktype(body, pc + 1) match
              case Left(e) => return Left(e)
              case Right((arity, afterBT)) =>
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

  /** Decode a blocktype byte (i32 / i64 / f32 / f64 result; empty for no
    * result). Returns `Right((resultArity, posAfter))`, or `Left(InvalidModule)`
    * for the multi-value (s33 typeidx) form the current subset doesn't model
    * yet. With f64 supported, every scalar blocktype is now accepted. */
  private def readBlocktype(body: Array[Byte], pos: Int): Either[WasmError, (Int, Int)] =
    (body(pos) & 0xff) match
      case 0x40 => Right((0, pos + 1))            // empty
      case 0x7f => Right((1, pos + 1))            // i32 result
      case 0x7e => Right((1, pos + 1))            // i64 result
      case 0x7d => Right((1, pos + 1))            // f32 result
      case 0x7c => Right((1, pos + 1))            // f64 result
      // TODO: multi-value (s33 type index).
      case b    => Left(WasmError.InvalidModule(s"unsupported blocktype 0x${b.toHexString}"))

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
      case 0x28 | 0x29 | 0x2a | 0x2b |                     // i32.load, i64.load, f32.load, f64.load
           0x2c | 0x2d |                                   // i32.load8_s/u
           0x30 | 0x31 | 0x32 | 0x33 | 0x34 | 0x35 |       // i64.load{8,16,32}_{s,u}
           0x36 | 0x37 | 0x38 | 0x39 |                     // i32.store, i64.store, f32.store, f64.store
           0x3a | 0x3c | 0x3d | 0x3e =>                    // i32.store8 / i64.store{8,16,32}
        for
          (_, p1) <- Leb128.readU32(body, pc + 1)          // align
          (_, p2) <- Leb128.readU32(body, p1)              // offset
        yield p2
      case 0x41 =>                                         // i32.const
        Leb128.readS32(body, pc + 1).map(_._2)
      case 0x42 =>                                         // i64.const (SLEB64 immediate)
        Leb128.readS64(body, pc + 1).map(_._2)
      case 0x43 =>                                         // f32.const (4 raw little-endian bytes — NOT LEB)
        if pc + 5 > body.length then Left(WasmError.InvalidModule("truncated f32.const immediate"))
        else Right(pc + 5)
      case 0x44 =>                                         // f64.const (8 raw little-endian bytes — NOT LEB)
        if pc + 9 > body.length then Left(WasmError.InvalidModule("truncated f64.const immediate"))
        else Right(pc + 9)
      // 0x45–0x75 covers every i32 unary/binary/compare/shift AND the i64
      // comparisons (0x50–0x5A); none take immediates. f32/f64 comparisons
      // (0x5B–0x66) also live in here, harmless to skip-past since they
      // likewise take no immediates — `step` is the gate on what's executable.
      case b if b >= 0x45 && b <= 0x75 =>
        Right(pc + 1)
      // 0x79–0xA6: i64 unary + i64 numeric/bitwise/shift/rotate (0x79–0x8A),
      // f32 unary + f32 numeric/min/max/copysign (0x8B–0x98), and f64 unary
      // + f64 numeric (0x99–0xA6). All single-byte.
      case b if b >= 0x79 && b <= 0xa6 =>
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

  private inline def pushI32(v: Int): Unit    = valueStack += I32(v)
  private inline def pushI64(v: Long): Unit   = valueStack += I64(v)
  private inline def pushF32(v: Float): Unit  = valueStack += F32(v)
  private inline def pushF64(v: Double): Unit = valueStack += F64(v)

  private inline def popI32(): Int =
    if valueStack.isEmpty then fail(WasmError.TypeMismatch)
    valueStack.remove(valueStack.size - 1) match
      case I32(v) => v
      case _      => fail(WasmError.TypeMismatch)

  private inline def popI64(): Long =
    if valueStack.isEmpty then fail(WasmError.TypeMismatch)
    valueStack.remove(valueStack.size - 1) match
      case I64(v) => v
      case _      => fail(WasmError.TypeMismatch)

  private inline def popF32(): Float =
    if valueStack.isEmpty then fail(WasmError.TypeMismatch)
    valueStack.remove(valueStack.size - 1) match
      case F32(v) => v
      case _      => fail(WasmError.TypeMismatch)

  private inline def popF64(): Double =
    if valueStack.isEmpty then fail(WasmError.TypeMismatch)
    valueStack.remove(valueStack.size - 1) match
      case F64(v) => v
      case _      => fail(WasmError.TypeMismatch)

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

      // === i64 memory ====================================================
      //
      // All i64 memory accesses follow the same align+offset immediate
      // pattern as i32 — only the access width and sign-extension differ.

      case 0x29 =>                                                                        // i64.load (8 bytes)
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushI64(loadI64(addr + offset))

      case 0x30 =>                                                                        // i64.load8_s
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushI64(loadByte(addr + offset).toByte.toLong)                                    // sign-extend

      case 0x31 =>                                                                        // i64.load8_u
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushI64((loadByte(addr + offset) & 0xff).toLong)

      case 0x32 =>                                                                        // i64.load16_s
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushI64(loadI16(addr + offset).toLong)                                            // already sign-extended

      case 0x33 =>                                                                        // i64.load16_u
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushI64((loadI16(addr + offset) & 0xffff).toLong)

      case 0x34 =>                                                                        // i64.load32_s
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushI64(loadI32(addr + offset).toLong)                                            // sign-extend

      case 0x35 =>                                                                        // i64.load32_u
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushI64(loadI32(addr + offset).toLong & 0xffffffffL)

      case 0x37 =>                                                                        // i64.store (8 bytes)
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        storeI64(addr + offset, v)

      case 0x3c =>                                                                        // i64.store8 — low 8 bits
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        storeByte(addr + offset, (v & 0xffL).toInt)

      case 0x3d =>                                                                        // i64.store16 — low 16 bits
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        storeI16(addr + offset, (v & 0xffffL).toInt)

      case 0x3e =>                                                                        // i64.store32 — low 32 bits
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        storeI32(addr + offset, v.toInt)

      // === i64 numeric ===================================================

      case 0x42 =>                                                                        // i64.const
        val (v, p) = Leb128.readS64(body, f.pc + 1) match
          case Right(t) => t
          case Left(e)  => fail(e)
        f.pc = p
        pushI64(v)

      case 0x50 => unop64Test(_ == 0L); f.pc += 1                                         // i64.eqz
      case 0x51 => binop64Test(_ == _);                       f.pc += 1                   // i64.eq
      case 0x52 => binop64Test(_ != _);                       f.pc += 1                   // i64.ne
      case 0x53 => binop64Test(_ <  _);                       f.pc += 1                   // i64.lt_s
      case 0x54 => binop64Test((a, b) => jl.Long.compareUnsigned(a, b) <  0); f.pc += 1   // i64.lt_u
      case 0x55 => binop64Test(_ >  _);                       f.pc += 1                   // i64.gt_s
      case 0x56 => binop64Test((a, b) => jl.Long.compareUnsigned(a, b) >  0); f.pc += 1   // i64.gt_u
      case 0x57 => binop64Test(_ <= _);                       f.pc += 1                   // i64.le_s
      case 0x58 => binop64Test((a, b) => jl.Long.compareUnsigned(a, b) <= 0); f.pc += 1   // i64.le_u
      case 0x59 => binop64Test(_ >= _);                       f.pc += 1                   // i64.ge_s
      case 0x5a => binop64Test((a, b) => jl.Long.compareUnsigned(a, b) >= 0); f.pc += 1   // i64.ge_u

      case 0x79 => unop64(v => jl.Long.numberOfLeadingZeros(v).toLong);  f.pc += 1        // i64.clz
      case 0x7a => unop64(v => jl.Long.numberOfTrailingZeros(v).toLong); f.pc += 1        // i64.ctz
      case 0x7b => unop64(v => jl.Long.bitCount(v).toLong);             f.pc += 1         // i64.popcnt

      case 0x7c => binop64(_ + _); f.pc += 1                                              // i64.add
      case 0x7d => binop64(_ - _); f.pc += 1                                              // i64.sub
      case 0x7e => binop64(_ * _); f.pc += 1                                              // i64.mul

      case 0x7f =>                                                                        // i64.div_s
        val b = popI64(); val a = popI64()
        if b == 0L then fail(WasmError.InvalidModule("integer divide by zero"))
        if a == Long.MinValue && b == -1L then fail(WasmError.InvalidModule("integer overflow in div_s"))
        pushI64(a / b); f.pc += 1

      case 0x80 =>                                                                        // i64.div_u
        val b = popI64(); val a = popI64()
        if b == 0L then fail(WasmError.InvalidModule("integer divide by zero"))
        pushI64(jl.Long.divideUnsigned(a, b)); f.pc += 1

      case 0x81 =>                                                                        // i64.rem_s
        val b = popI64(); val a = popI64()
        if b == 0L then fail(WasmError.InvalidModule("integer divide by zero"))
        // WASM rem_s for MIN_LONG % -1 is 0 (does NOT trap, unlike Java).
        pushI64(if a == Long.MinValue && b == -1L then 0L else a % b); f.pc += 1

      case 0x82 =>                                                                        // i64.rem_u
        val b = popI64(); val a = popI64()
        if b == 0L then fail(WasmError.InvalidModule("integer divide by zero"))
        pushI64(jl.Long.remainderUnsigned(a, b)); f.pc += 1

      case 0x83 => binop64(_ & _); f.pc += 1                                              // i64.and
      case 0x84 => binop64(_ | _); f.pc += 1                                              // i64.or
      case 0x85 => binop64(_ ^ _); f.pc += 1                                              // i64.xor
      case 0x86 => binop64((a, b) => a << (b & 63L).toInt); f.pc += 1                     // i64.shl
      case 0x87 => binop64((a, b) => a >> (b & 63L).toInt); f.pc += 1                     // i64.shr_s
      case 0x88 => binop64((a, b) => a >>> (b & 63L).toInt); f.pc += 1                    // i64.shr_u
      case 0x89 => binop64((a, b) => jl.Long.rotateLeft (a, (b & 63L).toInt)); f.pc += 1  // i64.rotl
      case 0x8a => binop64((a, b) => jl.Long.rotateRight(a, (b & 63L).toInt)); f.pc += 1  // i64.rotr

      // === f32 memory ====================================================

      case 0x2a =>                                                                        // f32.load (4 bytes IEEE-754)
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushF32(loadF32(addr + offset))

      case 0x38 =>                                                                        // f32.store
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val v    = popF32()
        val addr = popI32().toLong & 0xffffffffL
        storeF32(addr + offset, v)

      // === f32 numeric ===================================================

      case 0x43 =>                                                                        // f32.const — 4 raw LE bytes (NOT LEB)
        if f.pc + 5 > body.length then fail(WasmError.InvalidModule("truncated f32.const immediate"))
        val bits = (body(f.pc + 1) & 0xff)          |
                   ((body(f.pc + 2) & 0xff) <<  8)  |
                   ((body(f.pc + 3) & 0xff) << 16)  |
                   ((body(f.pc + 4) & 0xff) << 24)
        pushF32(jl.Float.intBitsToFloat(bits))
        f.pc += 5

      // f32 ordered comparisons (0x5B–0x60). Scala's float operators already
      // return `false` for any NaN-involving compare except `!=`, which is
      // exactly what WASM specifies — no special-casing needed here.

      case 0x5b => binopF32Test(_ == _); f.pc += 1                                        // f32.eq
      case 0x5c => binopF32Test(_ != _); f.pc += 1                                        // f32.ne
      case 0x5d => binopF32Test(_ <  _); f.pc += 1                                        // f32.lt
      case 0x5e => binopF32Test(_ >  _); f.pc += 1                                        // f32.gt
      case 0x5f => binopF32Test(_ <= _); f.pc += 1                                        // f32.le
      case 0x60 => binopF32Test(_ >= _); f.pc += 1                                        // f32.ge

      // f32 unary (0x8B–0x91). Java's `Math.{abs, floor, ceil, rint, sqrt}` all
      // follow IEEE-754. `nearest` is round-half-to-even — `Math.rint` is the
      // double-precision version; widening via `toDouble` and narrowing back
      // is safe for all finite f32 values. `trunc` (round-toward-zero) isn't
      // in `Math`, so we synthesise it from floor/ceil; this preserves the
      // sign of zeros and the NaN/Inf behaviour both branches inherit.

      case 0x8b => unopF32(v => jl.Math.abs(v));        f.pc += 1                         // f32.abs
      case 0x8c => unopF32(v => -v);                    f.pc += 1                         // f32.neg
      case 0x8d => unopF32(v => jl.Math.ceil (v.toDouble).toFloat); f.pc += 1             // f32.ceil
      case 0x8e => unopF32(v => jl.Math.floor(v.toDouble).toFloat); f.pc += 1             // f32.floor
      case 0x8f => unopF32(v =>                                                            // f32.trunc — round toward zero
        if jl.Float.isNaN(v) || jl.Float.isInfinite(v) then v
        else if v < 0.0f then jl.Math.ceil(v.toDouble).toFloat
        else jl.Math.floor(v.toDouble).toFloat); f.pc += 1
      case 0x90 => unopF32(v => jl.Math.rint(v.toDouble).toFloat);  f.pc += 1             // f32.nearest (round half to even)
      case 0x91 => unopF32(v => jl.Math.sqrt(v.toDouble).toFloat);  f.pc += 1             // f32.sqrt

      // f32 binary (0x92–0x98). `+/-/*` and `/` are Scala primitives — already
      // IEEE-754. `min`/`max` go through `jl.Math.min`/`max`, which handle
      // NaN (returns NaN) and signed zeros (min(-0,+0) == -0) per IEEE-754,
      // matching WASM. `copysign` uses `Math.copySign`.

      case 0x92 => binopF32(_ + _);                                f.pc += 1              // f32.add
      case 0x93 => binopF32(_ - _);                                f.pc += 1              // f32.sub
      case 0x94 => binopF32(_ * _);                                f.pc += 1              // f32.mul
      case 0x95 => binopF32(_ / _);                                f.pc += 1              // f32.div (no trap — returns Inf/NaN)
      case 0x96 => binopF32((a, b) => jl.Math.min     (a, b));     f.pc += 1              // f32.min
      case 0x97 => binopF32((a, b) => jl.Math.max     (a, b));     f.pc += 1              // f32.max
      case 0x98 => binopF32((a, b) => jl.Math.copySign(a, b));     f.pc += 1              // f32.copysign

      // === f64 memory ====================================================

      case 0x2b =>                                                                        // f64.load (8 bytes IEEE-754)
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val addr = popI32().toLong & 0xffffffffL
        pushF64(loadF64(addr + offset))

      case 0x39 =>                                                                        // f64.store
        val (_, p1)      = readU32At(f, f.pc + 1)
        val (offset, p2) = readU32At(f, p1)
        f.pc = p2
        val v    = popF64()
        val addr = popI32().toLong & 0xffffffffL
        storeF64(addr + offset, v)

      // === f64 numeric ===================================================

      case 0x44 =>                                                                        // f64.const — 8 raw LE bytes (NOT LEB)
        if f.pc + 9 > body.length then fail(WasmError.InvalidModule("truncated f64.const immediate"))
        val bits =
          (body(f.pc + 1) & 0xffL)         |
          ((body(f.pc + 2) & 0xffL) <<  8) |
          ((body(f.pc + 3) & 0xffL) << 16) |
          ((body(f.pc + 4) & 0xffL) << 24) |
          ((body(f.pc + 5) & 0xffL) << 32) |
          ((body(f.pc + 6) & 0xffL) << 40) |
          ((body(f.pc + 7) & 0xffL) << 48) |
          ((body(f.pc + 8) & 0xffL) << 56)
        pushF64(jl.Double.longBitsToDouble(bits))
        f.pc += 9

      // f64 ordered comparisons (0x61–0x66). Same NaN-rules story as f32 —
      // Scala's `<`, `<=`, `>`, `>=`, `==` return false against NaN; `!=`
      // returns true. Matches WASM's ordered-compare semantics exactly.

      case 0x61 => binopF64Test(_ == _); f.pc += 1                                        // f64.eq
      case 0x62 => binopF64Test(_ != _); f.pc += 1                                        // f64.ne
      case 0x63 => binopF64Test(_ <  _); f.pc += 1                                        // f64.lt
      case 0x64 => binopF64Test(_ >  _); f.pc += 1                                        // f64.gt
      case 0x65 => binopF64Test(_ <= _); f.pc += 1                                        // f64.le
      case 0x66 => binopF64Test(_ >= _); f.pc += 1                                        // f64.ge

      // f64 unary (0x99–0x9F). `Math.{abs, floor, ceil, rint, sqrt}` all
      // operate natively on Double — no widen/narrow dance needed (unlike
      // f32). `trunc` (round-toward-zero) is again synthesised from
      // floor/ceil; NaN/Inf pass through, sign of zero preserved.

      case 0x99 => unopF64(v => jl.Math.abs(v));                   f.pc += 1              // f64.abs
      case 0x9a => unopF64(v => -v);                               f.pc += 1              // f64.neg
      case 0x9b => unopF64(v => jl.Math.ceil (v));                 f.pc += 1              // f64.ceil
      case 0x9c => unopF64(v => jl.Math.floor(v));                 f.pc += 1              // f64.floor
      case 0x9d => unopF64(v =>                                                            // f64.trunc — round toward zero
        if jl.Double.isNaN(v) || jl.Double.isInfinite(v) then v
        else if v < 0.0 then jl.Math.ceil(v)
        else jl.Math.floor(v));                                    f.pc += 1
      case 0x9e => unopF64(v => jl.Math.rint(v));                  f.pc += 1              // f64.nearest (round half to even)
      case 0x9f => unopF64(v => jl.Math.sqrt(v));                  f.pc += 1              // f64.sqrt

      // f64 binary (0xA0–0xA6). Same shapes as f32, just at Double width.

      case 0xa0 => binopF64(_ + _);                                f.pc += 1              // f64.add
      case 0xa1 => binopF64(_ - _);                                f.pc += 1              // f64.sub
      case 0xa2 => binopF64(_ * _);                                f.pc += 1              // f64.mul
      case 0xa3 => binopF64(_ / _);                                f.pc += 1              // f64.div (no trap — returns Inf/NaN)
      case 0xa4 => binopF64((a, b) => jl.Math.min     (a, b));     f.pc += 1              // f64.min
      case 0xa5 => binopF64((a, b) => jl.Math.max     (a, b));     f.pc += 1              // f64.max
      case 0xa6 => binopF64((a, b) => jl.Math.copySign(a, b));     f.pc += 1              // f64.copysign

      // === unsupported ===================================================

      // TODO: conversions (0xA7–0xC4) — Phase 1.4.
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

      case wf @ WasmFunc(_, paramCount, localCount, localTypes, _, _) =>
        if valueStack.size < paramCount then fail(WasmError.TypeMismatch)
        val locals = new Array[Value](localCount)
        // Pop params right-to-left so locals[0..paramCount-1] hold them in declared order.
        var k = paramCount - 1
        while k >= 0 do { locals(k) = valueStack.remove(valueStack.size - 1); k -= 1 }
        // Declared locals zero-initialize. Pick the right `Value` variant for
        // each slot — `I32(0)` for i32, `I64(0L)` for i64, `F32(0.0f)` for
        // f32, `F64(0.0)` for f64. (WASM mandates positive zero for floats;
        // Java's `0.0f`/`0.0` defaults match.)
        var j = paramCount
        while j < localCount do
          locals(j) = localTypes(j) match
            case ValueType.I32Type => I32(0)
            case ValueType.I64Type => I64(0L)
            case ValueType.F32Type => F32(0.0f)
            case ValueType.F64Type => F64(0.0)
          j += 1
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

  /** Little-endian 16-bit load — returns a sign-extended Int. Callers that
    * want the zero-extended form mask with `0xffff` themselves. */
  private def loadI16(addr: Long): Int =
    boundsCheck(addr, 2)
    val a = addr.toInt
    val d = memory.data
    val raw = (d(a) & 0xff) | ((d(a + 1) & 0xff) << 8)
    (raw << 16) >> 16 // sign-extend the 16-bit value into an Int

  private def storeI16(addr: Long, v: Int): Unit =
    boundsCheck(addr, 2)
    val a = addr.toInt
    val d = memory.data
    d(a)     = (v         & 0xff).toByte
    d(a + 1) = ((v >>> 8) & 0xff).toByte

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

  /** Little-endian 64-bit load. */
  private def loadI64(addr: Long): Long =
    boundsCheck(addr, 8)
    val a = addr.toInt
    val d = memory.data
    (d(a)     & 0xffL)        |
    ((d(a + 1) & 0xffL) <<  8) |
    ((d(a + 2) & 0xffL) << 16) |
    ((d(a + 3) & 0xffL) << 24) |
    ((d(a + 4) & 0xffL) << 32) |
    ((d(a + 5) & 0xffL) << 40) |
    ((d(a + 6) & 0xffL) << 48) |
    ((d(a + 7) & 0xffL) << 56)

  private def storeI64(addr: Long, v: Long): Unit =
    boundsCheck(addr, 8)
    val a = addr.toInt
    val d = memory.data
    d(a)     = ( v         & 0xffL).toByte
    d(a + 1) = ((v >>>  8) & 0xffL).toByte
    d(a + 2) = ((v >>> 16) & 0xffL).toByte
    d(a + 3) = ((v >>> 24) & 0xffL).toByte
    d(a + 4) = ((v >>> 32) & 0xffL).toByte
    d(a + 5) = ((v >>> 40) & 0xffL).toByte
    d(a + 6) = ((v >>> 48) & 0xffL).toByte
    d(a + 7) = ((v >>> 56) & 0xffL).toByte

  /** Little-endian 32-bit IEEE-754 load. Uses the *raw* conversion so NaN
    * payloads survive a round-trip through memory. */
  private def loadF32(addr: Long): Float =
    jl.Float.intBitsToFloat(loadI32(addr))

  private def storeF32(addr: Long, v: Float): Unit =
    storeI32(addr, jl.Float.floatToRawIntBits(v))

  /** Little-endian 64-bit IEEE-754 load. Same NaN-payload-preserving raw
    * conversion as the f32 path, just at Double width. */
  private def loadF64(addr: Long): Double =
    jl.Double.longBitsToDouble(loadI64(addr))

  private def storeF64(addr: Long, v: Double): Unit =
    storeI64(addr, jl.Double.doubleToRawLongBits(v))

  // === misc helpers =======================================================

  private inline def readU32At(f: Frame, pos: Int): (Int, Int) =
    Leb128.readU32(f.func.body, pos) match
      case Right(t) => t
      case Left(e)  => fail(e)

  private inline def binop(op: (Int, Int) => Int): Unit =
    val b = popI32(); val a = popI32(); pushI32(op(a, b))

  private inline def unop(op: Int => Int): Unit =
    val a = popI32(); pushI32(op(a))

  private inline def binop64(op: (Long, Long) => Long): Unit =
    val b = popI64(); val a = popI64(); pushI64(op(a, b))

  private inline def unop64(op: Long => Long): Unit =
    val a = popI64(); pushI64(op(a))

  /** i64 comparison — pops two i64 values, pushes an i32 (1 if true, 0 otherwise). */
  private inline def binop64Test(op: (Long, Long) => Boolean): Unit =
    val b = popI64(); val a = popI64(); pushI32(if op(a, b) then 1 else 0)

  /** i64.eqz — pops one i64, pushes i32 1/0 by predicate. */
  private inline def unop64Test(op: Long => Boolean): Unit =
    val a = popI64(); pushI32(if op(a) then 1 else 0)

  private inline def binopF32(op: (Float, Float) => Float): Unit =
    val b = popF32(); val a = popF32(); pushF32(op(a, b))

  private inline def unopF32(op: Float => Float): Unit =
    val a = popF32(); pushF32(op(a))

  /** f32 comparison — pops two f32s, pushes i32 1/0. Scala's `<`, `<=`, `>`,
    * `>=`, `==` already return `false` against NaN (and `!=` returns `true`),
    * which matches WASM's ordered-compare semantics exactly. */
  private inline def binopF32Test(op: (Float, Float) => Boolean): Unit =
    val b = popF32(); val a = popF32(); pushI32(if op(a, b) then 1 else 0)

  private inline def binopF64(op: (Double, Double) => Double): Unit =
    val b = popF64(); val a = popF64(); pushF64(op(a, b))

  private inline def unopF64(op: Double => Double): Unit =
    val a = popF64(); pushF64(op(a))

  /** f64 comparison — pops two f64s, pushes i32 1/0. Same NaN semantics as
    * the f32 form. */
  private inline def binopF64Test(op: (Double, Double) => Boolean): Unit =
    val b = popF64(); val a = popF64(); pushI32(if op(a, b) then 1 else 0)
