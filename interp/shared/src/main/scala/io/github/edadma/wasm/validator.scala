package io.github.edadma.wasm

import scala.collection.mutable.ArrayBuffer

/** Module validator — Phase 6.
  *
  * Walks every defined function body once, before any instruction is
  * interpreted, and performs the WebAssembly spec's static checks:
  *
  *   - abstract operand-stack typing — every opcode pops its declared
  *     operand types and pushes its declared results; a mismatch is
  *     `InvalidModule` with a "expected X, got Y" diagnostic;
  *   - control-flow well-formedness — block/loop/if open frames, the
  *     matching `end` pops them, and the function body's outer `end`
  *     pops the implicit function frame;
  *   - label arity matching — `br` / `br_if` / `br_table` validate
  *     against the target frame's label types (results for block/if,
  *     params for loop — loops re-feed their params on `br`);
  *   - stack polymorphism — after a `br` / `return` / `unreachable`
  *     the rest of the block is "unreachable"; pops in that region
  *     synthesise the expected type rather than erroring.
  *
  * On success the runtime can safely assume that every value-stack
  * pop sees the right type, every `br` lands on a real label, every
  * block exits with the right result arity. The interpreter's
  * defensive `TypeMismatch` checks become assertions of validator
  * invariants rather than recoverable runtime errors.
  *
  * Diagnostics name the function index (using the wasm-wide funcidx
  * space — imports first, then defined functions), the byte offset
  * of the failing instruction within the function body, and where
  * available the expected vs found stack type. Useful for handwritten
  * code generators iterating on bring-up: the spec's "the module is
  * invalid" needs to point at a specific line of generated emit code.
  */
