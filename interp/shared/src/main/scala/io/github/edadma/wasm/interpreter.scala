package io.github.edadma.wasm

import scala.collection.mutable.ArrayBuffer
import java.lang as jl  // for Long.divideUnsigned / rotateLeft / numberOfLeadingZeros etc.

/** Linear memory — a flat byte array sized in 64KiB pages.
  *
  * Resizable via `memory.grow`: `data` is replaced with a fresh, larger
  * array on every successful grow, with the old contents copied over. All
  * memory access in the interpreter chases through `memory.data` per call
  * (never caching the array reference past a helper), so grow is safe
  * mid-execution. `currentPages` is the live page count; tracking it
  * separately makes `memory.size` O(1) and keeps the page accounting
  * independent of `data.length` (which is also exact, but expressed in
  * bytes — keeping the names parallel to the spec opcodes wins).
  *
  * `maxPages` is the module-declared upper bound (None when omitted in the
  * binary). `grow` honours it on top of the JVM/spec implicit cap at
  * `Int.MaxValue` bytes (≈ 32767 pages). On failure it returns `-1`
  * verbatim — that's the spec's signal for "grow failed", *not* a trap.
  */
final class Memory(initialPages: Int, val maxPages: Option[Int] = None):
  var data: Array[Byte] = new Array[Byte](initialPages * Memory.PageSize)
  var currentPages: Int = initialPages
  def size: Int = data.length

  /** Grow the memory by `delta` 64KiB pages. Returns the previous page
    * count on success (the spec's contract), or -1 if the grow would
    * exceed the declared `maxPages` or the implicit `Int.MaxValue`-bytes
    * platform cap.
    *
    * `delta == 0` is permitted and is a no-op that still returns the
    * current page count — modules use it as a probe for "how big is
    * memory right now" relative to a known prior anchor.
    */
  def grow(delta: Int): Int =
    if delta < 0 then return -1
    val prev = currentPages
    val newPages = prev + delta
    // Overflow guard: arithmetic above can wrap negative on huge `delta`.
    if newPages < prev then return -1
    if maxPages.exists(newPages > _) then return -1
    val newBytesL = newPages.toLong * Memory.PageSize
    if newBytesL > Int.MaxValue then return -1
    // delta == 0 is a no-op, but the spec still requires returning prev.
    if delta != 0 then
      val newData = new Array[Byte](newBytesL.toInt)
      System.arraycopy(data, 0, newData, 0, data.length)
      data = newData
      currentPages = newPages
    prev

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
    *   - `paramArity` = number of values popped from the stack at block entry (always 0 for the
    *                    MVP inline blocktypes — only the multi-value typeidx form has > 0)
    *   - `resultArity` = number of values produced at block end / fall-through; carried across
    *                     `br` for Block/If (loops use `paramArity` as the branch arity instead)
    */
  final case class BlockInfo(
      kind: BlockKind,
      bodyStartPC: Int,
      endPC: Int,
      elsePC: Int,
      paramArity:  Int,
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

  // === Phase 8.D: multi-memory memarg ======================================

  /** One memarg immediate for a load/store opcode. The reference-types-era
    * encoding extends the MVP `(align, offset)` pair with an optional
    * memidx, signalled by bit 6 of the alignment LEB:
    *
    *   align-LEB-byte0 & 0x40  == 0 → MVP: just align + offset; memIdx = 0.
    *   align-LEB-byte0 & 0x40  != 0 → bit 6 is a "memidx-present" flag;
    *                                  alignment value is the LEB with that
    *                                  bit cleared, then a memidx LEB
    *                                  follows, then offset.
    *
    * The interpreter ignores `align` (alignment is advisory) — only
    * `memIdx` + `offset` matter at run time. */
  final case class MemArg(memIdx: Int, align: Int, offset: Int)

  /** Read a memarg starting at `pc`. Returns `(MemArg, posAfter)`. The
    * shape is identical for every load + store opcode, so threading this
    * through `step` keeps the per-opcode code tiny. */
  private[wasm] def readMemArg(body: Array[Byte], pc: Int): Either[WasmError, (MemArg, Int)] =
    Leb128.readU32(body, pc) match
      case Left(e) => Left(e)
      case Right((alignFlag, p1)) =>
        val memidxFlag = (alignFlag & 0x40) != 0
        val align      = if memidxFlag then alignFlag & ~0x40 else alignFlag
        val memStep: Either[WasmError, (Int, Int)] =
          if memidxFlag then Leb128.readU32(body, p1) else Right((0, p1))
        memStep match
          case Left(e)             => Left(e)
          case Right((memIdx, p2)) =>
            Leb128.readU32(body, p2) match
              case Left(e)             => Left(e)
              case Right((offset, p3)) => Right((MemArg(memIdx, align, offset), p3))

  // === Pre-compute the matching-end lookup for one body ====================

  /** Scan a function body once and emit a map from each block/loop/if opcode
    * PC to its `BlockInfo`. The scanner walks the byte stream tracking nesting
    * and uses `skipImmediates` to step over operands of plain opcodes.
    *
    * `else` is recorded as the PC of the byte right after the `else` opcode
    * (i.e., where execution resumes when the if-condition was false).
    *
    * `types` is threaded in so the multi-value typeidx form of blocktype can
    * be resolved during pre-scan — the param/result arities are baked into
    * each `BlockInfo` once and the runtime never has to decode them again.
    */
  def computeBodyMeta(body: Array[Byte], types: Vector[FuncType]): Either[WasmError, BodyMeta] =
    val out = scala.collection.mutable.HashMap.empty[Int, BlockInfo]
    // Open block stack: (opcodePos, kind, bodyStartPC, paramArity, resultArity, elsePC)
    val stack = ArrayBuffer.empty[(Int, BlockKind, Int, Int, Int, Int)]
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
            readBlocktype(body, pc + 1, types) match
              case Left(e) => return Left(e)
              case Right((BlockSig(params, results), afterBT)) =>
                stack += ((startPC, kind, afterBT, params, results, -1))
                pc = afterBT
          case 0x05 =>
            // `else` belongs to the topmost open If
            if stack.isEmpty then return Left(WasmError.InvalidModule("`else` outside any block"))
            val (sPC, kind, sBody, params, results, _) = stack.last
            if kind != BlockKind.If then
              return Left(WasmError.InvalidModule("`else` matched a non-If block"))
            stack(stack.size - 1) = (sPC, kind, sBody, params, results, pc + 1)
            pc += 1
          case 0x0b =>
            if stack.isEmpty then
              // outermost end — function body terminator
              pc += 1
            else
              val (sPC, kind, sBody, params, results, ePC) = stack.remove(stack.size - 1)
              out(sPC) = BlockInfo(kind, sBody, pc + 1, ePC, params, results)
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

  /** Param + result arity of one structured block. Used by the pre-scan to
    * bake a block's signature into its [[BlockInfo]] without the runtime
    * needing to re-decode the blocktype byte on entry. */
  final case class BlockSig(paramArity: Int, resultArity: Int)

  /** Decode a blocktype, returning `(BlockSig, posAfter)`. Three encodings,
    * disambiguated by the first byte:
    *
    *   - `0x40` → empty (no params, no results)
    *   - one of `0x7F` / `0x7E` / `0x7D` / `0x7C` → no params, one result
    *     of the named scalar type (the MVP inline form)
    *   - anything else → a signed-LEB128 typeidx (the multi-value form);
    *     the resulting value must be non-negative and index into `types`,
    *     and the block's params/results are copied from that `FuncType`.
    *
    * The inline-byte values are chosen so they all parse as *negative*
    * SLEB128 numbers (sign bit set on a single-byte read), which is why
    * the typeidx form unambiguously takes the "otherwise" branch even
    * though it overlaps the same byte space — typeidx 0 encodes as
    * `0x00`, never as `0x40` or `0x7C..0x7F`.
    *
    * Package-private (`private[wasm]`) so [[Validator]] can share the
    * decode: validation needs the resolved `FuncType` to type-check the
    * block's params + results, and re-implementing this disambiguation
    * would invite a parser/validator drift on future blocktype additions. */
  private[wasm] def readBlocktype(body: Array[Byte], pos: Int,
                                  types: Vector[FuncType]): Either[WasmError, (BlockSig, Int)] =
    if pos >= body.length then Left(WasmError.InvalidModule("EOF in blocktype"))
    else (body(pos) & 0xff) match
      case 0x40 => Right((BlockSig(0, 0), pos + 1))            // empty
      case 0x7f => Right((BlockSig(0, 1), pos + 1))            // i32 result
      case 0x7e => Right((BlockSig(0, 1), pos + 1))            // i64 result
      case 0x7d => Right((BlockSig(0, 1), pos + 1))            // f32 result
      case 0x7c => Right((BlockSig(0, 1), pos + 1))            // f64 result
      // Phase 8.C: reftype-valued blocktypes — `(block (result funcref))`
      // and `(block (result externref))` are both legal.
      case 0x70 => Right((BlockSig(0, 1), pos + 1))            // funcref result
      case 0x6f => Right((BlockSig(0, 1), pos + 1))            // externref result
      case _ =>
        // Multi-value form: signed LEB128 typeidx (the spec says s33;
        // readS32 is sufficient because any plausible typeidx fits well
        // inside i32 range). A negative value here is malformed.
        Leb128.readS32(body, pos) match
          case Left(e) => Left(e)
          case Right((idx, np)) =>
            if idx < 0 then
              Left(WasmError.InvalidModule(s"blocktype: negative typeidx $idx"))
            else if idx >= types.length then
              Left(WasmError.InvalidModule(
                s"blocktype: typeidx $idx out of range (have ${types.length} types)"))
            else
              val ft = types(idx)
              Right((BlockSig(ft.params.size, ft.results.size), np))

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
      case 0x0c | 0x0d | 0x10 |                            // br, br_if, call
           0x20 | 0x21 | 0x22 |                            // local.{get,set,tee}
           0x23 | 0x24 |                                   // global.{get,set}
           0x25 | 0x26 =>                                  // table.get, table.set (Phase 8.C)
        Leb128.readU32(body, pc + 1).map(_._2)
      // Phase 8.C: reference-typed ops.
      //   0xD0 ref.null    — single reftype byte (0x70 / 0x6F).
      //   0xD1 ref.is_null — no immediates.
      //   0xD2 ref.func    — funcidx LEB.
      case 0xd0 =>                                         // ref.null reftype
        if pc + 2 > body.length then
          Left(WasmError.InvalidModule(s"truncated ref.null reftype at $pc"))
        else Right(pc + 2)
      case 0xd1 =>                                         // ref.is_null
        Right(pc + 1)
      case 0xd2 =>                                         // ref.func funcidx
        Leb128.readU32(body, pc + 1).map(_._2)
      case 0x0e =>                                         // br_table — vec(labelidx) + default labelidx
        // vec(u32) count, then `count` LEB u32 entries, then one more u32
        // for the default label. Each LEB u32 is variable-length, so this
        // must walk one at a time.
        Leb128.readU32(body, pc + 1) match
          case Left(e)            => Left(e)
          case Right((count, p0)) =>
            if count < 0 then Left(WasmError.InvalidModule(s"br_table: negative vec count $count"))
            else
              var i  = 0
              var p  = p0
              var er: WasmError = null
              while i < count && er == null do
                Leb128.readU32(body, p) match
                  case Left(e)         => er = e
                  case Right((_, np))  => p = np
                i += 1
              if er != null then Left(er)
              else Leb128.readU32(body, p).map(_._2)            // default labelidx
      case 0x11 =>                                         // call_indirect — typeidx, tableidx
        for
          (_, p1) <- Leb128.readU32(body, pc + 1)
          (_, p2) <- Leb128.readU32(body, p1)
        yield p2
      case 0x28 | 0x29 | 0x2a | 0x2b |                     // i32.load, i64.load, f32.load, f64.load
           0x2c | 0x2d | 0x2e | 0x2f |                     // i32.load8_s/u, i32.load16_s/u
           0x30 | 0x31 | 0x32 | 0x33 | 0x34 | 0x35 |       // i64.load{8,16,32}_{s,u}
           0x36 | 0x37 | 0x38 | 0x39 |                     // i32.store, i64.store, f32.store, f64.store
           0x3a | 0x3b | 0x3c | 0x3d | 0x3e =>             // i32.store8/16, i64.store{8,16,32}
        // Phase 8.D: memarg may carry a memidx (bit 6 of the align LEB).
        // readMemArg handles both shapes.
        readMemArg(body, pc + 1).map(_._2)
      case 0x3f | 0x40 =>                                  // memory.size / memory.grow
        // Phase 8.D: the slot that was a "must-be-zero reserved byte"
        // in the MVP is now a memidx LEB. For single-memory modules
        // it's still always 0x00, but we read it as a u32 LEB so any
        // memidx value parses cleanly.
        Leb128.readU32(body, pc + 1).map(_._2)
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
      // 0x45–0x78 covers every i32 unary/binary/compare/shift/rotate AND
      // the i64 comparisons (0x50–0x5A); none take immediates. f32/f64
      // comparisons (0x5B–0x66) also live in here, harmless to skip-past
      // since they likewise take no immediates — `step` is the gate on
      // what's executable. Upper bound bumped from 0x75 → 0x78 in Phase
      // 1.5 (shr_u / rotl / rotr).
      case b if b >= 0x45 && b <= 0x78 =>
        Right(pc + 1)
      // 0x79–0xBF: i64 unary + i64 numeric/bitwise/shift/rotate (0x79–0x8A),
      // f32 unary + f32 numeric/min/max/copysign (0x8B–0x98), f64 unary
      // + f64 numeric (0x99–0xA6), and every numeric conversion opcode
      // (0xA7–0xBF: wrap/extend/trunc/convert/demote/promote/reinterpret).
      // 0xC0–0xC4: the sign-extension proposal (i32.extend8_s / 16_s,
      // i64.extend8_s / 16_s / 32_s). All single-byte.
      case b if b >= 0x79 && b <= 0xc4 =>
        Right(pc + 1)
      case 0xfc =>
        // 0xFC is a multibyte-opcode prefix; the sub-opcode is a LEB u32.
        // Sub 0..7: trunc_sat proposal — single-LEB encoding, no further
        // immediates (Phase 8.A).
        // Sub 8..14: bulk-memory + table proposal (Phase 8.B added 8/9/12/13/14
        // alongside 7.B's 10/11). Immediate layout per spec:
        //   sub  8 (memory.init): dataidx LEB + memidx-reserved-byte (0x00)
        //   sub  9 (data.drop):   dataidx LEB
        //   sub 10 (memory.copy): two memidx reserved bytes (dst, src)
        //   sub 11 (memory.fill): memidx reserved byte
        //   sub 12 (table.init):  elemidx LEB + tableidx LEB
        //   sub 13 (elem.drop):   elemidx LEB
        //   sub 14 (table.copy):  dst tableidx LEB + src tableidx LEB
        // Sub ≥15 is reserved space; surfaces as UnknownOpcode(0xFC).
        Leb128.readU32(body, pc + 1) match
          case Left(e)          => Left(e)
          case Right((sub, p1)) =>
            sub match
              case s if s >= 0 && s <= 7 =>                                    // i32/i64.trunc_sat_{f32,f64}_{s,u}
                Right(p1)
              case 8 =>                                                        // memory.init dataidx, memidx
                // Phase 8.D: second immediate is a memidx LEB (was a
                // must-be-zero reserved byte pre-multi-memory).
                Leb128.readU32(body, p1) match
                  case Left(e)            => Left(e)
                  case Right((_, p2)) =>
                    Leb128.readU32(body, p2) match
                      case Left(e)        => Left(e)
                      case Right((_, p3)) => Right(p3)
              case 9 =>                                                        // data.drop dataidx
                Leb128.readU32(body, p1) match
                  case Left(e)      => Left(e)
                  case Right((_, p2)) => Right(p2)
              case 10 =>                                                       // memory.copy dst-memidx, src-memidx
                Leb128.readU32(body, p1) match
                  case Left(e)            => Left(e)
                  case Right((_, p2)) =>
                    Leb128.readU32(body, p2) match
                      case Left(e)        => Left(e)
                      case Right((_, p3)) => Right(p3)
              case 11 =>                                                       // memory.fill memidx
                Leb128.readU32(body, p1) match
                  case Left(e)      => Left(e)
                  case Right((_, p2)) => Right(p2)
              case 12 =>                                                       // table.init elemidx, tableidx
                Leb128.readU32(body, p1) match
                  case Left(e)            => Left(e)
                  case Right((_, p2)) =>
                    Leb128.readU32(body, p2) match
                      case Left(e)        => Left(e)
                      case Right((_, p3)) => Right(p3)
              case 13 =>                                                       // elem.drop elemidx
                Leb128.readU32(body, p1) match
                  case Left(e)      => Left(e)
                  case Right((_, p2)) => Right(p2)
              case 14 =>                                                       // table.copy dst-tableidx, src-tableidx
                Leb128.readU32(body, p1) match
                  case Left(e)            => Left(e)
                  case Right((_, p2)) =>
                    Leb128.readU32(body, p2) match
                      case Left(e)        => Left(e)
                      case Right((_, p3)) => Right(p3)
              // Phase 8.C: ref-typed table ops, all with a single tableidx
              // LEB immediate (the new size/grow/fill triple).
              case 15 | 16 | 17 =>                                             // table.grow / table.size / table.fill
                Leb128.readU32(body, p1) match
                  case Left(e)      => Left(e)
                  case Right((_, p2)) => Right(p2)
              case _  => Left(WasmError.UnknownOpcode(0xfc))
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
    * preserved across a `br`; for `Block`/`If` it equals the block's
    * `resultArity`, for `Loop` it equals the block's `paramArity` (the
    * loop's params are what's re-fed into the next iteration). `stackHeight`
    * is the value-stack height *below* the block's params — so on entry,
    * the params are above `stackHeight` and a `br` correctly trims past
    * them before re-pushing the carry. */
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
    /** Phase 8.D: linear memories indexed by memidx. The MVP single-memory
      * shape just makes this an `Array[Memory]` of length 1; multi-memory
      * modules carry one entry per declared memory. Load/store paths read
      * `memories(memArg.memIdx)` per opcode. */
    private val memories: Array[Memory],
    /** Module-instance globals (shared across calls — that persistence is
      * the whole point of globals). The interpreter mutates entries in
      * place on `global.set`. */
    private val globals: Array[Value],
    /** Parallel to `globals` — true if the corresponding slot is `var`,
      * false if `const`. `global.set` traps if the bit is false. */
    private val globalMutable: Array[Boolean],
    /** One [[RuntimeTable]] per declared table; each carries a typed
      * `Array[Value]` plus its refType and max. Phase 8.C surfaces
      * `table.set`, `table.grow`, `table.fill`, `table.get`, and
      * `table.size`, all of which mutate or read this array; the table
      * is shared across `invoke` calls. */
    private val tables: Array[RuntimeTable],
    /** Module function-type vector — `call_indirect`'s dynamic signature
      * check resolves the static typeidx immediate against this. */
    private val types: Vector[FuncType],
    // Phase 8.B: bulk-memory state. `dataBytes(i)` is segment `i`'s original
    // byte payload (active or passive); `dataDropped(i)` is the drop flag —
    // active segments start dropped (their bytes already landed in memory at
    // instantiation), passive ones start undropped until `data.drop`. Same
    // shape for elements: `elemRefs(i)` is the original ref vector (Phase
    // 8.C: `RefNull` / `RefFunc` / `RefExtern` values, not bare funcidxs),
    // `elemDropped(i)` the drop flag (active + declarative start dropped,
    // passive starts undropped).
    private val dataBytes:   Array[Array[Byte]],
    private val dataDropped: Array[Boolean],
    private val elemRefs:    Array[Vector[Value]],
    private val elemDropped: Array[Boolean],
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
        // `baseHeight` is the value-stack height below the block's params.
        // Params stay on the stack (they're the block's input "operand
        // frame"); the label records this base so a branch can trim past
        // them and re-push the carry (results for Block/If, params for Loop).
        val baseHeight = valueStack.size - info.paramArity
        op match
          case 0x02 => // block — branch target is after end; carry results on br
            f.labels += Label(BlockKind.Block, info.endPC, info.resultArity, baseHeight)
            f.pc = info.bodyStartPC
          case 0x03 => // loop — branch target is body start; carry params on br
            f.labels += Label(BlockKind.Loop, info.bodyStartPC, info.paramArity, baseHeight)
            f.pc = info.bodyStartPC
          case _    => // if
            val cond = popI32()
            // The if's condition was popped above, so `baseHeight` computed
            // before that pop is now stale by 1 — but we recompute against
            // the post-pop stack to get the right base for the *body*'s
            // operand frame. When we take a branch we push the If label so
            // the matching `end` (or `else` fall-through) can pop it; when
            // the condition is false and there's no else branch there's no
            // `end` on our path, so we skip straight past it without pushing.
            val ifBase = valueStack.size - info.paramArity
            if cond != 0 then
              f.labels += Label(BlockKind.If, info.endPC, info.resultArity, ifBase)
              f.pc = info.bodyStartPC
            else if info.elsePC >= 0 then
              f.labels += Label(BlockKind.If, info.endPC, info.resultArity, ifBase)
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

      case 0x0e =>                                                                        // br_table L* L_default
        // vec(labelidx) count, then `count` LEB u32 entries, then one more
        // u32 for the default. Pop an i32 selector; if it's in [0, count)
        // branch to vec(selector) else branch to default.
        val (count, p0) = readU32At(f, f.pc + 1)
        if count < 0 then fail(WasmError.InvalidModule(s"br_table: negative vec count $count"))
        val targets = new Array[Int](count)
        var i  = 0
        var pp = p0
        while i < count do
          val (lbl, np) = readU32At(f, pp)
          targets(i) = lbl
          pp = np
          i += 1
        val (dflt, pend) = readU32At(f, pp)
        f.pc = pend
        val sel = popI32()
        // Spec semantics: index is unsigned. A negative sel (sign-bit set)
        // falls through to the default branch.
        val branchN =
          if sel >= 0 && sel < count then targets(sel) else dflt
        branchTo(branchN)

      case 0x0f => returnFromFunction()                                                   // return

      case 0x10 =>                                                                        // call funcidx
        val (idx, p) = readU32At(f, f.pc + 1)
        f.pc = p
        callFunction(idx)

      case 0x11 =>                                                                        // call_indirect typeidx tableidx
        // Two LEB u32 immediates: the declared function type and the table
        // to dispatch through. The stack carries an i32 slot index; the slot
        // must be in-range, hold a non-null funcref, and the slot's
        // signature must match `types(typeidx)` exactly. The validator
        // already rejected externref-typed tables here, so the slot is
        // guaranteed to be `RefNull(FuncRef)` or `RefFunc(_)`.
        val (typeIdx, p1)  = readU32At(f, f.pc + 1)
        val (tableIdx, p2) = readU32At(f, p1)
        f.pc = p2
        if typeIdx < 0 || typeIdx >= types.length then
          fail(WasmError.InvalidModule(s"call_indirect: invalid type index $typeIdx"))
        if tableIdx < 0 || tableIdx >= tables.length then
          fail(WasmError.InvalidModule(s"call_indirect: invalid table index $tableIdx"))
        val tab  = tables(tableIdx)
        val slot = popI32()
        if slot < 0 || slot >= tab.size then
          fail(WasmError.InvalidModule(s"call_indirect: index $slot out of table bounds (size ${tab.size})"))
        tab.slots(slot) match
          case RefFunc(fi) =>
            val expected = types(typeIdx)
            val actual   = funcs(fi).signature
            if expected != actual then
              fail(WasmError.InvalidModule(
                s"call_indirect: signature mismatch at index $slot (expected $expected, got $actual)"))
            callFunction(fi)
          case RefNull(_) =>
            fail(WasmError.InvalidModule(s"call_indirect: null funcref at index $slot"))
          case other =>
            // Externref / non-ref types are caught at validation; defensive.
            fail(WasmError.InvalidModule(s"call_indirect: non-funcref slot at index $slot: $other"))

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

      case 0x23 =>                                                                        // global.get
        val (i, p) = readU32At(f, f.pc + 1)
        f.pc = p
        if i < 0 || i >= globals.length then fail(WasmError.InvalidModule(s"global.get $i out of range"))
        valueStack += globals(i)

      case 0x24 =>                                                                        // global.set
        val (i, p) = readU32At(f, f.pc + 1)
        f.pc = p
        if i < 0 || i >= globals.length then fail(WasmError.InvalidModule(s"global.set $i out of range"))
        if !globalMutable(i) then fail(WasmError.InvalidModule(s"global.set on immutable global $i"))
        if valueStack.isEmpty then fail(WasmError.TypeMismatch)
        // Spec validates types statically (Phase 6). Here we accept whatever's
        // on the stack — a mistyped store would be caught by the operator
        // that reads the global next, and Phase 6's validator will lift this
        // into a structural error.
        globals(i) = valueStack.remove(valueStack.size - 1)

      // === memory ========================================================

      case 0x28 =>                                                                        // i32.load
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI32(loadI32(mem, addr + memArg.offset))

      case 0x2c =>                                                                        // i32.load8_s
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI32(loadByte(mem, addr + memArg.offset).toByte.toInt)                                     // sign-extend

      case 0x2d =>                                                                        // i32.load8_u
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI32(loadByte(mem, addr + memArg.offset) & 0xff)                                           // zero-extend

      case 0x36 =>                                                                        // i32.store
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val v    = popI32()
        val addr = popI32().toLong & 0xffffffffL
        storeI32(mem, addr + memArg.offset, v)

      case 0x3a =>                                                                        // i32.store8
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val v    = popI32()
        val addr = popI32().toLong & 0xffffffffL
        storeByte(mem, addr + memArg.offset, v & 0xff)

      case 0x2e =>                                                                        // i32.load16_s
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI32(loadI16(mem, addr + memArg.offset))                                                   // already sign-extended by loadI16

      case 0x2f =>                                                                        // i32.load16_u
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI32(loadI16(mem, addr + memArg.offset) & 0xffff)                                          // mask off the sign-extension

      case 0x3b =>                                                                        // i32.store16 — low 16 bits
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val v    = popI32()
        val addr = popI32().toLong & 0xffffffffL
        storeI16(mem, addr + memArg.offset, v & 0xffff)

      case 0x3f =>                                                                        // memory.size memidx
        // Phase 8.D: the byte that was a must-be-zero reserved slot in the
        // MVP is now a memidx LEB. Single-memory modules still encode 0x00
        // (one LEB byte = 0) and read the only memory; multi-memory
        // modules can target memidx 1, 2, ... here.
        val mem = readMemIdxMemory(f)
        pushI32(mem.currentPages)

      case 0x40 =>                                                                        // memory.grow memidx
        val mem   = readMemIdxMemory(f)
        val delta = popI32()
        pushI32(mem.grow(delta))                                                          // -1 on failure (NOT a trap)

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
      case 0x49 => binop((a, b) => if jl.Integer.compareUnsigned(a, b) <  0 then 1 else 0); f.pc += 1 // i32.lt_u
      case 0x4a => binop((a, b) => if a >  b then 1 else 0); f.pc += 1                    // i32.gt_s
      case 0x4b => binop((a, b) => if jl.Integer.compareUnsigned(a, b) >  0 then 1 else 0); f.pc += 1 // i32.gt_u
      case 0x4c => binop((a, b) => if a <= b then 1 else 0); f.pc += 1                    // i32.le_s
      case 0x4d => binop((a, b) => if jl.Integer.compareUnsigned(a, b) <= 0 then 1 else 0); f.pc += 1 // i32.le_u
      case 0x4e => binop((a, b) => if a >= b then 1 else 0); f.pc += 1                    // i32.ge_s
      case 0x4f => binop((a, b) => if jl.Integer.compareUnsigned(a, b) >= 0 then 1 else 0); f.pc += 1 // i32.ge_u

      // i32 bit counting (mirrors the i64 forms at 0x79–0x7B). Result is
      // i32, not i64, so we go through `unop` rather than `unop64`-style
      // helpers. Spec defines clz/ctz of 0 as the operand width (32 here).
      case 0x67 => unop(a => jl.Integer.numberOfLeadingZeros(a));  f.pc += 1              // i32.clz
      case 0x68 => unop(a => jl.Integer.numberOfTrailingZeros(a)); f.pc += 1              // i32.ctz
      case 0x69 => unop(a => jl.Integer.bitCount(a));              f.pc += 1              // i32.popcnt

      case 0x6a => binop(_ + _);  f.pc += 1                                               // i32.add
      case 0x6b => binop(_ - _);  f.pc += 1                                               // i32.sub
      case 0x6c => binop(_ * _);  f.pc += 1                                               // i32.mul

      case 0x6d =>                                                                        // i32.div_s
        val b = popI32(); val a = popI32()
        if b == 0 then fail(WasmError.InvalidModule("integer divide by zero"))
        if a == Int.MinValue && b == -1 then fail(WasmError.InvalidModule("integer overflow in div_s"))
        pushI32(a / b); f.pc += 1

      case 0x6e =>                                                                        // i32.div_u
        val b = popI32(); val a = popI32()
        if b == 0 then fail(WasmError.InvalidModule("integer divide by zero"))
        pushI32(jl.Integer.divideUnsigned(a, b)); f.pc += 1

      case 0x6f =>                                                                        // i32.rem_s
        val b = popI32(); val a = popI32()
        if b == 0 then fail(WasmError.InvalidModule("integer divide by zero"))
        // WASM: rem_s for MIN_INT % -1 is defined as 0 (no trap, despite Java's behaviour).
        pushI32(if a == Int.MinValue && b == -1 then 0 else a % b); f.pc += 1

      case 0x70 =>                                                                        // i32.rem_u
        val b = popI32(); val a = popI32()
        if b == 0 then fail(WasmError.InvalidModule("integer divide by zero"))
        pushI32(jl.Integer.remainderUnsigned(a, b)); f.pc += 1

      case 0x71 => binop(_ & _); f.pc += 1                                                // i32.and
      case 0x72 => binop(_ | _); f.pc += 1                                                // i32.or
      case 0x73 => binop(_ ^ _); f.pc += 1                                                // i32.xor
      case 0x74 => binop((a, b) => a << (b & 31)); f.pc += 1                              // i32.shl
      case 0x75 => binop((a, b) => a >> (b & 31)); f.pc += 1                              // i32.shr_s
      case 0x76 => binop((a, b) => a >>> (b & 31)); f.pc += 1                             // i32.shr_u
      case 0x77 => binop((a, b) => jl.Integer.rotateLeft (a, b & 31)); f.pc += 1          // i32.rotl
      case 0x78 => binop((a, b) => jl.Integer.rotateRight(a, b & 31)); f.pc += 1          // i32.rotr

      // === i64 memory ====================================================
      //
      // All i64 memory accesses follow the same align+offset immediate
      // pattern as i32 — only the access width and sign-extension differ.

      case 0x29 =>                                                                        // i64.load (8 bytes)
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI64(loadI64(mem, addr + memArg.offset))

      case 0x30 =>                                                                        // i64.load8_s
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI64(loadByte(mem, addr + memArg.offset).toByte.toLong)                                    // sign-extend

      case 0x31 =>                                                                        // i64.load8_u
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI64((loadByte(mem, addr + memArg.offset) & 0xff).toLong)

      case 0x32 =>                                                                        // i64.load16_s
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI64(loadI16(mem, addr + memArg.offset).toLong)                                            // already sign-extended

      case 0x33 =>                                                                        // i64.load16_u
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI64((loadI16(mem, addr + memArg.offset) & 0xffff).toLong)

      case 0x34 =>                                                                        // i64.load32_s
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI64(loadI32(mem, addr + memArg.offset).toLong)                                            // sign-extend

      case 0x35 =>                                                                        // i64.load32_u
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushI64(loadI32(mem, addr + memArg.offset).toLong & 0xffffffffL)

      case 0x37 =>                                                                        // i64.store (8 bytes)
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        storeI64(mem, addr + memArg.offset, v)

      case 0x3c =>                                                                        // i64.store8 — low 8 bits
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        storeByte(mem, addr + memArg.offset, (v & 0xffL).toInt)

      case 0x3d =>                                                                        // i64.store16 — low 16 bits
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        storeI16(mem, addr + memArg.offset, (v & 0xffffL).toInt)

      case 0x3e =>                                                                        // i64.store32 — low 32 bits
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val v    = popI64()
        val addr = popI32().toLong & 0xffffffffL
        storeI32(mem, addr + memArg.offset, v.toInt)

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
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushF32(loadF32(mem, addr + memArg.offset))

      case 0x38 =>                                                                        // f32.store
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val v    = popF32()
        val addr = popI32().toLong & 0xffffffffL
        storeF32(mem, addr + memArg.offset, v)

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
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val addr = popI32().toLong & 0xffffffffL
        pushF64(loadF64(mem, addr + memArg.offset))

      case 0x39 =>                                                                        // f64.store
        val memArg = readMemArgFrame(f)
        val mem    = memArgMemory(memArg)
        val v    = popF64()
        val addr = popI32().toLong & 0xffffffffL
        storeF64(mem, addr + memArg.offset, v)

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

      // === conversions (Phase 1.4) =======================================
      //
      // Three flavours, all single-byte opcodes, all popping one operand and
      // pushing one of a different type (or same bits reinterpreted):
      //
      //   * integer-only (wrap_i64, extend_i32_{s,u}) — no traps;
      //   * float→int trunc — TRAPS on NaN / ±Inf / out-of-range (post-trunc);
      //   * int→float convert — no trap, but may round (precision loss);
      //   * f32⇄f64 demote/promote — no trap;
      //   * reinterpret — pure bit-cast, never inspects the value.
      //
      // Boundary checks for trunc are written in the FLOAT space (against
      // exact-power-of-two limits like 2^31, 2^32, 2^63, 2^64), all of which
      // are representable in both Float and Double. The comparisons evaluate
      // to false for NaN so the explicit NaN check is needed first.

      case 0xa7 =>                                                                          // i32.wrap_i64
        val v = popI64()
        pushI32(v.toInt)
        f.pc += 1

      case 0xa8 =>                                                                          // i32.trunc_f32_s
        val v = popF32()
        if jl.Float.isNaN(v) then fail(WasmError.InvalidModule("trunc: NaN"))
        if v < -2147483648.0f || v >= 2147483648.0f then fail(WasmError.InvalidModule("i32.trunc_f32_s: out of range"))
        pushI32(v.toInt)
        f.pc += 1

      case 0xa9 =>                                                                          // i32.trunc_f32_u
        val v = popF32()
        if jl.Float.isNaN(v) then fail(WasmError.InvalidModule("trunc: NaN"))
        if v <= -1.0f || v >= 4294967296.0f then fail(WasmError.InvalidModule("i32.trunc_f32_u: out of range"))
        pushI32(v.toLong.toInt)
        f.pc += 1

      case 0xaa =>                                                                          // i32.trunc_f64_s
        val v = popF64()
        if jl.Double.isNaN(v) then fail(WasmError.InvalidModule("trunc: NaN"))
        if v < -2147483648.0 || v >= 2147483648.0 then fail(WasmError.InvalidModule("i32.trunc_f64_s: out of range"))
        pushI32(v.toInt)
        f.pc += 1

      case 0xab =>                                                                          // i32.trunc_f64_u
        val v = popF64()
        if jl.Double.isNaN(v) then fail(WasmError.InvalidModule("trunc: NaN"))
        if v <= -1.0 || v >= 4294967296.0 then fail(WasmError.InvalidModule("i32.trunc_f64_u: out of range"))
        pushI32(v.toLong.toInt)
        f.pc += 1

      case 0xac =>                                                                          // i64.extend_i32_s
        val v = popI32()
        pushI64(v.toLong)
        f.pc += 1

      case 0xad =>                                                                          // i64.extend_i32_u
        val v = popI32()
        pushI64(v.toLong & 0xffffffffL)
        f.pc += 1

      // i64.trunc_f32_s: signed range is [-2^63, 2^63). 2^63 as Float rounds
      // to exactly 9223372036854775808.0f (the next representable float above
      // 9223372036854774784.0, the largest in-range value). So `>= 2^63f`
      // rejects 2^63 and everything above; `< -2^63f` rejects -2^63 - ulp
      // and below. The boundary value -2^63 itself is in range and produces
      // Long.MinValue.
      case 0xae =>                                                                          // i64.trunc_f32_s
        val v = popF32()
        if jl.Float.isNaN(v) then fail(WasmError.InvalidModule("trunc: NaN"))
        if v < -9223372036854775808.0f || v >= 9223372036854775808.0f then
          fail(WasmError.InvalidModule("i64.trunc_f32_s: out of range"))
        pushI64(v.toLong)
        f.pc += 1

      // i64.trunc_f32_u: unsigned range is [0, 2^64). `v.toLong` doesn't
      // cover the [2^63, 2^64) range correctly (Java clamps to Long.MaxValue
      // for Float→Long when the float exceeds Long range). Use the
      // bit-splice trick: shift the high half into the low half via a
      // subtraction by 2^63, convert, then OR back the sign bit.
      case 0xaf =>                                                                          // i64.trunc_f32_u
        val v = popF32()
        if jl.Float.isNaN(v) then fail(WasmError.InvalidModule("trunc: NaN"))
        if v <= -1.0f || v >= 18446744073709551616.0f then
          fail(WasmError.InvalidModule("i64.trunc_f32_u: out of range"))
        val result =
          if v < 9223372036854775808.0f then v.toLong
          else (v - 9223372036854775808.0f).toLong | Long.MinValue
        pushI64(result)
        f.pc += 1

      case 0xb0 =>                                                                          // i64.trunc_f64_s
        val v = popF64()
        if jl.Double.isNaN(v) then fail(WasmError.InvalidModule("trunc: NaN"))
        if v < -9223372036854775808.0 || v >= 9223372036854775808.0 then
          fail(WasmError.InvalidModule("i64.trunc_f64_s: out of range"))
        pushI64(v.toLong)
        f.pc += 1

      case 0xb1 =>                                                                          // i64.trunc_f64_u
        val v = popF64()
        if jl.Double.isNaN(v) then fail(WasmError.InvalidModule("trunc: NaN"))
        if v <= -1.0 || v >= 18446744073709551616.0 then
          fail(WasmError.InvalidModule("i64.trunc_f64_u: out of range"))
        val result =
          if v < 9223372036854775808.0 then v.toLong
          else (v - 9223372036854775808.0).toLong | Long.MinValue
        pushI64(result)
        f.pc += 1

      // Int→float convert: no traps, but a Long → Float may lose precision
      // (Float has 24 bits of mantissa; Long has 64). Spec says round to
      // nearest, ties to even — which is what Java's primitive cast does.

      case 0xb2 =>                                                                          // f32.convert_i32_s
        val v = popI32()
        pushF32(v.toFloat)
        f.pc += 1

      case 0xb3 =>                                                                          // f32.convert_i32_u
        val v = popI32()
        pushF32((v.toLong & 0xffffffffL).toFloat)
        f.pc += 1

      case 0xb4 =>                                                                          // f32.convert_i64_s
        val v = popI64()
        pushF32(v.toFloat)
        f.pc += 1

      // f32.convert_i64_u: Java's Long→Float is signed. For unsigned values
      // with the high bit set, halve, convert, double, and add the low bit
      // back. The `(a >>> 1) | (a & 1L)` trick preserves the rounding parity
      // by OR-ing in the low bit before the halve drops it — without that,
      // `(a >>> 1).toFloat * 2.0f` would round half-to-even incorrectly for
      // unsigned-Long values that fall on a tie between two floats.
      case 0xb5 =>                                                                          // f32.convert_i64_u
        val v = popI64()
        if v >= 0L then pushF32(v.toFloat)
        else pushF32(((v >>> 1) | (v & 1L)).toFloat * 2.0f)
        f.pc += 1

      case 0xb6 =>                                                                          // f32.demote_f64
        val v = popF64()
        pushF32(v.toFloat)
        f.pc += 1

      case 0xb7 =>                                                                          // f64.convert_i32_s
        val v = popI32()
        pushF64(v.toDouble)
        f.pc += 1

      case 0xb8 =>                                                                          // f64.convert_i32_u
        val v = popI32()
        pushF64((v.toLong & 0xffffffffL).toDouble)
        f.pc += 1

      case 0xb9 =>                                                                          // f64.convert_i64_s
        val v = popI64()
        pushF64(v.toDouble)
        f.pc += 1

      case 0xba =>                                                                          // f64.convert_i64_u
        val v = popI64()
        if v >= 0L then pushF64(v.toDouble)
        else pushF64(((v >>> 1) | (v & 1L)).toDouble * 2.0)
        f.pc += 1

      case 0xbb =>                                                                          // f64.promote_f32
        val v = popF32()
        pushF64(v.toDouble)
        f.pc += 1

      // Reinterpret: pure bit-cast. `floatToRawIntBits` / `doubleToRawLongBits`
      // (vs `floatToIntBits` / `doubleToLongBits`) preserve NaN payloads —
      // the non-raw versions canonicalise NaN to a single representation,
      // which would lose information that the wasm value carries.

      case 0xbc =>                                                                          // i32.reinterpret_f32
        val v = popF32()
        pushI32(jl.Float.floatToRawIntBits(v))
        f.pc += 1

      case 0xbd =>                                                                          // i64.reinterpret_f64
        val v = popF64()
        pushI64(jl.Double.doubleToRawLongBits(v))
        f.pc += 1

      case 0xbe =>                                                                          // f32.reinterpret_i32
        val v = popI32()
        pushF32(jl.Float.intBitsToFloat(v))
        f.pc += 1

      case 0xbf =>                                                                          // f64.reinterpret_i64
        val v = popI64()
        pushF64(jl.Double.longBitsToDouble(v))
        f.pc += 1

      // === sign-extension proposal =======================================
      //
      // Five opcodes added by the post-MVP sign-extension proposal. rustc
      // emits 0xC0 (i32.extend8_s) from `as i8 as i32`, `i64 << 56 >> 56`,
      // etc. — common enough to land alongside MVP. All single-byte.

      case 0xc0 => unop  (a => (a << 24) >> 24); f.pc += 1                                  // i32.extend8_s
      case 0xc1 => unop  (a => (a << 16) >> 16); f.pc += 1                                  // i32.extend16_s
      case 0xc2 => unop64(v => (v << 56) >> 56); f.pc += 1                                  // i64.extend8_s
      case 0xc3 => unop64(v => (v << 48) >> 48); f.pc += 1                                  // i64.extend16_s
      case 0xc4 => unop64(v => (v << 32) >> 32); f.pc += 1                                  // i64.extend32_s

      // === 0xFC multibyte prefix (trunc_sat + bulk-memory subset) ========
      //
      // The 0xFC prefix family carries the bulk-memory + table proposal
      // ops and the non-trapping (saturating) float-to-int conversions.
      // Sub 0..7 are trunc_sat (Phase 8.A); sub 10/11 are memory.copy /
      // memory.fill (Phase 7.B); the remaining bulk-memory + table ops
      // (memory.init, data.drop, table.copy, table.init, elem.drop) stay
      // UnknownOpcode until they surface in a real binary — each will land
      // alongside its own dispatch + skipImmediates pair and regression tests.
      //
      // === trunc_sat semantics (all 8) ===
      //   NaN              → 0
      //   v < INT_MIN      → INT_MIN   (signed)   /   0       (unsigned)
      //   v > INT_MAX      → INT_MAX
      //   otherwise        → truncate toward zero
      // The boundary values used here mirror the trapping versions
      // (0xA8..0xAB, 0xAE..0xB1) — Float can represent ±2^31 / ±2^63 / 2^32
      // / 2^64 exactly (all powers of 2), so the strict-`>=` upper-bound and
      // strict-`<` (signed) / `<= -1` (unsigned) lower-bound conventions
      // pick out exactly the in-range half-open interval. For
      // i64.trunc_sat_*_u in [2^63, 2^64), the bit-splice trick (subtract
      // 2^63, convert, OR back the sign bit) mirrors the trapping version
      // because Java's `Float.toLong` clamps to Long.MaxValue for values
      // past 2^63.

      case 0xfc =>
        // The 0xFC sub-dispatch was extracted into its own method when its
        // body got large enough to push `step` past the JVM 64KB-method
        // ceiling. Phase 8.A (8 trunc_sat sub-opcodes) + Phase 8.B (5
        // bulk-memory sub-opcodes) + Phase 8.C (3 ref-typed table sub-
        // opcodes) brought the cumulative case down here past the limit;
        // factoring it out is a no-op semantically.
        stepFc(f)

      // === Phase 8.C: reference-types ====================================
      //
      // Five new top-level opcodes: two table accessors (0x25 / 0x26) and
      // three ref ops (0xD0 / 0xD1 / 0xD2). Validator enforced operand
      // types and table-index range; here we trust both.

      case 0x25 =>                                                                          // table.get tableidx
        val (tableIdx, p) = readU32At(f, f.pc + 1)
        f.pc = p
        val tab  = tables(tableIdx)
        val slot = popI32()
        if slot < 0 || slot >= tab.size then
          fail(WasmError.MemoryOutOfBounds)
        valueStack += tab.slots(slot)

      case 0x26 =>                                                                          // table.set tableidx
        val (tableIdx, p) = readU32At(f, f.pc + 1)
        f.pc = p
        val tab  = tables(tableIdx)
        val v    = popValue()
        val slot = popI32()
        if slot < 0 || slot >= tab.size then
          fail(WasmError.MemoryOutOfBounds)
        tab.slots(slot) = v

      case 0xd0 =>                                                                          // ref.null reftype
        val b = body(f.pc + 1) & 0xff
        f.pc += 2
        val rt = RefType.fromByte(b).getOrElse(
          fail(WasmError.InvalidModule(s"ref.null: unknown reftype 0x${b.toHexString}")))
        valueStack += RefNull(rt)

      case 0xd1 =>                                                                          // ref.is_null
        f.pc += 1
        val v = popValue()
        pushI32(v match
          case _: RefNull => 1
          case _          => 0)

      case 0xd2 =>                                                                          // ref.func funcidx
        val (idx, p) = readU32At(f, f.pc + 1)
        f.pc = p
        valueStack += RefFunc(idx)

      // === unsupported ===================================================

      case other => fail(WasmError.UnknownOpcode(other))

  /** Dispatch one 0xFC sub-opcode. Pulled out of `step` to keep that
    * method under the JVM's 64KB ceiling — the cumulative trunc_sat (8.A)
    * + bulk-memory remainder (8.B) + table grow/size/fill (8.C) arms add
    * up to roughly 16 sub-cases of non-trivial length. The body is
    * otherwise structurally identical to what `step` would have done. */
  private def stepFc(f: Frame): Unit =
    val body = f.func.body
    val (sub, p1) = readU32At(f, f.pc + 1)
    sub match
      case 0 =>                                                                         // i32.trunc_sat_f32_s
        val v = popF32()
        val r =
          if jl.Float.isNaN(v)              then 0
          else if v < -2147483648.0f        then Int.MinValue
          else if v >=  2147483648.0f       then Int.MaxValue
          else                                   v.toInt
        pushI32(r)
        f.pc = p1

      case 1 =>                                                                         // i32.trunc_sat_f32_u
        val v = popF32()
        val r =
          if jl.Float.isNaN(v)              then 0
          else if v <= -1.0f                then 0
          else if v >=  4294967296.0f       then -1                                    // 0xFFFFFFFF as signed Int
          else                                   v.toLong.toInt
        pushI32(r)
        f.pc = p1

      case 2 =>                                                                         // i32.trunc_sat_f64_s
        val v = popF64()
        val r =
          if jl.Double.isNaN(v)             then 0
          else if v < -2147483648.0         then Int.MinValue
          else if v >=  2147483648.0        then Int.MaxValue
          else                                   v.toInt
        pushI32(r)
        f.pc = p1

      case 3 =>                                                                         // i32.trunc_sat_f64_u
        val v = popF64()
        val r =
          if jl.Double.isNaN(v)             then 0
          else if v <= -1.0                 then 0
          else if v >=  4294967296.0        then -1
          else                                   v.toLong.toInt
        pushI32(r)
        f.pc = p1

      case 4 =>                                                                         // i64.trunc_sat_f32_s
        val v = popF32()
        val r =
          if jl.Float.isNaN(v)              then 0L
          else if v < -9223372036854775808.0f  then Long.MinValue
          else if v >=  9223372036854775808.0f then Long.MaxValue
          else                                   v.toLong
        pushI64(r)
        f.pc = p1

      case 5 =>                                                                         // i64.trunc_sat_f32_u
        val v = popF32()
        val r =
          if jl.Float.isNaN(v)              then 0L
          else if v <= -1.0f                then 0L
          else if v >= 18446744073709551616.0f then -1L                                // UInt64.MaxValue
          else if v < 9223372036854775808.0f   then v.toLong
          else (v - 9223372036854775808.0f).toLong | Long.MinValue
        pushI64(r)
        f.pc = p1

      case 6 =>                                                                         // i64.trunc_sat_f64_s
        val v = popF64()
        val r =
          if jl.Double.isNaN(v)             then 0L
          else if v < -9223372036854775808.0  then Long.MinValue
          else if v >=  9223372036854775808.0 then Long.MaxValue
          else                                   v.toLong
        pushI64(r)
        f.pc = p1

      case 7 =>                                                                         // i64.trunc_sat_f64_u
        val v = popF64()
        val r =
          if jl.Double.isNaN(v)             then 0L
          else if v <= -1.0                 then 0L
          else if v >= 18446744073709551616.0 then -1L
          else if v < 9223372036854775808.0   then v.toLong
          else (v - 9223372036854775808.0).toLong | Long.MinValue
        pushI64(r)
        f.pc = p1

      // ===== Phase 8.B bulk-memory remainder =================================
      //
      // memory.init / data.drop reference one data segment by dataidx;
      // table.init / elem.drop reference one element segment by elemidx;
      // table.copy references two tables (dst, src). All five share the
      // unsigned-Long bounds check pattern used by memory.copy/fill.
      // The validator has already range-checked the static immediates
      // (dataidx, elemidx, tableidx, reserved memidx); the interpreter
      // re-reads them via readU32At for the dispatch.

      case 8 =>                                                                         // memory.init dataidx, memidx
        val (dataIdx, p2)  = readU32At(f, p1)
        val (memIdx, p3)   = readU32At(f, p2)
        f.pc = p3
        if memIdx < 0 || memIdx >= memories.length then
          fail(WasmError.InvalidModule(s"memory.init: memidx $memIdx out of range (have ${memories.length} memories)"))
        val mem = memories(memIdx)
        val n   = popI32()
        val src = popI32()                                                              // offset into data segment
        val dst = popI32()                                                              // offset into memory
        val nL   = n.toLong   & 0xffffffffL
        val srcL = src.toLong & 0xffffffffL
        val dstL = dst.toLong & 0xffffffffL
        // A dropped segment is treated as an empty byte vector — the
        // OOB check uses its effective length (0 if dropped, else
        // original byte-count). The dataDropped flag is set both for
        // active segments post-instantiation and for passive segments
        // that have been explicitly `data.drop`'d.
        val segLen =
          if dataDropped(dataIdx) then 0L else dataBytes(dataIdx).length.toLong
        if srcL + nL > segLen || dstL + nL > mem.data.length.toLong then
          fail(WasmError.MemoryOutOfBounds)
        if nL > 0L then
          System.arraycopy(dataBytes(dataIdx), srcL.toInt, mem.data, dstL.toInt, nL.toInt)

      case 9 =>                                                                         // data.drop dataidx
        val (dataIdx, p2) = readU32At(f, p1)
        f.pc = p2
        // Idempotent — dropping a dropped (or originally-active) segment
        // is a no-op, not an error. The bit stays set.
        dataDropped(dataIdx) = true

      case 10 =>                                                                        // memory.copy dst-memidx src-memidx
        val (dstMemIdx, p2) = readU32At(f, p1)
        val (srcMemIdx, p3) = readU32At(f, p2)
        f.pc = p3
        if dstMemIdx < 0 || dstMemIdx >= memories.length then
          fail(WasmError.InvalidModule(s"memory.copy: dst memidx $dstMemIdx out of range"))
        if srcMemIdx < 0 || srcMemIdx >= memories.length then
          fail(WasmError.InvalidModule(s"memory.copy: src memidx $srcMemIdx out of range"))
        val dstMem = memories(dstMemIdx)
        val srcMem = memories(srcMemIdx)
        val n   = popI32()
        val src = popI32()
        val dst = popI32()
        val nL   = n.toLong   & 0xffffffffL
        val srcL = src.toLong & 0xffffffffL
        val dstL = dst.toLong & 0xffffffffL
        if srcL + nL > srcMem.data.length.toLong || dstL + nL > dstMem.data.length.toLong then
          fail(WasmError.MemoryOutOfBounds)
        if nL > 0L then
          // System.arraycopy handles overlapping copies correctly in both
          // directions when src and dst arrays are the same, matching the
          // spec for same-memory overlap. Cross-memory uses distinct
          // backing arrays, so no aliasing concern.
          System.arraycopy(srcMem.data, srcL.toInt, dstMem.data, dstL.toInt, nL.toInt)

      case 11 =>                                                                        // memory.fill memidx
        val (memIdx, p2) = readU32At(f, p1)
        f.pc = p2
        if memIdx < 0 || memIdx >= memories.length then
          fail(WasmError.InvalidModule(s"memory.fill: memidx $memIdx out of range"))
        val mem = memories(memIdx)
        val n   = popI32()
        val v   = popI32()
        val dst = popI32()
        val nL   = n.toLong   & 0xffffffffL
        val dstL = dst.toLong & 0xffffffffL
        if dstL + nL > mem.data.length.toLong then
          fail(WasmError.MemoryOutOfBounds)
        if nL > 0L then
          java.util.Arrays.fill(mem.data, dstL.toInt, (dstL + nL).toInt, (v & 0xff).toByte)

      case 12 =>                                                                        // table.init elemidx, tableidx
        val (elemIdx, p2) = readU32At(f, p1)
        val (tableIdx, p3) = readU32At(f, p2)
        f.pc = p3
        val n   = popI32()
        val src = popI32()                                                              // offset into elem segment
        val dst = popI32()                                                              // offset into table
        val nL   = n.toLong   & 0xffffffffL
        val srcL = src.toLong & 0xffffffffL
        val dstL = dst.toLong & 0xffffffffL
        val tab    = tables(tableIdx)
        val segLen = if elemDropped(elemIdx) then 0L else elemRefs(elemIdx).length.toLong
        // Same unsigned-Long bounds-check pattern as memory.init. We
        // reuse the MemoryOutOfBounds error variant because no
        // Table-specific one exists yet; the diagnostic message
        // disambiguates.
        if srcL + nL > segLen || dstL + nL > tab.size.toLong then
          fail(WasmError.MemoryOutOfBounds)
        if nL > 0L then
          val src0 = srcL.toInt
          val dst0 = dstL.toInt
          val segRefs = elemRefs(elemIdx)
          var k = 0
          while k < nL.toInt do
            tab.slots(dst0 + k) = segRefs(src0 + k)
            k += 1

      case 13 =>                                                                        // elem.drop elemidx
        val (elemIdx, p2) = readU32At(f, p1)
        f.pc = p2
        elemDropped(elemIdx) = true

      case 14 =>                                                                        // table.copy dst-tableidx, src-tableidx
        val (dstTab, p2) = readU32At(f, p1)
        val (srcTab, p3) = readU32At(f, p2)
        f.pc = p3
        val n   = popI32()
        val src = popI32()
        val dst = popI32()
        val nL   = n.toLong   & 0xffffffffL
        val srcL = src.toLong & 0xffffffffL
        val dstL = dst.toLong & 0xffffffffL
        val sTab = tables(srcTab)
        val dTab = tables(dstTab)
        if srcL + nL > sTab.size.toLong || dstL + nL > dTab.size.toLong then
          fail(WasmError.MemoryOutOfBounds)
        if nL > 0L then
          // System.arraycopy handles overlapping copies correctly,
          // including same-table self-copy (dstTab == srcTab).
          System.arraycopy(sTab.slots, srcL.toInt, dTab.slots, dstL.toInt, nL.toInt)

      // Phase 8.C: ref-typed table ops. table.grow / table.size /
      // table.fill all take a single tableidx LEB. operand types come
      // from the table's reftype (the validator already enforced that
      // the fill / value popped here has the right type).
      case 15 =>                                                                        // table.grow tableidx
        val (tableIdx, p2) = readU32At(f, p1)
        f.pc = p2
        val n    = popI32()
        val fill = popValue()
        val tab  = tables(tableIdx)
        pushI32(tab.grow(n, fill))

      case 16 =>                                                                        // table.size tableidx
        val (tableIdx, p2) = readU32At(f, p1)
        f.pc = p2
        pushI32(tables(tableIdx).size)

      case 17 =>                                                                        // table.fill tableidx
        val (tableIdx, p2) = readU32At(f, p1)
        f.pc = p2
        val n   = popI32()
        val v   = popValue()
        val dst = popI32()
        val nL   = n.toLong   & 0xffffffffL
        val dstL = dst.toLong & 0xffffffffL
        val tab  = tables(tableIdx)
        if dstL + nL > tab.size.toLong then
          fail(WasmError.MemoryOutOfBounds)
        if nL > 0L then
          val dst0 = dstL.toInt
          var k = 0
          while k < nL.toInt do
            tab.slots(dst0 + k) = v
            k += 1

      case _ =>
        fail(WasmError.UnknownOpcode(0xfc))

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
        // Host functions take the implicit "memory 0" handle. Phase 8.D
        // multi-memory introspection is host-side only — host modules
        // wanting access to memidx > 0 can reach through ModuleInstance
        // separately; the per-call HostFunc surface keeps the MVP
        // contract for backwards compat. memories.length is always >= 1
        // (zero-memory modules get a synthetic zero-page placeholder).
        val results = fn(memories(0), args.toSeq)
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
        // Java's `0.0f`/`0.0` defaults match.) Phase 8.C: reftype locals
        // zero-init to a typed null.
        var j = paramCount
        while j < localCount do
          locals(j) = localTypes(j) match
            case ValueType.I32Type       => I32(0)
            case ValueType.I64Type       => I64(0L)
            case ValueType.F32Type       => F32(0.0f)
            case ValueType.F64Type       => F64(0.0)
            case ValueType.FuncRefType   => RefNull(RefType.FuncRef)
            case ValueType.ExternRefType => RefNull(RefType.ExternRef)
          j += 1
        frames += new Frame(wf, locals, stackBase = valueStack.size)

  // === memory access ======================================================
  //
  // Phase 8.D: each helper takes the target Memory explicitly. Callers
  // resolve the memArg's memidx via `memArgMemory(memArg)` and pass the
  // result through.

  private inline def boundsCheck(mem: Memory, addr: Long, n: Int): Unit =
    if addr < 0 || addr + n > mem.data.length then fail(WasmError.MemoryOutOfBounds)

  private def loadByte(mem: Memory, addr: Long): Int =
    boundsCheck(mem, addr, 1)
    mem.data(addr.toInt) & 0xff

  private def storeByte(mem: Memory, addr: Long, v: Int): Unit =
    boundsCheck(mem, addr, 1)
    mem.data(addr.toInt) = v.toByte

  /** Little-endian 16-bit load — returns a sign-extended Int. Callers that
    * want the zero-extended form mask with `0xffff` themselves. */
  private def loadI16(mem: Memory, addr: Long): Int =
    boundsCheck(mem, addr, 2)
    val a = addr.toInt
    val d = mem.data
    val raw = (d(a) & 0xff) | ((d(a + 1) & 0xff) << 8)
    (raw << 16) >> 16 // sign-extend the 16-bit value into an Int

  private def storeI16(mem: Memory, addr: Long, v: Int): Unit =
    boundsCheck(mem, addr, 2)
    val a = addr.toInt
    val d = mem.data
    d(a)     = (v         & 0xff).toByte
    d(a + 1) = ((v >>> 8) & 0xff).toByte

  /** Little-endian 32-bit load. */
  private def loadI32(mem: Memory, addr: Long): Int =
    boundsCheck(mem, addr, 4)
    val a = addr.toInt
    val d = mem.data
    (d(a) & 0xff) | ((d(a + 1) & 0xff) << 8) | ((d(a + 2) & 0xff) << 16) | ((d(a + 3) & 0xff) << 24)

  private def storeI32(mem: Memory, addr: Long, v: Int): Unit =
    boundsCheck(mem, addr, 4)
    val a = addr.toInt
    val d = mem.data
    d(a)     = (v         & 0xff).toByte
    d(a + 1) = ((v >>>  8) & 0xff).toByte
    d(a + 2) = ((v >>> 16) & 0xff).toByte
    d(a + 3) = ((v >>> 24) & 0xff).toByte

  /** Little-endian 64-bit load. */
  private def loadI64(mem: Memory, addr: Long): Long =
    boundsCheck(mem, addr, 8)
    val a = addr.toInt
    val d = mem.data
    (d(a)     & 0xffL)        |
    ((d(a + 1) & 0xffL) <<  8) |
    ((d(a + 2) & 0xffL) << 16) |
    ((d(a + 3) & 0xffL) << 24) |
    ((d(a + 4) & 0xffL) << 32) |
    ((d(a + 5) & 0xffL) << 40) |
    ((d(a + 6) & 0xffL) << 48) |
    ((d(a + 7) & 0xffL) << 56)

  private def storeI64(mem: Memory, addr: Long, v: Long): Unit =
    boundsCheck(mem, addr, 8)
    val a = addr.toInt
    val d = mem.data
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
  private def loadF32(mem: Memory, addr: Long): Float =
    jl.Float.intBitsToFloat(loadI32(mem, addr))

  private def storeF32(mem: Memory, addr: Long, v: Float): Unit =
    storeI32(mem, addr, jl.Float.floatToRawIntBits(v))

  /** Little-endian 64-bit IEEE-754 load. Same NaN-payload-preserving raw
    * conversion as the f32 path, just at Double width. */
  private def loadF64(mem: Memory, addr: Long): Double =
    jl.Double.longBitsToDouble(loadI64(mem, addr))

  private def storeF64(mem: Memory, addr: Long, v: Double): Unit =
    storeI64(mem, addr, jl.Double.doubleToRawLongBits(v))

  // === memarg / memory-index helpers ======================================

  /** Read the memarg starting just after the current opcode, advance the
    * frame's pc past it, and return the immediate. Routes through the
    * `Interpreter.readMemArg` companion-object helper so the encoding
    * (incl. the multi-memory bit-6 flag) is shared with `skipImmediates`
    * + the validator. */
  private inline def readMemArgFrame(f: Frame): MemArg =
    Interpreter.readMemArg(f.func.body, f.pc + 1) match
      case Left(e) => fail(e)
      case Right((memArg, p)) =>
        f.pc = p
        memArg

  /** Resolve a memarg's memidx to the runtime [[Memory]]. The validator
    * already range-checked the index, but a defensive check here keeps
    * the runtime trap-shape clean for handwritten / malformed binaries
    * that bypass validation (none should reach here today). */
  private inline def memArgMemory(memArg: MemArg): Memory =
    val i = memArg.memIdx
    if i < 0 || i >= memories.length then
      fail(WasmError.InvalidModule(s"memory op: memidx $i out of range (have ${memories.length} memories)"))
    memories(i)

  /** Read a single u32 LEB at `f.pc + 1`, advance `f.pc`, range-check
    * the value against `memories.length`, and return the resolved
    * memory. Used by the memory.size / memory.grow / memory.fill step
    * cases whose only immediate is a single memidx LEB. */
  private inline def readMemIdxMemory(f: Frame): Memory =
    val (idx, p) = readU32At(f, f.pc + 1)
    f.pc = p
    if idx < 0 || idx >= memories.length then
      fail(WasmError.InvalidModule(s"memory op: memidx $idx out of range (have ${memories.length} memories)"))
    memories(idx)

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
