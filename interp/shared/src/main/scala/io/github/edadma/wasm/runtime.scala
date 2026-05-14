package io.github.edadma.wasm

import scala.collection.mutable.ArrayBuffer

/** An instantiated module — imports resolved, memory allocated and primed,
  * exports indexed by name. Ready to `invoke`.
  *
  * `globals` and `globalMutable` are parallel arrays indexed by globalIdx.
  * They persist across `invoke` calls — that persistence is what globals
  * exist to provide. Each fresh `Interpreter` shares the same arrays, so a
  * `global.set` in one call is visible to the next.
  *
  * `tables` is one funcidx-int array per declared table; `-1` marks a null
  * slot. The arrays are mutable in principle (Phase 8+ adds `table.set`),
  * but Phase 3 leaves them write-once at instantiation. `types` is the
  * module's function-type vector — kept on the instance so
  * `call_indirect`'s dynamic signature check can resolve a typeidx against
  * the original `FuncType` rather than against the called slot's
  * `ResolvedFunc.signature` directly.
  */
final class ModuleInstance private[wasm] (
    private val funcs: IndexedSeq[Interpreter.ResolvedFunc],
    val memory: Memory,
    private val globals: Array[Value],
    private val globalMutable: Array[Boolean],
    private val tables: Array[Array[Int]],
    private val types: Vector[FuncType],
    private val exportFuncs: Map[String, Int],
    private val exportGlobals: Map[String, Int],
    // Phase 8.B: bulk-memory state. Both `dataBytes`/`dataDropped` and
    // `elemFuncs`/`elemDropped` persist across `invoke` calls — `data.drop`
    // / `elem.drop` flips bits that subsequent `memory.init` / `table.init`
    // calls observe.
    private val dataBytes:   Array[Array[Byte]],
    private val dataDropped: Array[Boolean],
    private val elemFuncs:   Array[Vector[Int]],
    private val elemDropped: Array[Boolean],
):

  /** Invoke an exported function. Each call gets a fresh interpreter so
    * memory, globals, and tables persist across calls but the value/call
    * stacks don't. */
  def invoke(name: String, args: Seq[Value] = Seq.empty): Either[WasmError, Seq[Value]] =
    exportFuncs.get(name) match
      case None      => Left(WasmError.ExportNotFound(name))
      case Some(idx) => new Interpreter(
        funcs, memory, globals, globalMutable, tables, types,
        dataBytes, dataDropped, elemFuncs, elemDropped,
      ).invoke(idx, args)

  /** Direct access to the imports table — useful for tests that want to
    * confirm linking worked. */
  def functionCount: Int = funcs.length

  /** Names of all exported functions, sorted for stable output. Used by the
    * CLI's `--list-exports` and by tests verifying the export table after
    * instantiation. */
  def exportedFunctionNames: Seq[String] = exportFuncs.keys.toSeq.sorted

  /** Read the current value of an exported global. Used by tests to
    * confirm `global.set` mutations from inside the module without having
    * to ship a getter function — and by future host-driven introspection.
    */
  def globalValue(name: String): Either[WasmError, Value] =
    exportGlobals.get(name) match
      case None      => Left(WasmError.ExportNotFound(name))
      case Some(idx) => Right(globals(idx))

/** Linker / loader. `instantiate` does the four jobs the WASM spec assigns to
  * instantiation: resolve imports, allocate memory, initialize data segments,
  * and produce the export table. Errors map onto the public `WasmError`
  * variants — no exceptions cross the API boundary.
  */