object Validator:

  // === Abstract operand stack =============================================

  /** Abstract value on the operand stack. `Unknown` is the polymorphic
    * placeholder produced after a `br` / `return` / `unreachable`: a
    * subsequent pop will silently consume it and return whatever type
    * the consumer expected. This is how the spec models "any code
    * after a guaranteed branch is well-typed for any future use". */
  enum AbsValue:
    case Known(t: ValueType)
    case Unknown

  /** One frame on the control stack. `startTypes` are the values
    * present at frame entry (block params — pushed on the operand
    * stack as part of opening the frame); `endTypes` are what must
    * be on the stack when the frame closes (block results).
    * `baseHeight` is the operand-stack height at the moment the
    * frame opened, so the frame's operand window starts at index
    * `baseHeight + startTypes.size`. `unreachable` flips to true
    * after `br` / `return` / `unreachable` inside this frame. */
  final case class CtrlFrame(
      kind:        CtrlKind,
      startTypes:  Vector[ValueType],
      endTypes:    Vector[ValueType],
      baseHeight:  Int,
      var unreachable: Boolean = false,
  )

  enum CtrlKind:
    case Function, Block, Loop, If, Else
    /** Legacy EH frames. `Try` is opened by `0x06`; `Catch` and `CatchAll`
      * are opened by `0x07 tagidx` / `0x19` respectively (each replaces the
      * previous Try/Catch/CatchAll on the control stack but inherits the
      * try's `endTypes`). `rethrow N` is only legal when the label-frame at
      * depth N is a `Catch` or `CatchAll`. */
    case Try, Catch, CatchAll
    /** Modern EH frame. `TryTable` is opened by `0x1F` and is structurally
      * a `Block` — body produces `endTypes`, normal fall-through pops the
      * frame at `end`. Handlers branch to outer labels at throw delivery
      * time; the validator just type-checks the handler payload against
      * the target label's branch arity. */
    case TryTable

  // === Public API =========================================================

  private final class ValFail(val err: WasmError)
      extends RuntimeException(null, null, false, false)

  /** Validate every defined function body in `module`. Returns
    * `Right(())` on success, or `Left(InvalidModule(...))` with a
    * diagnostic message naming the offending function index and
    * byte offset. */
  def validate(module: WasmModule): Either[WasmError, Unit] =
    try
      // Module-level typeidx checks first: every import's and every
      // defined function's typeidx must be in range. Defining functions
      // also get their bodies walked below; imports have no body so the
      // typeidx check is the only validation they need here.
      var im = 0
      while im < module.imports.length do
        val imp = module.imports(im)
        if imp.typeIdx < 0 || imp.typeIdx >= module.types.length then
          throw new ValFail(WasmError.InvalidModule(
            s"import ${imp.module}.${imp.name}: type index ${imp.typeIdx} out of range"))
        im += 1
      val funcSigs       = collectFuncSigs(module)
      val globalSigs     = module.globals.map(g => (g.valueType, g.mutable))
      val tableRefTypes  = module.tables.map(_.refType)
      val elemRefTypes   = module.elements.map(_.refType)
      val tagTypes       = collectTagTypes(module)
      // Phase 8.C: build the set of "declared" funcidxs — those that may
      // appear as a ref.func operand. Per the wasm-3.0 spec these are
      // funcidxs that appear anywhere structural in the module (exports,
      // start, element segments). The function body's own ref.func
      // operands don't count (that would be circular). A funcidx that's
      // never declared rejects with "ref.func: funcidx N not declared".
      val declaredFuncs = scala.collection.mutable.HashSet.empty[Int]
      module.exports.foreach {
        case FuncExport(_, idx) => declaredFuncs += idx
        case _                  => ()
      }
      module.startFunction.foreach(declaredFuncs += _)
      module.elements.foreach { seg =>
        seg.refs.foreach {
          case RefFunc(idx) => declaredFuncs += idx
          case _            => ()
        }
      }
      var i = 0
      while i < module.codes.length do
        val typeIdx = module.functions(i)
        val funcIdx = module.imports.length + i
        if typeIdx < 0 || typeIdx >= module.types.length then
          val nameSuffix = module.funcNames.get(funcIdx).fold("")(n => s" ($n)")
          throw new ValFail(WasmError.InvalidModule(
            s"function $funcIdx$nameSuffix: type index $typeIdx out of range"))
        validateFunction(
          funcIdx          = funcIdx,
          funcName         = module.funcNames.get(funcIdx),
          sig              = module.types(typeIdx),
          declared         = module.codes(i).locals,
          body             = module.codes(i).body,
          funcSigs         = funcSigs,
          globalSigs       = globalSigs,
          types            = module.types,
          tableCount       = module.tables.length,
          tableRefTypes    = tableRefTypes,
          memoryCount      = module.memories.length,
          dataSegmentCount = module.data.length,
          elemSegmentCount = module.elements.length,
          elemRefTypes     = elemRefTypes,
          declaredFuncs    = declaredFuncs.toSet,
          dataCountPresent = module.dataCount.isDefined,
          tagTypes         = tagTypes,
        )
        i += 1
      Right(())
    catch case e: ValFail => Left(e.err)

  // === Per-module helpers =================================================

  /** Build the unified tag-payload table — imports first, then defs. The
    * EH proposal requires every tag's functype to have empty results; this
    * pre-pass surfaces a violation as `InvalidModule` before any function
    * body sees a `throw tagidx`. Each entry is the tag's payload param
    * vector — what `throw tagidx` pops in order and what `catch tagidx`
    * pushes onto the operand stack. */
  private def collectTagTypes(module: WasmModule): Vector[Vector[ValueType]] =
    val out = ArrayBuffer.empty[Vector[ValueType]]
    module.tagImports.foreach { ti =>
      if ti.typeIdx < 0 || ti.typeIdx >= module.types.length then
        throw new ValFail(WasmError.InvalidModule(
          s"tag import ${ti.module}.${ti.name}: type index ${ti.typeIdx} out of range"))
      val ft = module.types(ti.typeIdx)
      if ft.results.nonEmpty then
        throw new ValFail(WasmError.InvalidModule(
          s"tag import ${ti.module}.${ti.name}: tag functype must have empty results"))
      out += ft.params
    }
    module.tags.zipWithIndex.foreach { case (t, i) =>
      val tagIdx = module.tagImports.length + i
      if t.typeIdx < 0 || t.typeIdx >= module.types.length then
        throw new ValFail(WasmError.InvalidModule(
          s"tag $tagIdx: type index ${t.typeIdx} out of range"))
      val ft = module.types(t.typeIdx)
      if ft.results.nonEmpty then
        throw new ValFail(WasmError.InvalidModule(
          s"tag $tagIdx: tag functype must have empty results"))
      out += ft.params
    }
    out.toVector

  /** Build the unified function-signature index — imports first, then
    * defined functions. Same order the interpreter uses internally so
    * `call funcidx` immediates resolve consistently. */
  private def collectFuncSigs(module: WasmModule): Vector[FuncType] =
    val out = ArrayBuffer.empty[FuncType]
    module.imports.foreach { imp =>
      // Tolerate out-of-range typeidxs here — Runtime.build surfaces
      // them as a clearer InvalidModule. Using an empty type prevents
      // a NoSuchElementException during validation.
      if imp.typeIdx >= 0 && imp.typeIdx < module.types.length then
        out += module.types(imp.typeIdx)
      else
        out += FuncType(Vector.empty, Vector.empty)
    }
    module.functions.foreach { typeIdx =>
      if typeIdx >= 0 && typeIdx < module.types.length then
        out += module.types(typeIdx)
      else
        out += FuncType(Vector.empty, Vector.empty)
    }
    out.toVector

  // === Per-function validation ============================================

  /** Validate one function body. Opens an implicit `Function` control
    * frame whose `endTypes` are the function's declared results; the
    * outer body-end (0x0B) pops that frame, ensuring the function's
    * results are on the stack at exit. */
  private def validateFunction(
      funcIdx:          Int,
      funcName:         Option[String],
      sig:              FuncType,
      declared:         Vector[ValueType],
      body:             Array[Byte],
      funcSigs:         Vector[FuncType],
      globalSigs:       Vector[(ValueType, Boolean)],
      types:            Vector[FuncType],
      tableCount:       Int,
      tableRefTypes:    Vector[RefType],
      memoryCount:      Int,
      dataSegmentCount: Int,
      elemSegmentCount: Int,
      elemRefTypes:     Vector[RefType],
      declaredFuncs:    Set[Int],
      dataCountPresent: Boolean,
      tagTypes:         Vector[Vector[ValueType]],
  ): Unit =
    val state = new State(
      funcIdx          = funcIdx,
      funcName         = funcName,
      funcResults      = sig.results,
      locals           = sig.params ++ declared,
      funcSigs         = funcSigs,
      globalSigs       = globalSigs,
      types            = types,
      tableCount       = tableCount,
      tableRefTypes    = tableRefTypes,
      memoryCount      = memoryCount,
      dataSegmentCount = dataSegmentCount,
      elemSegmentCount = elemSegmentCount,
      elemRefTypes     = elemRefTypes,
      declaredFuncs    = declaredFuncs,
      dataCountPresent = dataCountPresent,
      tagTypes         = tagTypes,
      body             = body,
    )
    state.pushCtrl(CtrlKind.Function, Vector.empty, sig.results)
    state.walk()
    // After the outer end is consumed, ctrlStack must be empty and pc
    // at the end of the body. `walk` already enforces this.

  /** Per-function validator state. Mutable because the algorithm is
    * structurally iterative; immutable would be possible but make the
    * operand/ctrl stacks much noisier. */
  private final class State(
      val funcIdx:     Int,
      val funcName:    Option[String],
      val funcResults: Vector[ValueType],
      val locals:      Vector[ValueType],
      val funcSigs:    Vector[FuncType],
      val globalSigs:  Vector[(ValueType, Boolean)],
      val types:       Vector[FuncType],
      val tableCount:  Int,
      // Phase 8.C: per-table reference type, indexed by tableidx. Used by
      // `call_indirect` (must be funcref), `table.copy` (matching reftypes),
      // `table.init` (segment reftype must match table reftype),
      // `table.get` / `table.set` / `table.grow` / `table.fill` (operand
      // type is the table's reftype).
      val tableRefTypes: Vector[RefType],
      val memoryCount: Int,
      // Phase 8.B context for bulk-memory + table ops:
      //   dataSegmentCount — `memory.init` / `data.drop` need their
      //     dataidx immediate validated against this.
      //   elemSegmentCount — `table.init` / `elem.drop` need their
      //     elemidx immediate validated against this.
      //   dataCountPresent — `memory.init` and `data.drop` are only
      //     valid in a module that declared a DataCount section
      //     (Section 12) per the bulk-memory spec.
      val dataSegmentCount: Int,
      val elemSegmentCount: Int,
      val elemRefTypes:     Vector[RefType],
      val declaredFuncs:    Set[Int],
      val dataCountPresent: Boolean,
      // EH proposal: indexed by tagidx (imports first, then defs); the entry
      // is the tag's payload param vector. `throw tagidx` pops these in order,
      // `catch tagidx` pushes them at handler entry.
      val tagTypes:         Vector[Vector[ValueType]],
      val body:        Array[Byte],
  ):
    val operandStack: ArrayBuffer[AbsValue]  = ArrayBuffer.empty
    val ctrlStack:    ArrayBuffer[CtrlFrame] = ArrayBuffer.empty
    var pc:   Int = 0
    var opPC: Int = 0   // PC of the instruction currently being validated

    // --- diagnostics ----

    def fail(msg: String): Nothing =
      val nameSuffix = funcName.fold("")(n => s" ($n)")
      throw new ValFail(WasmError.InvalidModule(
        s"function $funcIdx$nameSuffix: byte offset 0x${opPC.toHexString}: $msg"))

    def typeName(t: ValueType): String = t match
      case ValueType.I32Type       => "i32"
      case ValueType.I64Type       => "i64"
      case ValueType.F32Type       => "f32"
      case ValueType.F64Type       => "f64"
      case ValueType.FuncRefType   => "funcref"
      case ValueType.ExternRefType => "externref"
      case ValueType.V128Type      => "v128"
      case ValueType.ExnRefType    => "exnref"

    // --- operand stack ----

    def pushVal(v: AbsValue): Unit = operandStack += v
    def pushVal(t: ValueType): Unit = pushVal(AbsValue.Known(t))

    def pushVals(ts: Vector[ValueType]): Unit =
      var i = 0
      while i < ts.length do
        pushVal(ts(i))
        i += 1

    /** Pop one value off the operand stack. If the topmost control
      * frame is unreachable and the stack is at the frame's base
      * height, return `Unknown` (polymorphic): the validator pretends
      * the dead code produced whatever value the consumer needs.
      * Otherwise pop a real value, or error on underflow. */
    def popVal(): AbsValue =
      val frame = topFrame
      if operandStack.size == frame.baseHeight then
        if frame.unreachable then AbsValue.Unknown
        else fail("operand stack underflow")
      else if operandStack.size < frame.baseHeight then
        fail("operand stack underflow (below frame base)")
      else
        operandStack.remove(operandStack.size - 1)

    /** Pop one value and check it matches `expected`. `Unknown`
      * matches any expected type. The popped value isn't returned —
      * every caller just wants the side-effect of pop + type-check. */
    def popVal(expected: ValueType): Unit =
      popVal() match
        case AbsValue.Unknown   => ()
        case AbsValue.Known(t)  =>
          if t != expected then
            fail(s"type mismatch: expected ${typeName(expected)}, got ${typeName(t)}")

    /** Pop a vector of values in REVERSE order so the bottom of
      * `ts` matches the top of the stack — consumer semantics. */
    def popVals(ts: Vector[ValueType]): Unit =
      var i = ts.length - 1
      while i >= 0 do
        popVal(ts(i))
        i -= 1

    // --- control stack ----

    def topFrame: CtrlFrame =
      if ctrlStack.isEmpty then fail("control stack underflow")
      else ctrlStack.last

    def pushCtrl(kind: CtrlKind, startTypes: Vector[ValueType],
                 endTypes: Vector[ValueType]): Unit =
      val frame = CtrlFrame(kind, startTypes, endTypes, operandStack.size)
      ctrlStack += frame
      pushVals(startTypes)

    def popCtrl(): CtrlFrame =
      if ctrlStack.isEmpty then
        fail("end without matching block/loop/if/function")
      val frame = ctrlStack.last
      popVals(frame.endTypes)
      if operandStack.size != frame.baseHeight then
        fail(s"end of ${frame.kind}: stack height ${operandStack.size}, expected ${frame.baseHeight} (extra values on stack)")
      val _ = ctrlStack.remove(ctrlStack.size - 1)
      frame

    def unreachable(): Unit =
      val frame = topFrame
      while operandStack.size > frame.baseHeight do
        val _ = operandStack.remove(operandStack.size - 1)
      frame.unreachable = true

    /** Label-types of a control frame — what `br N` to it carries.
      * For `Loop`, branches re-enter the loop body with its params
      * fresh on the stack; for everything else, branches exit the
      * frame with its results. */
    def labelTypes(frame: CtrlFrame): Vector[ValueType] =
      if frame.kind == CtrlKind.Loop then frame.startTypes else frame.endTypes

    def labelFrame(n: Int): CtrlFrame =
      if n < 0 || n >= ctrlStack.length then
        fail(s"branch index $n out of range (have ${ctrlStack.length} labels)")
      ctrlStack(ctrlStack.length - 1 - n)

    // --- LEB / immediate readers ----
    //
    // The validator never advances `pc` past a malformed immediate
    // without erroring first — each helper fails atomically. opPC
    // (the start of the current instruction) is preserved so any
    // failure points at the offending opcode, not at the immediate.

    def readU32(): Int =
      Leb128.readU32(body, pc) match
        case Right((v, np)) => pc = np; v
        case Left(e)        => throw new ValFail(e)

    def readS32(): Int =
      Leb128.readS32(body, pc) match
        case Right((v, np)) => pc = np; v
        case Left(e)        => throw new ValFail(e)

    def readS64(): Long =
      Leb128.readS64(body, pc) match
        case Right((v, np)) => pc = np; v
        case Left(e)        => throw new ValFail(e)

    /** Skip `n` raw bytes; used by `f32.const` (4 bytes) and
      * `f64.const` (8 bytes). */
    def skipRaw(n: Int): Unit =
      if pc + n > body.length then fail(s"truncated immediate of $n bytes")
      pc += n

    /** Read + validate one memarg (every load/store op). Phase 8.D: the
      * alignment LEB carries a bit-6 "memidx-present" flag; when set, a
      * memidx LEB follows. The validator range-checks the memidx against
      * the module's memory count. Alignment + offset values are ignored
      * by the type check (alignment is dynamic, offset doesn't affect
      * typing) — we just advance pc. */
    def skipMemArg(label: String): Unit =
      val alignFlag = readU32()
      val memIdx =
        if (alignFlag & 0x40) != 0 then readU32()
        else 0
      if memIdx < 0 || memIdx >= memoryCount then
        fail(s"$label: memidx $memIdx out of range (have $memoryCount memories)")
      val _ = readU32() // offset (discarded)
      ()

    /** Read + validate a single memidx LEB immediate. Phase 8.D introduced
      * this shape in place of the single-memory "must-be-zero reserved
      * byte" — memory.size, memory.grow, memory.fill, and (the second
      * immediate of) memory.init all take this form. */
    def readAndValidateMemIdx(label: String): Unit =
      val m = readU32()
      if m < 0 || m >= memoryCount then
        fail(s"$label: memidx $m out of range (have $memoryCount memories)")

    /** Read one laneidx byte and validate `lane < max`. Every SIMD
      * lane-immediate op (extract_lane, replace_lane) calls this. The
      * value itself is discarded — the typing rule doesn't depend on
      * which lane is touched, only that the index is in range. */
    def readLaneIdx(label: String, max: Int): Unit =
      if pc + 1 > body.length then fail(s"$label: truncated lane index")
      val lane = body(pc) & 0xff
      if lane >= max then fail(s"$label: lane $lane out of range (max $max)")
      pc += 1

    /** Read the 16-byte `i8x16.shuffle` immediate; each byte must be
      * `< 32` (lanes 0..15 source from the first v128, 16..31 from the
      * second). All 16 are advanced past in one shot. */
    def readShuffleLanes(): Unit =
      if pc + 16 > body.length then fail("i8x16.shuffle: truncated lane vector")
      var i = 0
      while i < 16 do
        val c = body(pc + i) & 0xff
        if c >= 32 then fail(s"i8x16.shuffle: lane $c out of range (max 32)")
        i += 1
      pc += 16

    // --- walker ----

    /** Walk the function body, dispatching each opcode to its
      * type-check. The walker assumes the implicit `Function` ctrl
      * frame has been pushed by the caller; when the outer `end`
      * pops it the loop exits. */
    def walk(): Unit =
      while ctrlStack.nonEmpty do
        if pc >= body.length then
          fail("missing function-body end")
        opPC = pc
        val op = body(pc) & 0xff
        pc += 1
        dispatch(op)
      // After the function frame has been popped, `pc` should equal
      // body.length (the outer end byte was the last byte). Any extra
      // bytes after the function-level `end` are spec-malformed.
      if pc != body.length then
        opPC = pc
        fail(s"${body.length - pc} extra bytes after function-body end")

    /** Dispatch one opcode. Mirrors `Interpreter.step` and
      * `skipImmediates` opcode-for-opcode, replacing concrete execution
      * with type-check (pop expected types, push declared results).
      * Any opcode not enumerated here surfaces as `UnknownOpcode`
      * — the same error variant the interpreter uses, so a fresh
      * opcode addition fails loudly here too. */
    def dispatch(op: Int): Unit = op match

      // === control flow =================================================

      case 0x00 => unreachable()                                                // unreachable
      case 0x01 => ()                                                           // nop
      case 0x02 | 0x03 | 0x04 | 0x06 =>                                         // block / loop / if / try
        val (_, np) = Interpreter.readBlocktype(body, pc, types) match
          case Right(t) => t
          case Left(e)  => throw new ValFail(e)
        pc = np
        val ft = resolveBlockSig()
        op match
          case 0x02 =>
            popVals(ft.params)
            pushCtrl(CtrlKind.Block, ft.params, ft.results)
          case 0x03 =>
            popVals(ft.params)
            pushCtrl(CtrlKind.Loop, ft.params, ft.results)
          case 0x04 =>
            popVal(ValueType.I32Type)
            popVals(ft.params)
            pushCtrl(CtrlKind.If, ft.params, ft.results)
          case _ =>                                                             // 0x06 try
            popVals(ft.params)
            pushCtrl(CtrlKind.Try, ft.params, ft.results)
      case 0x05 =>                                                              // else
        val frame = popCtrl()
        if frame.kind != CtrlKind.If then
          fail(s"else matched a non-if frame: ${frame.kind}")
        pushCtrl(CtrlKind.Else, frame.startTypes, frame.endTypes)
      case 0x07 =>                                                              // catch tagidx
        val tagIdx = readU32()
        if tagIdx < 0 || tagIdx >= tagTypes.length then
          fail(s"catch: tag index $tagIdx out of range (have ${tagTypes.length} tags)")
        val frame = popCtrl()
        // Catch ends the previous try/catch region and starts a new one.
        if frame.kind != CtrlKind.Try && frame.kind != CtrlKind.Catch then
          fail(s"catch matched a non-try/catch frame: ${frame.kind}")
        pushCtrl(CtrlKind.Catch, tagTypes(tagIdx), frame.endTypes)
      case 0x19 =>                                                              // catch_all
        val frame = popCtrl()
        if frame.kind != CtrlKind.Try && frame.kind != CtrlKind.Catch then
          fail(s"catch_all matched a non-try/catch frame: ${frame.kind}")
        pushCtrl(CtrlKind.CatchAll, Vector.empty, frame.endTypes)
      case 0x18 =>                                                              // delegate labelidx
        val labelIdx = readU32()
        val frame = popCtrl()
        if frame.kind != CtrlKind.Try then
          fail(s"delegate matched a non-try frame: ${frame.kind}")
        // The label-frame depth here is computed *after* popCtrl, so labelidx
        // 0 names the immediately-enclosing frame (the one that was below the
        // try on the ctrl stack). The target must be a Try or the Function
        // frame — anything else has no exception-handler semantics.
        if labelIdx < 0 || labelIdx >= ctrlStack.length then
          fail(s"delegate: label index $labelIdx out of range (have ${ctrlStack.length} labels)")
        val target = ctrlStack(ctrlStack.length - 1 - labelIdx)
        if target.kind != CtrlKind.Try && target.kind != CtrlKind.Function then
          fail(s"delegate target must be try or function frame, got ${target.kind}")
        pushVals(frame.endTypes)
      case 0x08 =>                                                              // throw tagidx
        val tagIdx = readU32()
        if tagIdx < 0 || tagIdx >= tagTypes.length then
          fail(s"throw: tag index $tagIdx out of range (have ${tagTypes.length} tags)")
        popVals(tagTypes(tagIdx))
        unreachable()
      case 0x09 =>                                                              // rethrow labelidx
        val labelIdx = readU32()
        if labelIdx < 0 || labelIdx >= ctrlStack.length then
          fail(s"rethrow: label index $labelIdx out of range (have ${ctrlStack.length} labels)")
        val target = ctrlStack(ctrlStack.length - 1 - labelIdx)
        if target.kind != CtrlKind.Catch && target.kind != CtrlKind.CatchAll then
          fail(s"rethrow target must be a catch/catch_all frame, got ${target.kind}")
        unreachable()
      case 0x0a =>                                                              // throw_ref
        popVal(ValueType.ExnRefType)
        unreachable()
      case 0x1f =>                                                              // try_table blocktype catchvec
        // Same blocktype shape as block/loop/if/try. Resolve through the
        // shared decoder so future blocktype extensions land here too.
        val (_, np) = Interpreter.readBlocktype(body, pc, types) match
          case Right(t) => t
          case Left(e)  => throw new ValFail(e)
        pc = np
        val ft = resolveBlockSig()
        // Then the catch-clause vector (re-decoded via the same shared
        // reader the pre-scan uses, so byte-offsets stay in lockstep).
        val handlers = Interpreter.readTryTableCatches(body, pc) match
          case Right((hs, np2)) => pc = np2; hs
          case Left(e)          => throw new ValFail(e)
        popVals(ft.params)
        pushCtrl(CtrlKind.TryTable, ft.params, ft.results)
        // Validate each handler clause. labelidx is counted with the
        // TryTable frame on the ctrl stack — so labelidx 0 is the
        // try_table itself, and a catch targeting 0 means "exit the
        // try_table with the handler's payload on the stack".
        handlers.foreach { h =>
          val (payloadTypes, lbl) = h match
            case Interpreter.TryTableHandler.Catch(tagIdx, l) =>
              if tagIdx < 0 || tagIdx >= tagTypes.length then
                fail(s"try_table catch: tag index $tagIdx out of range (have ${tagTypes.length} tags)")
              (tagTypes(tagIdx), l)
            case Interpreter.TryTableHandler.CatchRef(tagIdx, l) =>
              if tagIdx < 0 || tagIdx >= tagTypes.length then
                fail(s"try_table catch_ref: tag index $tagIdx out of range (have ${tagTypes.length} tags)")
              (tagTypes(tagIdx) :+ ValueType.ExnRefType, l)
            case Interpreter.TryTableHandler.CatchAll(l) =>
              (Vector.empty[ValueType], l)
            case Interpreter.TryTableHandler.CatchAllRef(l) =>
              (Vector(ValueType.ExnRefType), l)
          if lbl < 0 || lbl >= ctrlStack.length then
            fail(s"try_table catch: label index $lbl out of range (have ${ctrlStack.length} labels)")
          val targetFrame = ctrlStack(ctrlStack.length - 1 - lbl)
          val targetTypes = labelTypes(targetFrame)
          if targetTypes != payloadTypes then
            fail(s"try_table catch: payload types ${payloadTypes.map(typeName).mkString("[", ",", "]")} don't match label $lbl arity ${targetTypes.map(typeName).mkString("[", ",", "]")}")
        }
      case 0x0b =>                                                              // end
        val frame = popCtrl()
        pushVals(frame.endTypes)
      case 0x0c =>                                                              // br N
        val n = readU32()
        val frame = labelFrame(n)
        popVals(labelTypes(frame))
        unreachable()
      case 0x0d =>                                                              // br_if N
        val n = readU32()
        popVal(ValueType.I32Type)
        val frame = labelFrame(n)
        popVals(labelTypes(frame))
        pushVals(labelTypes(frame))
      case 0x0e =>                                                              // br_table targets default
        val count = readU32()
        if count < 0 then fail(s"br_table: negative vec count $count")
        val targets = new Array[Int](count)
        var i = 0
        while i < count do { targets(i) = readU32(); i += 1 }
        val dflt = readU32()
        popVal(ValueType.I32Type)
        val dframe = labelFrame(dflt)
        val arity  = labelTypes(dframe).length
        // Spec: every target label must have the *same* arity. We
        // approximate the spec's "consistent type sequence" by arity
        // only (the surface here doesn't track all permitted subtype
        // relaxations); strict type equality of the individual
        // entries is implied by every-target popVals(labelTypes).
        i = 0
        while i < count do
          val lt = labelTypes(labelFrame(targets(i)))
          if lt.length != arity then
            fail(s"br_table: target $i has arity ${lt.length}, default has $arity")
          // Validate types are poppable (without consuming — push back).
          popVals(lt)
          pushVals(lt)
          i += 1
        popVals(labelTypes(dframe))
        unreachable()
      case 0x0f =>                                                              // return
        popVals(funcResults)
        unreachable()
      case 0x10 =>                                                              // call funcidx
        val idx = readU32()
        if idx < 0 || idx >= funcSigs.length then
          fail(s"call: function index $idx out of range (have ${funcSigs.length})")
        val sig = funcSigs(idx)
        popVals(sig.params)
        pushVals(sig.results)
      case 0x11 =>                                                              // call_indirect typeidx tableidx
        val typeIdx  = readU32()
        val tableIdx = readU32()
        if typeIdx < 0 || typeIdx >= types.length then
          fail(s"call_indirect: type index $typeIdx out of range")
        if tableIdx < 0 || tableIdx >= tableCount then
          fail(s"call_indirect: table index $tableIdx out of range (have $tableCount tables)")
        // Phase 8.C: call_indirect only dispatches through funcref tables.
        // An externref table doesn't carry callable funcref values; the
        // spec rejects this at validation time, not at run time.
        if tableRefTypes(tableIdx) != RefType.FuncRef then
          fail(s"call_indirect: table $tableIdx is externref (must be funcref)")
        val sig = types(typeIdx)
        popVal(ValueType.I32Type)                                               // slot index
        popVals(sig.params)
        pushVals(sig.results)
      case 0x12 =>                                                              // return_call funcidx
        // Tail-call proposal: the callee replaces the current frame. Its
        // results become the current function's return values, so the
        // callee's `results` must exactly match `funcResults`.
        val idx = readU32()
        if idx < 0 || idx >= funcSigs.length then
          fail(s"return_call: function index $idx out of range (have ${funcSigs.length})")
        val sig = funcSigs(idx)
        if sig.results != funcResults then
          fail(s"return_call: callee results ${sig.results.map(typeName).mkString("[", ",", "]")} must equal current function's results ${funcResults.map(typeName).mkString("[", ",", "]")}")
        popVals(sig.params)
        unreachable()
      case 0x13 =>                                                              // return_call_indirect typeidx tableidx
        val typeIdx  = readU32()
        val tableIdx = readU32()
        if typeIdx < 0 || typeIdx >= types.length then
          fail(s"return_call_indirect: type index $typeIdx out of range")
        if tableIdx < 0 || tableIdx >= tableCount then
          fail(s"return_call_indirect: table index $tableIdx out of range (have $tableCount tables)")
        if tableRefTypes(tableIdx) != RefType.FuncRef then
          fail(s"return_call_indirect: table $tableIdx is externref (must be funcref)")
        val sig = types(typeIdx)
        if sig.results != funcResults then
          fail(s"return_call_indirect: callee results ${sig.results.map(typeName).mkString("[", ",", "]")} must equal current function's results ${funcResults.map(typeName).mkString("[", ",", "]")}")
        popVal(ValueType.I32Type)                                               // slot index
        popVals(sig.params)
        unreachable()

      // === parametric ==================================================

      case 0x1a => val _ = popVal()                                             // drop
      case 0x1b =>                                                              // select (untyped, numeric-only)
        popVal(ValueType.I32Type)
        val t1 = popVal()
        val t2 = popVal()
        // Phase 8.C: the untyped `select` (0x1B) is now spec-restricted to
        // numeric value types — reftype operands must use the typed
        // `select t*` form (0x1C). Both operands must agree and neither
        // may be a reftype.
        def numeric(t: ValueType): Boolean = t match
          case ValueType.I32Type | ValueType.I64Type |
               ValueType.F32Type | ValueType.F64Type => true
          case _                                     => false
        (t1, t2) match
          case (AbsValue.Known(a), AbsValue.Known(b)) =>
            if a != b then
              fail(s"select: operand type mismatch (${typeName(a)} vs ${typeName(b)})")
            if !numeric(a) then
              fail(s"select: untyped form rejects reference operand (${typeName(a)}) — use select t* (0x1C)")
            pushVal(a)
          case (AbsValue.Known(a), AbsValue.Unknown) =>
            if !numeric(a) then
              fail(s"select: untyped form rejects reference operand (${typeName(a)}) — use select t* (0x1C)")
            pushVal(a)
          case (AbsValue.Unknown, AbsValue.Known(b)) =>
            if !numeric(b) then
              fail(s"select: untyped form rejects reference operand (${typeName(b)}) — use select t* (0x1C)")
            pushVal(b)
          case (AbsValue.Unknown, AbsValue.Unknown)  => pushVal(AbsValue.Unknown)

      case 0x1c =>                                                              // select t* (typed)
        // Encoding: vec(valtype). Per the wasm-3.0 spec the vector has
        // length exactly 1 — multi-value `select` isn't enabled by any
        // shipped proposal. The typed form is what reftype operands
        // must use (the untyped `select` (0x1B) rejects them above).
        val count = readU32()
        if count != 1 then
          fail(s"select t*: vector length $count, expected 1")
        if pc >= body.length then fail("truncated select t* valtype")
        val tb = body(pc) & 0xff
        pc += 1
        val t = tb match
          case 0x7f => ValueType.I32Type
          case 0x7e => ValueType.I64Type
          case 0x7d => ValueType.F32Type
          case 0x7c => ValueType.F64Type
          case 0x70 => ValueType.FuncRefType
          case 0x6f => ValueType.ExternRefType
          case other => fail(s"select t*: unknown valtype byte 0x${other.toHexString}")
        popVal(ValueType.I32Type)                                               // cond
        popVal(t)                                                               // b
        popVal(t)                                                               // a
        pushVal(t)

      // === Phase 8.C: reference-types ====================================
      //
      // Five new top-level opcodes — ref.null / ref.is_null / ref.func plus
      // table.get / table.set — and three more under the 0xFC prefix
      // (table.grow / table.size / table.fill). All ref-typed table ops
      // pop / push values whose abstract type comes from the named
      // table's reftype.

      case 0x25 =>                                                              // table.get tableidx
        val tableIdx = readU32()
        if tableIdx < 0 || tableIdx >= tableCount then
          fail(s"table.get: table index $tableIdx out of range (have $tableCount tables)")
        popVal(ValueType.I32Type)                                               // slot index
        pushVal(ValueType.fromRef(tableRefTypes(tableIdx)))
      case 0x26 =>                                                              // table.set tableidx
        val tableIdx = readU32()
        if tableIdx < 0 || tableIdx >= tableCount then
          fail(s"table.set: table index $tableIdx out of range (have $tableCount tables)")
        popVal(ValueType.fromRef(tableRefTypes(tableIdx)))                      // value
        popVal(ValueType.I32Type)                                               // slot index

      case 0xd0 =>                                                              // ref.null reftype
        if pc + 1 > body.length then
          fail("truncated ref.null reftype immediate")
        val b = body(pc) & 0xff
        pc += 1
        RefType.fromByte(b) match
          case Some(rt) => pushVal(ValueType.fromRef(rt))
          case None     => fail(s"ref.null: unknown reftype 0x${b.toHexString}")
      case 0xd1 =>                                                              // ref.is_null
        val v = popVal()
        v match
          case AbsValue.Known(t) =>
            t match
              case ValueType.FuncRefType | ValueType.ExternRefType | ValueType.ExnRefType => ()
              case other => fail(s"ref.is_null: expected reference, got ${typeName(other)}")
          case AbsValue.Unknown  => ()
        pushVal(ValueType.I32Type)
      case 0xd2 =>                                                              // ref.func funcidx
        val idx = readU32()
        if idx < 0 || idx >= funcSigs.length then
          fail(s"ref.func: function index $idx out of range (have ${funcSigs.length})")
        if !declaredFuncs.contains(idx) then
          fail(s"ref.func: funcidx $idx is not declared (must appear in an export, start, or element segment)")
        pushVal(ValueType.FuncRefType)

      // === variables ===================================================

      case 0x20 =>                                                              // local.get N
        val idx = readU32()
        if idx < 0 || idx >= locals.length then
          fail(s"local.get $idx out of range (have ${locals.length} locals)")
        pushVal(locals(idx))
      case 0x21 =>                                                              // local.set N
        val idx = readU32()
        if idx < 0 || idx >= locals.length then
          fail(s"local.set $idx out of range (have ${locals.length} locals)")
        popVal(locals(idx))
      case 0x22 =>                                                              // local.tee N
        val idx = readU32()
        if idx < 0 || idx >= locals.length then
          fail(s"local.tee $idx out of range (have ${locals.length} locals)")
        popVal(locals(idx))
        pushVal(locals(idx))
      case 0x23 =>                                                              // global.get N
        val idx = readU32()
        if idx < 0 || idx >= globalSigs.length then
          fail(s"global.get $idx out of range (have ${globalSigs.length} globals)")
        pushVal(globalSigs(idx)._1)
      case 0x24 =>                                                              // global.set N
        val idx = readU32()
        if idx < 0 || idx >= globalSigs.length then
          fail(s"global.set $idx out of range (have ${globalSigs.length} globals)")
        if !globalSigs(idx)._2 then
          fail(s"global.set on immutable global $idx")
        popVal(globalSigs(idx)._1)

      // === memory loads / stores =======================================

      case 0x28 => memLoad(ValueType.I32Type)                                   // i32.load
      case 0x29 => memLoad(ValueType.I64Type)                                   // i64.load
      case 0x2a => memLoad(ValueType.F32Type)                                   // f32.load
      case 0x2b => memLoad(ValueType.F64Type)                                   // f64.load
      case 0x2c | 0x2d | 0x2e | 0x2f =>                                         // i32.load{8,16}_{s,u}
        memLoad(ValueType.I32Type)
      case 0x30 | 0x31 | 0x32 | 0x33 | 0x34 | 0x35 =>                           // i64.load{8,16,32}_{s,u}
        memLoad(ValueType.I64Type)
      case 0x36 => memStore(ValueType.I32Type)                                  // i32.store
      case 0x37 => memStore(ValueType.I64Type)                                  // i64.store
      case 0x38 => memStore(ValueType.F32Type)                                  // f32.store
      case 0x39 => memStore(ValueType.F64Type)                                  // f64.store
      case 0x3a | 0x3b => memStore(ValueType.I32Type)                           // i32.store{8,16}
      case 0x3c | 0x3d | 0x3e => memStore(ValueType.I64Type)                    // i64.store{8,16,32}

      case 0x3f =>                                                              // memory.size memidx
        requireMemory("memory.size")
        readAndValidateMemIdx("memory.size")
        pushVal(ValueType.I32Type)
      case 0x40 =>                                                              // memory.grow memidx
        requireMemory("memory.grow")
        readAndValidateMemIdx("memory.grow")
        popVal(ValueType.I32Type)
        pushVal(ValueType.I32Type)

      // === const ========================================================

      case 0x41 => val _ = readS32(); pushVal(ValueType.I32Type)                // i32.const
      case 0x42 => val _ = readS64(); pushVal(ValueType.I64Type)                // i64.const
      case 0x43 => skipRaw(4);        pushVal(ValueType.F32Type)                // f32.const
      case 0x44 => skipRaw(8);        pushVal(ValueType.F64Type)                // f64.const

      // === i32 numeric ===================================================

      case 0x45 => unop(ValueType.I32Type, ValueType.I32Type)                   // i32.eqz
      case 0x46 | 0x47 | 0x48 | 0x49 | 0x4a | 0x4b |
           0x4c | 0x4d | 0x4e | 0x4f =>                                          // i32.eq..ge_u → i32 i32 -> i32
        binop(ValueType.I32Type, ValueType.I32Type, ValueType.I32Type)
      case 0x67 | 0x68 | 0x69 =>                                                 // i32.clz / ctz / popcnt
        unop(ValueType.I32Type, ValueType.I32Type)
      case 0x6a | 0x6b | 0x6c | 0x6d | 0x6e | 0x6f | 0x70 |
           0x71 | 0x72 | 0x73 | 0x74 | 0x75 | 0x76 | 0x77 | 0x78 =>              // i32.add..rotr
        binop(ValueType.I32Type, ValueType.I32Type, ValueType.I32Type)

      // === i64 numeric ===================================================

      case 0x50 => unop(ValueType.I64Type, ValueType.I32Type)                   // i64.eqz → i32
      case 0x51 | 0x52 | 0x53 | 0x54 | 0x55 | 0x56 |
           0x57 | 0x58 | 0x59 | 0x5a =>                                          // i64.eq..ge_u → i64 i64 -> i32
        binop(ValueType.I64Type, ValueType.I64Type, ValueType.I32Type)
      case 0x79 | 0x7a | 0x7b =>                                                 // i64.clz / ctz / popcnt
        unop(ValueType.I64Type, ValueType.I64Type)
      case 0x7c | 0x7d | 0x7e | 0x7f | 0x80 | 0x81 | 0x82 |
           0x83 | 0x84 | 0x85 | 0x86 | 0x87 | 0x88 | 0x89 | 0x8a =>              // i64.add..rotr
        binop(ValueType.I64Type, ValueType.I64Type, ValueType.I64Type)

      // === f32 numeric ===================================================

      case 0x5b | 0x5c | 0x5d | 0x5e | 0x5f | 0x60 =>                            // f32.eq..ge → f32 f32 -> i32
        binop(ValueType.F32Type, ValueType.F32Type, ValueType.I32Type)
      case 0x8b | 0x8c | 0x8d | 0x8e | 0x8f | 0x90 | 0x91 =>                     // f32 abs..sqrt
        unop(ValueType.F32Type, ValueType.F32Type)
      case 0x92 | 0x93 | 0x94 | 0x95 | 0x96 | 0x97 | 0x98 =>                     // f32 add..copysign
        binop(ValueType.F32Type, ValueType.F32Type, ValueType.F32Type)

      // === f64 numeric ===================================================

      case 0x61 | 0x62 | 0x63 | 0x64 | 0x65 | 0x66 =>                            // f64.eq..ge → f64 f64 -> i32
        binop(ValueType.F64Type, ValueType.F64Type, ValueType.I32Type)
      case 0x99 | 0x9a | 0x9b | 0x9c | 0x9d | 0x9e | 0x9f =>                     // f64 abs..sqrt
        unop(ValueType.F64Type, ValueType.F64Type)
      case 0xa0 | 0xa1 | 0xa2 | 0xa3 | 0xa4 | 0xa5 | 0xa6 =>                     // f64 add..copysign
        binop(ValueType.F64Type, ValueType.F64Type, ValueType.F64Type)

      // === conversions ===================================================

      case 0xa7 => unop(ValueType.I64Type, ValueType.I32Type)                   // i32.wrap_i64
      case 0xa8 | 0xa9 => unop(ValueType.F32Type, ValueType.I32Type)            // i32.trunc_f32_{s,u}
      case 0xaa | 0xab => unop(ValueType.F64Type, ValueType.I32Type)            // i32.trunc_f64_{s,u}
      case 0xac | 0xad => unop(ValueType.I32Type, ValueType.I64Type)            // i64.extend_i32_{s,u}
      case 0xae | 0xaf => unop(ValueType.F32Type, ValueType.I64Type)            // i64.trunc_f32_{s,u}
      case 0xb0 | 0xb1 => unop(ValueType.F64Type, ValueType.I64Type)            // i64.trunc_f64_{s,u}
      case 0xb2 | 0xb3 => unop(ValueType.I32Type, ValueType.F32Type)            // f32.convert_i32_{s,u}
      case 0xb4 | 0xb5 => unop(ValueType.I64Type, ValueType.F32Type)            // f32.convert_i64_{s,u}
      case 0xb6 => unop(ValueType.F64Type, ValueType.F32Type)                   // f32.demote_f64
      case 0xb7 | 0xb8 => unop(ValueType.I32Type, ValueType.F64Type)            // f64.convert_i32_{s,u}
      case 0xb9 | 0xba => unop(ValueType.I64Type, ValueType.F64Type)            // f64.convert_i64_{s,u}
      case 0xbb => unop(ValueType.F32Type, ValueType.F64Type)                   // f64.promote_f32
      case 0xbc => unop(ValueType.F32Type, ValueType.I32Type)                   // i32.reinterpret_f32
      case 0xbd => unop(ValueType.F64Type, ValueType.I64Type)                   // i64.reinterpret_f64
      case 0xbe => unop(ValueType.I32Type, ValueType.F32Type)                   // f32.reinterpret_i32
      case 0xbf => unop(ValueType.I64Type, ValueType.F64Type)                   // f64.reinterpret_i64

      // === sign-extension proposal =======================================

      case 0xc0 | 0xc1 => unop(ValueType.I32Type, ValueType.I32Type)            // i32.extend8_s / 16_s
      case 0xc2 | 0xc3 | 0xc4 => unop(ValueType.I64Type, ValueType.I64Type)     // i64.extend8_s / 16_s / 32_s

      // === bulk memory subset (0xFC) =====================================

      case 0xfc =>
        val sub = readU32()
        sub match
          // sub 0..3 pop the float type and push i32; sub 4..7 push i64.
          // No further immediates. Float source: f32 for sub 0/1/4/5,
          // f64 for sub 2/3/6/7. Signedness (s/u) doesn't change the
          // stack shape — same float in, same int out.
          case 0 => unop(ValueType.F32Type, ValueType.I32Type)                  // i32.trunc_sat_f32_s
          case 1 => unop(ValueType.F32Type, ValueType.I32Type)                  // i32.trunc_sat_f32_u
          case 2 => unop(ValueType.F64Type, ValueType.I32Type)                  // i32.trunc_sat_f64_s
          case 3 => unop(ValueType.F64Type, ValueType.I32Type)                  // i32.trunc_sat_f64_u
          case 4 => unop(ValueType.F32Type, ValueType.I64Type)                  // i64.trunc_sat_f32_s
          case 5 => unop(ValueType.F32Type, ValueType.I64Type)                  // i64.trunc_sat_f32_u
          case 6 => unop(ValueType.F64Type, ValueType.I64Type)                  // i64.trunc_sat_f64_s
          case 7 => unop(ValueType.F64Type, ValueType.I64Type)                  // i64.trunc_sat_f64_u
          case 8 =>                                                             // memory.init dataidx, memidx
            requireMemory("memory.init")
            if !dataCountPresent then
              fail("memory.init requires a Data Count section (Section 12)")
            val dataIdx = readU32()
            if dataIdx < 0 || dataIdx >= dataSegmentCount then
              fail(s"memory.init: data index $dataIdx out of range (have $dataSegmentCount segments)")
            // Phase 8.D: second immediate is a memidx LEB (was a must-be-
            // zero reserved byte pre-multi-memory).
            readAndValidateMemIdx("memory.init")
            popVal(ValueType.I32Type)                                           // n
            popVal(ValueType.I32Type)                                           // src (offset into data segment)
            popVal(ValueType.I32Type)                                           // dst (offset into memory)
          case 9 =>                                                             // data.drop dataidx
            if !dataCountPresent then
              fail("data.drop requires a Data Count section (Section 12)")
            val dataIdx = readU32()
            if dataIdx < 0 || dataIdx >= dataSegmentCount then
              fail(s"data.drop: data index $dataIdx out of range (have $dataSegmentCount segments)")
          case 10 =>                                                            // memory.copy dst-memidx src-memidx
            requireMemory("memory.copy")
            // Phase 8.D: two memidx LEBs (dst, src) instead of two reserved
            // bytes. Each must be in-range.
            readAndValidateMemIdx("memory.copy dst")
            readAndValidateMemIdx("memory.copy src")
            popVal(ValueType.I32Type)                                           // n
            popVal(ValueType.I32Type)                                           // src
            popVal(ValueType.I32Type)                                           // dst
          case 11 =>                                                            // memory.fill memidx
            requireMemory("memory.fill")
            readAndValidateMemIdx("memory.fill")
            popVal(ValueType.I32Type)                                           // n
            popVal(ValueType.I32Type)                                           // value
            popVal(ValueType.I32Type)                                           // dst
          case 12 =>                                                            // table.init elemidx, tableidx
            val elemIdx  = readU32()
            val tableIdx = readU32()
            if elemIdx < 0 || elemIdx >= elemSegmentCount then
              fail(s"table.init: elem index $elemIdx out of range (have $elemSegmentCount segments)")
            if tableIdx < 0 || tableIdx >= tableCount then
              fail(s"table.init: table index $tableIdx out of range (have $tableCount tables)")
            // Phase 8.C: the segment's reftype must match the table's.
            if elemRefTypes(elemIdx) != tableRefTypes(tableIdx) then
              fail(s"table.init: elem segment $elemIdx (${elemRefTypes(elemIdx)}) doesn't match table $tableIdx (${tableRefTypes(tableIdx)})")
            popVal(ValueType.I32Type)                                           // n
            popVal(ValueType.I32Type)                                           // src (offset into elem segment)
            popVal(ValueType.I32Type)                                           // dst (offset into table)
          case 13 =>                                                            // elem.drop elemidx
            val elemIdx = readU32()
            if elemIdx < 0 || elemIdx >= elemSegmentCount then
              fail(s"elem.drop: elem index $elemIdx out of range (have $elemSegmentCount segments)")
          case 14 =>                                                            // table.copy dst-tableidx, src-tableidx
            val dstTab = readU32()
            val srcTab = readU32()
            if dstTab < 0 || dstTab >= tableCount then
              fail(s"table.copy: dst table index $dstTab out of range (have $tableCount tables)")
            if srcTab < 0 || srcTab >= tableCount then
              fail(s"table.copy: src table index $srcTab out of range (have $tableCount tables)")
            // Phase 8.C: src and dst must have the same reftype.
            if tableRefTypes(dstTab) != tableRefTypes(srcTab) then
              fail(s"table.copy: dst table $dstTab (${tableRefTypes(dstTab)}) and src table $srcTab (${tableRefTypes(srcTab)}) reftype mismatch")
            popVal(ValueType.I32Type)                                           // n
            popVal(ValueType.I32Type)                                           // src
            popVal(ValueType.I32Type)                                           // dst
          // Phase 8.C: table.grow / table.size / table.fill. Operand type
          // for grow/fill comes from the table's reftype.
          case 15 =>                                                            // table.grow tableidx
            val tableIdx = readU32()
            if tableIdx < 0 || tableIdx >= tableCount then
              fail(s"table.grow: table index $tableIdx out of range (have $tableCount tables)")
            popVal(ValueType.I32Type)                                           // delta
            popVal(ValueType.fromRef(tableRefTypes(tableIdx)))                  // fill value
            pushVal(ValueType.I32Type)                                          // previous size
          case 16 =>                                                            // table.size tableidx
            val tableIdx = readU32()
            if tableIdx < 0 || tableIdx >= tableCount then
              fail(s"table.size: table index $tableIdx out of range (have $tableCount tables)")
            pushVal(ValueType.I32Type)
          case 17 =>                                                            // table.fill tableidx
            val tableIdx = readU32()
            if tableIdx < 0 || tableIdx >= tableCount then
              fail(s"table.fill: table index $tableIdx out of range (have $tableCount tables)")
            popVal(ValueType.I32Type)                                           // n
            popVal(ValueType.fromRef(tableRefTypes(tableIdx)))                  // value
            popVal(ValueType.I32Type)                                           // dst
          case _ =>
            throw new ValFail(WasmError.UnknownOpcode(0xfc))

      // === SIMD prefix (0xFD) ============================================
      //
      // Phase 8.E chunks A + B: foundations + lane-aware loads/stores.
      // Subsequent chunks will fan this out alongside the runtime
      // implementations.
      case 0xfd =>
        val sub = readU32()
        sub match
          // --- Chunk A — foundations ----------------------------------
          case 12 =>                                                              // v128.const : 16 raw bytes
            if pc + 16 > body.length then
              fail("truncated v128.const literal")
            pc += 16
            pushVal(ValueType.V128Type)

          // --- Chunk B — loads ----------------------------------------
          //
          // Every SIMD load: pop i32 addr, push v128. The immediate is a
          // memarg, identical to the scalar loads in Phase 8.D.
          case 0 |                                                                // v128.load
               1 | 2 |                                                            // v128.load8x8_s / _u
               3 | 4 |                                                            // v128.load16x4_s / _u
               5 | 6 |                                                            // v128.load32x2_s / _u
               7 | 8 | 9 | 10 |                                                   // v128.load{8,16,32,64}_splat
               92 | 93 =>                                                         // v128.load32_zero / v128.load64_zero
            skipMemArg("v128 load")
            popVal(ValueType.I32Type)
            pushVal(ValueType.V128Type)

          // --- Chunk B — store ----------------------------------------
          //
          // Pops the v128 value first, then the i32 address (stack-top
          // is the value, just like the scalar stores).
          case 11 =>                                                              // v128.store
            skipMemArg("v128.store")
            popVal(ValueType.V128Type)
            popVal(ValueType.I32Type)

          // --- Chunk C — lane access ----------------------------------
          //
          // i8x16.shuffle: 16-byte laneidx immediate (each < 32). Both
          // sources are v128; result is v128.
          case 13 =>                                                              // i8x16.shuffle
            readShuffleLanes()
            popVal(ValueType.V128Type)
            popVal(ValueType.V128Type)
            pushVal(ValueType.V128Type)

          // i8x16.swizzle: no immediate. `s` (top) is the index vector,
          // `v` (below) is the source; result is v128.
          case 14 =>                                                              // i8x16.swizzle
            popVal(ValueType.V128Type)
            popVal(ValueType.V128Type)
            pushVal(ValueType.V128Type)

          // *.splat — scalar → v128. Sub-opcode table below picks the
          // scalar input type per shape; i8x16/i16x8 truncate from i32.
          case 15 => simdSplat(ValueType.I32Type)                                 // i8x16.splat
          case 16 => simdSplat(ValueType.I32Type)                                 // i16x8.splat
          case 17 => simdSplat(ValueType.I32Type)                                 // i32x4.splat
          case 18 => simdSplat(ValueType.I64Type)                                 // i64x2.splat
          case 19 => simdSplat(ValueType.F32Type)                                 // f32x4.splat
          case 20 => simdSplat(ValueType.F64Type)                                 // f64x2.splat

          // *.extract_lane — v128 + 1-byte lane imm → scalar. i8x16 and
          // i16x8 each split into _s/_u variants; the wider shapes have
          // a single (signed-irrelevant) form because the destination is
          // already at least as wide as the source lane.
          case 21 => simdExtract("i8x16.extract_lane_s", 16, ValueType.I32Type)
          case 22 => simdExtract("i8x16.extract_lane_u", 16, ValueType.I32Type)
          case 24 => simdExtract("i16x8.extract_lane_s",  8, ValueType.I32Type)
          case 25 => simdExtract("i16x8.extract_lane_u",  8, ValueType.I32Type)
          case 27 => simdExtract("i32x4.extract_lane",    4, ValueType.I32Type)
          case 29 => simdExtract("i64x2.extract_lane",    2, ValueType.I64Type)
          case 31 => simdExtract("f32x4.extract_lane",    4, ValueType.F32Type)
          case 33 => simdExtract("f64x2.extract_lane",    2, ValueType.F64Type)

          // *.replace_lane — v128 + 1-byte lane imm + scalar → v128.
          // Scalar is at stack-top (popped first), v128 below.
          case 23 => simdReplace("i8x16.replace_lane", 16, ValueType.I32Type)
          case 26 => simdReplace("i16x8.replace_lane",  8, ValueType.I32Type)
          case 28 => simdReplace("i32x4.replace_lane",  4, ValueType.I32Type)
          case 30 => simdReplace("i64x2.replace_lane",  2, ValueType.I64Type)
          case 32 => simdReplace("f32x4.replace_lane",  4, ValueType.F32Type)
          case 34 => simdReplace("f64x2.replace_lane",  2, ValueType.F64Type)

          // --- Chunk D — integer arithmetic ---------------------------
          //
          // Two typing rules cover all 29 ops: unary v128 → v128 (abs,
          // neg) and binary (v128, v128) → v128 (everything else,
          // including saturating + mul + avgr_u).

          case 0x60 | 0x61 |                                                      // i8x16 abs/neg
               0x80 | 0x81 |                                                      // i16x8 abs/neg
               0xA0 | 0xA1 |                                                      // i32x4 abs/neg
               0xC0 | 0xC1 =>                                                     // i64x2 abs/neg
            unop(ValueType.V128Type, ValueType.V128Type)

          case 0x6E | 0x6F | 0x70 | 0x71 | 0x72 | 0x73 | 0x7B |                   // i8x16
               0x8E | 0x8F | 0x90 | 0x91 | 0x92 | 0x93 | 0x95 | 0x9B |            // i16x8
               0xAE | 0xB1 | 0xB5 |                                               // i32x4
               0xCE | 0xD1 | 0xD5 =>                                              // i64x2
            binop(ValueType.V128Type, ValueType.V128Type, ValueType.V128Type)

          // --- Chunk E — shifts + min/max -----------------------------
          //
          // Shifts have shape `(v128, i32) → v128` — the shift count is
          // a regular operand-stack i32, NOT an immediate (note the
          // mismatch with skipImmediates, which sees no operand past the
          // sub-opcode because the i32 lives on the stack). Min/max are
          // the same `(v128, v128) → v128` shape as chunk-D binaries.

          case 0x6B | 0x6C | 0x6D |                                               // i8x16 shl / shr_s / shr_u
               0x8B | 0x8C | 0x8D |                                               // i16x8 shl / shr_s / shr_u
               0xAB | 0xAC | 0xAD |                                               // i32x4 shl / shr_s / shr_u
               0xCB | 0xCC | 0xCD =>                                              // i64x2 shl / shr_s / shr_u
            binop(ValueType.V128Type, ValueType.I32Type, ValueType.V128Type)

          case 0x76 | 0x77 | 0x78 | 0x79 |                                        // i8x16 min/max _s/_u
               0x96 | 0x97 | 0x98 | 0x99 |                                        // i16x8 min/max _s/_u
               0xB6 | 0xB7 | 0xB8 | 0xB9 =>                                       // i32x4 min/max _s/_u
            binop(ValueType.V128Type, ValueType.V128Type, ValueType.V128Type)

          // Chunk F — float arithmetic. Unary `v128 → v128` for rounding /
          // abs / neg / sqrt; binary `v128 v128 → v128` for add/sub/mul/div
          // and min/max/pmin/pmax.

          case 0x67 | 0x68 | 0x69 | 0x6A |                                        // f32x4 ceil/floor/trunc/nearest
               0x74 | 0x75 | 0x7A | 0x94 |                                        // f64x2 ceil/floor/trunc/nearest
               0xE0 | 0xE1 | 0xE3 |                                                // f32x4 abs/neg/sqrt
               0xEC | 0xED | 0xEF =>                                               // f64x2 abs/neg/sqrt
            unop(ValueType.V128Type, ValueType.V128Type)

          case 0xE4 | 0xE5 | 0xE6 | 0xE7 |                                        // f32x4 add/sub/mul/div
               0xE8 | 0xE9 | 0xEA | 0xEB |                                        // f32x4 min/max/pmin/pmax
               0xF0 | 0xF1 | 0xF2 | 0xF3 |                                        // f64x2 add/sub/mul/div
               0xF4 | 0xF5 | 0xF6 | 0xF7 =>                                       // f64x2 min/max/pmin/pmax
            binop(ValueType.V128Type, ValueType.V128Type, ValueType.V128Type)

          // --- Chunk G.1 — bitwise + reductions -------------------------
          //
          // Bitwise: `not` is unary; `and`/`andnot`/`or`/`xor` are binary;
          // `bitselect` is the only ternary SIMD op (3 v128 operands,
          // 1 v128 result). Reductions all produce a single i32 from a
          // v128 — `any_true` ignores lane shape; `all_true` / `bitmask`
          // are shape-aware (the per-lane reading lives in stepFd, not
          // here — the validator only enforces the stack shape).

          case 0x4D =>                                                            // v128.not
            unop(ValueType.V128Type, ValueType.V128Type)

          case 0x4E | 0x4F | 0x50 | 0x51 =>                                       // and / andnot / or / xor
            binop(ValueType.V128Type, ValueType.V128Type, ValueType.V128Type)

          case 0x52 =>                                                            // v128.bitselect
            ternop(ValueType.V128Type, ValueType.V128Type, ValueType.V128Type, ValueType.V128Type)

          case 0x53 |                                                             // v128.any_true
               0x63 | 0x83 | 0xA3 | 0xC3 |                                        // *.all_true
               0x64 | 0x84 | 0xA4 | 0xC4 =>                                       // *.bitmask
            unop(ValueType.V128Type, ValueType.I32Type)

          // --- Chunk G.2 — 48 comparison ops ------------------------------
          //
          // Every compare is v128×v128 → v128. Signedness, lane shape, and
          // NaN semantics live in stepFd; the validator only enforces the
          // operand+result shape. Three blocks of 10 (i8x16/i16x8/i32x4) +
          // i64x2's 6 (signed-only per spec — no `_u` forms) + 6 each for
          // f32x4 / f64x2 = 48 in total.

          case 0x23 | 0x24 | 0x25 | 0x26 | 0x27 | 0x28 |
               0x29 | 0x2A | 0x2B | 0x2C |                                        // i8x16  10 cmps
               0x2D | 0x2E | 0x2F | 0x30 | 0x31 | 0x32 |
               0x33 | 0x34 | 0x35 | 0x36 |                                        // i16x8  10 cmps
               0x37 | 0x38 | 0x39 | 0x3A | 0x3B | 0x3C |
               0x3D | 0x3E | 0x3F | 0x40 |                                        // i32x4  10 cmps
               0xD6 | 0xD7 | 0xD8 | 0xD9 | 0xDA | 0xDB |                          // i64x2   6 cmps (signed-only)
               0x41 | 0x42 | 0x43 | 0x44 | 0x45 | 0x46 |                          // f32x4   6 cmps
               0x47 | 0x48 | 0x49 | 0x4A | 0x4B | 0x4C =>                         // f64x2   6 cmps
            binop(ValueType.V128Type, ValueType.V128Type, ValueType.V128Type)

          // --- Chunk H — narrow / extend / extadd_pairwise / extmul + ---
          // ---           f-i conv / demote / promote  (42 ops)        ---
          //
          // Two sub-groups by operand stack shape:
          //   * binary v128×v128 → v128: narrow (4) + extmul (12) = 16.
          //   * unary v128 → v128: extend (12) + extadd_pairwise (4) +
          //                       trunc_sat / convert (8) + demote /
          //                       promote (2) = 26.

          case 0x65 | 0x66 | 0x85 | 0x86 |                                        // narrow
               0x9C | 0x9D | 0x9E | 0x9F |                                        // extmul i8→i16
               0xBC | 0xBD | 0xBE | 0xBF |                                        // extmul i16→i32
               0xDC | 0xDD | 0xDE | 0xDF =>                                       // extmul i32→i64
            binop(ValueType.V128Type, ValueType.V128Type, ValueType.V128Type)

          case 0x87 | 0x88 | 0x89 | 0x8A |                                        // extend i8→i16
               0xA7 | 0xA8 | 0xA9 | 0xAA |                                        // extend i16→i32
               0xC7 | 0xC8 | 0xC9 | 0xCA |                                        // extend i32→i64
               0x7C | 0x7D | 0x7E | 0x7F |                                        // extadd_pairwise
               0x5E | 0x5F |                                                      // demote / promote
               0xF8 | 0xF9 | 0xFA | 0xFB |                                        // trunc_sat / convert (f32x4 forms)
               0xFC | 0xFD | 0xFE | 0xFF =>                                       // trunc_sat / convert (f64x2 forms)
            unop(ValueType.V128Type, ValueType.V128Type)

          // --- Chunk I — dot product + load_lane / store_lane (9 ops) ------
          //
          // `i32x4.dot_i16x8_s` is just another binary `(v128, v128) → v128`
          // — the per-lane pairwise multiply-then-add lives in stepFd.
          //
          // `v128.load{8,16,32,64}_lane` / `v128.store{8,16,32,64}_lane` are
          // the only SIMD ops that carry BOTH a memarg AND a 1-byte lane
          // immediate. Lane bound = 16 / 8 / 4 / 2 depending on access
          // width. Operand stack for load_lane: pops v128 src (top) +
          // i32 addr (below), pushes the modified v128. store_lane is the
          // same pop pattern but pushes nothing.

          case 0xBA =>                                                            // i32x4.dot_i16x8_s
            binop(ValueType.V128Type, ValueType.V128Type, ValueType.V128Type)

          case 0x54 => simdLoadLane("v128.load8_lane",  16)
          case 0x55 => simdLoadLane("v128.load16_lane",  8)
          case 0x56 => simdLoadLane("v128.load32_lane",  4)
          case 0x57 => simdLoadLane("v128.load64_lane",  2)

          case 0x58 => simdStoreLane("v128.store8_lane",  16)
          case 0x59 => simdStoreLane("v128.store16_lane",  8)
          case 0x5A => simdStoreLane("v128.store32_lane",  4)
          case 0x5B => simdStoreLane("v128.store64_lane",  2)

          // --- Relaxed SIMD (20 ops) ---------------------------------------
          //
          // The proposal's "relaxed" flavour leaves a handful of edge cases
          // (NaN handling on min/max, out-of-range trunc, swizzle indices
          // ≥ 16) implementation-defined; the typing rules are conventional.
          // 0x100 swizzle, 0x10D..0x110 min/max, 0x111 q15mulr, 0x112 dot_s,
          // and the four trunc/laneselect families pop two v128s; the four
          // *_madd / *_nmadd ops and 0x113 dot_add are ternary.

          case 0x101 | 0x102 | 0x103 | 0x104 =>                                // *.relaxed_trunc_*
            unop(ValueType.V128Type, ValueType.V128Type)

          case 0x100 |                                                          // i8x16.relaxed_swizzle
               0x10D | 0x10E | 0x10F | 0x110 |                                  // f*.relaxed_min / relaxed_max
               0x111 | 0x112 =>                                                 // q15mulr_s, dot_i8x16_i7x16_s
            binop(ValueType.V128Type, ValueType.V128Type, ValueType.V128Type)

          case 0x105 | 0x106 | 0x107 | 0x108 |                                  // f*.relaxed_madd / relaxed_nmadd
               0x109 | 0x10A | 0x10B | 0x10C |                                  // *.relaxed_laneselect (bitselect-shaped: a, b, mask)
               0x113 =>                                                          // i32x4.relaxed_dot_i8x16_i7x16_add_s
            ternop(ValueType.V128Type, ValueType.V128Type, ValueType.V128Type, ValueType.V128Type)

          case _ =>
            throw new ValFail(WasmError.UnknownOpcode(0xfd))

      // === unhandled ===================================================

      case other => throw new ValFail(WasmError.UnknownOpcode(other))

    // --- typing-rule shortcuts ----

    /** popVal(in); pushVal(out) — every unary form. */
    def unop(in: ValueType, out: ValueType): Unit =
      popVal(in)
      pushVal(out)

    /** popVal(b); popVal(a); pushVal(out) — every binary form. Pops in
      * reverse order because the bottom operand is deeper on the
      * stack. */
    def binop(a: ValueType, b: ValueType, out: ValueType): Unit =
      popVal(b)
      popVal(a)
      pushVal(out)

    /** popVal(c); popVal(b); popVal(a); pushVal(out) — only used by
      * `v128.bitselect` so far. Same reverse-order rule as binop. */
    def ternop(a: ValueType, b: ValueType, c: ValueType, out: ValueType): Unit =
      popVal(c)
      popVal(b)
      popVal(a)
      pushVal(out)

    /** Walk a memory load: addr=i32 → result type. Memory must exist;
      * the memarg's memidx must be in range (Phase 8.D). */
    def memLoad(out: ValueType): Unit =
      requireMemory("memory load")
      skipMemArg("memory load")
      popVal(ValueType.I32Type)
      pushVal(out)

    /** Walk a memory store: addr=i32, value=t. Memory must exist;
      * the memarg's memidx must be in range. */
    def memStore(t: ValueType): Unit =
      requireMemory("memory store")
      skipMemArg("memory store")
      popVal(t)
      popVal(ValueType.I32Type)

    /** Phase 8.E.C: every `*.splat` is "pop scalar, push v128". The
      * lane-shape is implicit in the sub-opcode — i8x16/i16x8/i32x4
      * each take an i32 scalar (the high bits are truncated at runtime),
      * the wider shapes take their natural type. */
    def simdSplat(in: ValueType): Unit =
      popVal(in)
      pushVal(ValueType.V128Type)

    /** Phase 8.E.C: `*.extract_lane` reads a 1-byte lane index (< max),
      * pops a v128, and pushes the scalar lane value. The i8x16/i16x8
      * split into `_s/_u` lives at the opcode level — the validator
      * only sees one fixed output type. */
    def simdExtract(label: String, max: Int, out: ValueType): Unit =
      readLaneIdx(label, max)
      popVal(ValueType.V128Type)
      pushVal(out)

    /** Phase 8.E.C: `*.replace_lane` reads a 1-byte lane index (< max),
      * pops the scalar (top), pops the v128 (below), and pushes the
      * modified v128. */
    def simdReplace(label: String, max: Int, scalar: ValueType): Unit =
      readLaneIdx(label, max)
      popVal(scalar)
      popVal(ValueType.V128Type)
      pushVal(ValueType.V128Type)

    /** Phase 8.E.I: `v128.load{8,16,32,64}_lane` carries a memarg
      * followed by a 1-byte lane index (< max). Pops the v128 src (top),
      * pops the i32 addr (below), pushes the modified v128. */
    def simdLoadLane(label: String, max: Int): Unit =
      requireMemory(label)
      skipMemArg(label)
      readLaneIdx(label, max)
      popVal(ValueType.V128Type)
      popVal(ValueType.I32Type)
      pushVal(ValueType.V128Type)

    /** Phase 8.E.I: `v128.store{8,16,32,64}_lane` carries a memarg
      * followed by a 1-byte lane index (< max). Pops the v128 src (top),
      * pops the i32 addr (below). No push. */
    def simdStoreLane(label: String, max: Int): Unit =
      requireMemory(label)
      skipMemArg(label)
      readLaneIdx(label, max)
      popVal(ValueType.V128Type)
      popVal(ValueType.I32Type)

    /** Resolve the blocktype at `opPC + 1` into a full `FuncType` so
      * we can pop+push the actual types. For the inline blocktype
      * forms (empty / single-result) we re-decode the byte to pick
      * the type. Multi-value blocktypes resolve their typeidx via a
      * second decode pass on the same offset. */
    def resolveBlockSig(): FuncType =
      // The blocktype byte sat at `opPC + 1`. Re-decode to get the
      // actual types. (Cheap — one byte, occasionally a few-byte SLEB.)
      val pos = opPC + 1
      val b = body(pos) & 0xff
      b match
        case 0x40 => FuncType(Vector.empty, Vector.empty)
        case 0x7f => FuncType(Vector.empty, Vector(ValueType.I32Type))
        case 0x7e => FuncType(Vector.empty, Vector(ValueType.I64Type))
        case 0x7d => FuncType(Vector.empty, Vector(ValueType.F32Type))
        case 0x7c => FuncType(Vector.empty, Vector(ValueType.F64Type))
        // Phase 8.C: reftype-valued blocktypes — `(block (result funcref))`
        // and `(block (result externref))` are both legal.
        case 0x70 => FuncType(Vector.empty, Vector(ValueType.FuncRefType))
        case 0x6f => FuncType(Vector.empty, Vector(ValueType.ExternRefType))
        // Phase 8.E: v128-valued blocktypes — `(block (result v128))`.
        case 0x7b => FuncType(Vector.empty, Vector(ValueType.V128Type))
        // try_table proposal: exnref-valued blocktype.
        case 0x69 => FuncType(Vector.empty, Vector(ValueType.ExnRefType))
        case _    =>
          Leb128.readS32(body, pos) match
            case Right((idx, _)) if idx >= 0 && idx < types.length =>
              types(idx)
            case _ =>
              // The first decode in `dispatch` already validated this;
              // if we get here something is structurally wrong.
              fail("blocktype: internal error resolving signature")

    /** Memory ops require at least one declared memory in the module. */
    def requireMemory(label: String): Unit =
      if memoryCount == 0 then
        fail(s"$label: module has no memory")

end Validator