object Runtime:

  private final class InstFail(val err: WasmError) extends RuntimeException(null, null, false, false)

  /** Convenience: parse bytes and instantiate in one step. */
  def instantiate(bytes: Array[Byte], hostModules: Seq[HostModule]): Either[WasmError, ModuleInstance] =
    Parser.parse(bytes).flatMap(instantiate(_, hostModules))

  def instantiate(module: WasmModule, hostModules: Seq[HostModule]): Either[WasmError, ModuleInstance] =
    try Right(build(module, hostModules))
    catch case e: InstFail => Left(e.err)

  private def fail(err: WasmError): Nothing = throw new InstFail(err)

  private def build(module: WasmModule, hostModules: Seq[HostModule]): ModuleInstance =
    // === Phase 6: validate every function body up front =====================
    // A passing validation means: every value-stack pop sees the right
    // type, every br lands on a real label, every block exits with the
    // right result arity, every funcidx/typeidx/tableidx/local/global
    // index is in range. The interpreter's runtime TypeMismatch / range
    // checks become assertions of validator invariants rather than
    // recoverable errors. Bad code surfaces here with a precise
    // diagnostic (function index + byte offset + expected-vs-found)
    // — exactly what a handwritten code generator needs during bring-up.
    Validator.validate(module) match
      case Right(()) => ()
      case Left(e)   => fail(e)

    val hosts: Map[String, Map[String, HostFunc]] =
      hostModules.iterator.map(m => m.name -> m.functions).toMap

    val funcs = ArrayBuffer.empty[Interpreter.ResolvedFunc]

    // === imports ============================================================
    module.imports.foreach { imp =>
      val fn = hosts.get(imp.module).flatMap(_.get(imp.name))
        .getOrElse(fail(WasmError.UnknownImport(imp.module, imp.name)))
      if imp.typeIdx < 0 || imp.typeIdx >= module.types.length then
        fail(WasmError.InvalidModule(
          s"import ${imp.module}.${imp.name} references type ${imp.typeIdx}",
        ))
      funcs += Interpreter.HostBound(module.types(imp.typeIdx), fn)
    }

    // === defined functions ==================================================
    if module.functions.size != module.codes.size then
      fail(WasmError.InvalidModule("function/code section length mismatch"))

    module.functions.lazyZip(module.codes).foreach { (typeIdx, body) =>
      if typeIdx < 0 || typeIdx >= module.types.length then
        fail(WasmError.InvalidModule(s"function references type $typeIdx"))
      val sig = module.types(typeIdx)
      val meta = Interpreter.computeBodyMeta(body.body, module.types) match
        case Right(m) => m
        case Left(e)  => fail(e)
      funcs += Interpreter.WasmFunc(
        signature  = sig,
        paramCount = sig.params.size,
        localCount = sig.params.size + body.locals.size,
        // Per-local types — params first (from sig), then declared locals.
        // Used by the interpreter to pick the right zero-init value (I32(0) vs I64(0L)).
        localTypes = sig.params ++ body.locals,
        body       = body.body,
        meta       = meta,
      )
    }

    // === memory =============================================================
    // The MVP allows at most one memory; we still keep the conditional so an
    // empty `memories` vector instantiates as a zero-page memory (some tools
    // emit modules that never declare one when no `i32.load`/`i32.store` is
    // present). The declared max — when supplied — is threaded into the
    // `Memory` so `memory.grow` returns -1 verbatim on overflow rather than
    // resizing past the host's intent.
    val pages    = if module.memories.nonEmpty then module.memories.head.min else 0
    val maxPages = if module.memories.nonEmpty then module.memories.head.max else None
    if pages < 0 || pages.toLong * Memory.PageSize > Int.MaxValue then
      fail(WasmError.InvalidModule(s"unsupported memory size: $pages pages"))
    val memory = new Memory(pages, maxPages)

    // === data segments =====================================================
    // Active segments copy into memory at instantiation as they did before
    // Phase 8.B; post-init they're marked "dropped" so subsequent
    // `memory.init` with n > 0 traps OOB (the spec models this as the
    // segment's byte vector becoming empty). Passive segments keep their
    // bytes addressable as dataidx until `data.drop` flips the bit.
    //
    // We store BOTH kinds in the same `dataBytes` array indexed by dataidx
    // so the interpreter doesn't have to translate between the binary's
    // unified index space and our runtime model.
    val nData       = module.data.size
    val dataBytes   = new Array[Array[Byte]](nData)
    val dataDropped = new Array[Boolean](nData)
    var di = 0
    while di < nData do
      module.data(di) match
        case DataSegment.Active(memIdx, offset, bytes) =>
          if memIdx != 0 then
            fail(WasmError.InvalidModule(
              s"active data segment $di: memIdx=$memIdx (multi-memory not supported yet)"))
          val end = offset.toLong + bytes.length
          if offset < 0 || end > memory.size then fail(WasmError.MemoryOutOfBounds)
          System.arraycopy(bytes, 0, memory.data, offset, bytes.length)
          dataBytes(di)   = bytes
          dataDropped(di) = true                                            // active = "dropped right after init"
        case DataSegment.Passive(bytes) =>
          dataBytes(di)   = bytes
          dataDropped(di) = false
      di += 1

    // === globals ============================================================
    // Module-defined globals only; imported globals will join the head of
    // these arrays once Phase 5 surfaces them. Init values were folded at
    // parse time (no `global.get` over imports yet), so we just shuttle them
    // into the live arrays. The mutability bit is stored next to the value
    // so the interpreter's `global.set` guard is an O(1) lookup.
    val gN            = module.globals.size
    val globals       = new Array[Value](gN)
    val globalMutable = new Array[Boolean](gN)
    var gi = 0
    while gi < gN do
      val g = module.globals(gi)
      // Defensive: a wrong-type init slipped past the parser would be a bug,
      // but a misclassified `Value` here would otherwise show up as a runtime
      // TypeMismatch much later. Check up front.
      val ok = (g.valueType, g.initialValue) match
        case (ValueType.I32Type, _: I32) => true
        case (ValueType.I64Type, _: I64) => true
        case (ValueType.F32Type, _: F32) => true
        case (ValueType.F64Type, _: F64) => true
        case _                           => false
      if !ok then fail(WasmError.InvalidModule(s"global $gi: init value doesn't match declared type"))
      globals(gi)       = g.initialValue
      globalMutable(gi) = g.mutable
      gi += 1

    // === tables =============================================================
    // One int-array per defined table; `-1` = null funcref. Imported tables
    // aren't surfaced yet (Phase 5), so tableidx N in the binary maps 1:1
    // to `tables(N)` here. Each segment's `funcIndices` resolve against the
    // module's whole `funcs` index space (imports + defined), the same way
    // `call funcidx` does — so a `(elem (i32.const 0) func 0)` referring to
    // the first imported function resolves correctly.
    val tables: Array[Array[Int]] = new Array[Array[Int]](module.tables.size)
    var ti = 0
    while ti < module.tables.size do
      val t = module.tables(ti)
      if t.min < 0 then fail(WasmError.InvalidModule(s"table $ti: negative min size"))
      val arr = new Array[Int](t.min)
      var k = 0
      while k < arr.length do { arr(k) = -1; k += 1 }
      tables(ti) = arr
      ti += 1

    // === element segments ==================================================
    // Active segments still copy into their declared table at instantiation
    // (failure = `InvalidModule`, matching data-segment trap shape). Passive
    // and declarative segments stay addressable by elemidx; declarative is
    // a runtime no-op until Phase 8.C wires `ref.func` to consult them.
    // All three kinds share one `elemFuncs` array so `table.init` /
    // `elem.drop` can index uniformly.
    val nElem      = module.elements.size
    val elemFuncs  = new Array[Vector[Int]](nElem)
    val elemDropped = new Array[Boolean](nElem)
    var ei = 0
    while ei < nElem do
      module.elements(ei) match
        case ElementSegment.Active(tableIdx, offset, idxs) =>
          if tableIdx < 0 || tableIdx >= tables.length then
            fail(WasmError.InvalidModule(s"element segment $ei references invalid table $tableIdx"))
          val tab = tables(tableIdx)
          val end = offset.toLong + idxs.length
          if offset < 0 || end > tab.length then
            fail(WasmError.InvalidModule(
              s"element segment $ei overflows table $tableIdx (offset=$offset, len=${idxs.length}, size=${tab.length})"))
          var k = 0
          while k < idxs.length do
            val fi = idxs(k)
            if fi < 0 || fi >= funcs.size then
              fail(WasmError.InvalidModule(s"element segment $ei references invalid function $fi"))
            tab(offset + k) = fi
            k += 1
          elemFuncs(ei)   = idxs
          elemDropped(ei) = true                                            // active = "dropped right after init"
        case ElementSegment.Passive(idxs) =>
          // Validate each funcidx up front — a passive segment may be used
          // by `table.init` later, but the spec requires funcidxs to be
          // in-range at instantiation, not at use.
          var k = 0
          while k < idxs.length do
            val fi = idxs(k)
            if fi < 0 || fi >= funcs.size then
              fail(WasmError.InvalidModule(s"passive element segment $ei references invalid function $fi"))
            k += 1
          elemFuncs(ei)   = idxs
          elemDropped(ei) = false
        case ElementSegment.Declarative(idxs) =>
          // Same funcidx validation as passive — declarative segments
          // pre-declare funcrefs for future `ref.func` resolution and
          // their funcidxs must be in-range. Treated as "dropped" at
          // runtime: never copied by `table.init`.
          var k = 0
          while k < idxs.length do
            val fi = idxs(k)
            if fi < 0 || fi >= funcs.size then
              fail(WasmError.InvalidModule(s"declarative element segment $ei references invalid function $fi"))
            k += 1
          elemFuncs(ei)   = idxs
          elemDropped(ei) = true
      ei += 1

    // === exports ============================================================
    val exportFuncs: Map[String, Int] = module.exports.iterator.collect {
      case FuncExport(name, idx) =>
        if idx < 0 || idx >= funcs.size then
          fail(WasmError.InvalidModule(s"export `$name` references invalid function $idx"))
        name -> idx
    }.toMap

    val exportGlobals: Map[String, Int] = module.exports.iterator.collect {
      case GlobalExport(name, idx) =>
        if idx < 0 || idx >= gN then
          fail(WasmError.InvalidModule(s"export `$name` references invalid global $idx"))
        name -> idx
    }.toMap

    // Validate any TableExport indices up front. Phase 3 doesn't ship a
    // host-side `tableValue` accessor (the surface is internal to
    // `call_indirect`), but a bogus index in the binary should still
    // surface here rather than wait for a runtime read.
    module.exports.foreach {
      case TableExport(name, idx) =>
        if idx < 0 || idx >= tables.length then
          fail(WasmError.InvalidModule(s"export `$name` references invalid table $idx"))
      case _ => ()
    }

    // === Start (Section 8) =================================================
    // Spec semantics: invoked AFTER imports + memory + data + globals +
    // tables are in place — i.e. right here, just before the
    // ModuleInstance becomes observable to callers. The function must
    // have signature `() -> ()`; any other shape (or a bogus funcidx) is
    // an `InvalidModule` at instantiation time.
    //
    // Side effects are real: a start function can `global.set` mutable
    // globals, write to linear memory, even `call` exported functions
    // through the table indirectly. Failures in the start function
    // surface verbatim through `fail`, so the whole instantiation
    // returns `Left(err)` to the caller — the partially-built instance
    // is never observable.
    module.startFunction.foreach { startIdx =>
      if startIdx < 0 || startIdx >= funcs.size then
        fail(WasmError.InvalidModule(s"start: invalid funcidx $startIdx"))
      val sig = funcs(startIdx).signature
      if sig.params.nonEmpty || sig.results.nonEmpty then
        fail(WasmError.InvalidModule(
          s"start: function $startIdx has signature $sig, expected () -> ()"))
      val interp = new Interpreter(
        funcs.toIndexedSeq, memory, globals, globalMutable, tables, module.types,
        dataBytes, dataDropped, elemFuncs, elemDropped,
      )
      interp.invoke(startIdx, Seq.empty) match
        case Right(_) => ()
        case Left(e)  => fail(e)
    }

    new ModuleInstance(
      funcs.toIndexedSeq,
      memory,
      globals,
      globalMutable,
      tables,
      module.types,
      exportFuncs,
      exportGlobals,
      dataBytes,
      dataDropped,
      elemFuncs,
      elemDropped,
    )
