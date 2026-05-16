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
  * `tables` is one [[RuntimeTable]] per declared table — each carrying its
  * own reference-type tag, max-size cap, and a mutable `Array[Value]` of
  * slot entries (`RefNull` for empty slots, `RefFunc(idx)` / `RefExtern(...)`
  * for populated funcref / externref slots). Phase 8.C surfaces `table.set`,
  * `table.grow`, `table.fill`, `table.get`, and `table.size`, all of which
  * mutate or read this array. `types` is the module's function-type vector
  * — kept on the instance so `call_indirect`'s dynamic signature check can
  * resolve a typeidx against the original `FuncType` rather than against
  * the called slot's `ResolvedFunc.signature` directly.
  */
final class ModuleInstance private[wasm] (
    private val funcs: IndexedSeq[Interpreter.ResolvedFunc],
    /** Linear memories, indexed by memidx. Phase 8.D promotes this from a
      * single `Memory` to an `Array[Memory]` so multi-memory modules work
      * end-to-end. Backwards-compat: `.memory` returns the first entry (or
      * a zero-page placeholder if the module has none), keeping the
      * existing public API + WASI shim contract intact. */
    val memories: Array[Memory],
    private val globals: Array[Value],
    private val globalMutable: Array[Boolean],
    private val tables: Array[RuntimeTable],
    private val types: Vector[FuncType],
    private val exportFuncs: Map[String, Int],
    private val exportGlobals: Map[String, Int],
    private val exportMemories: Map[String, Int],
    // Phase 8.B: bulk-memory state. Both `dataBytes`/`dataDropped` and
    // `elemRefs`/`elemDropped` persist across `invoke` calls — `data.drop`
    // / `elem.drop` flips bits that subsequent `memory.init` / `table.init`
    // calls observe. Phase 8.C widens element segments from `Vector[Int]`
    // (funcidxs) to `Vector[Value]` (typed refs).
    private val dataBytes:   Array[Array[Byte]],
    private val dataDropped: Array[Boolean],
    private val elemRefs:    Array[Vector[Value]],
    private val elemDropped: Array[Boolean],
    /** EH proposal: payload params per tag (imports first, then defs).
      * Empty if the module declared no tags. Threaded through to every
      * fresh interpreter so `throw tagidx` knows how many values to pop. */
    private val tagParams:   Array[Vector[ValueType]],
):

  /** Public access to memory 0. Most callers only have one memory and
    * don't need to distinguish; for those this stays the friendly accessor
    * it always was. Multi-memory modules should reach for `.memories`
    * directly. If the module has *no* memory, returns a zero-page
    * placeholder so callers don't have to handle Option themselves. */
  val memory: Memory =
    if memories.length > 0 then memories(0) else new Memory(0)

  /** Invoke an exported function. Each call gets a fresh interpreter so
    * memory, globals, and tables persist across calls but the value/call
    * stacks don't. */
  def invoke(name: String, args: Seq[Value] = Seq.empty): Either[WasmError, Seq[Value]] =
    exportFuncs.get(name) match
      case None      => Left(WasmError.ExportNotFound(name))
      case Some(idx) => new Interpreter(
        funcs, memories, globals, globalMutable, tables, types,
        dataBytes, dataDropped, elemRefs, elemDropped, tagParams,
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

  /** Look up an exported memory by name. Phase 8.D surface: most modules
    * export a single `"memory"` and the existing `.memory` accessor
    * suffices; multi-memory modules can iterate `.memories` and
    * cross-reference names through this map. */
  def exportedMemory(name: String): Either[WasmError, Memory] =
    exportMemories.get(name) match
      case None      => Left(WasmError.ExportNotFound(name))
      case Some(idx) => Right(memories(idx))

/** A runtime-side table. Slots are typed [[Value]]s — `RefNull` for empty
  * slots, `RefFunc` / `RefExtern` for populated ones — and the table
  * supports runtime-side resizing via `table.grow` (Phase 8.C).
  *
  * `slots` is exposed as a `var` so `table.copy` can run `System.arraycopy`
  * over the underlying arrays directly. Outside that one path, every
  * read/write goes through `get` / `set` for consistency. */
final class RuntimeTable(val refType: RefType, val max: Option[Int]):
  /** Backing array. `var` because `table.grow` swaps in a larger array;
    * `table.copy` reads it directly for `System.arraycopy`. */
  var slots: Array[Value] = Array.empty[Value]

  def size: Int = slots.length

  /** Allocate with `initial` slots prefilled with `fill`. Used by
    * `Runtime.build` at instantiation; `fill` is the table's typed null. */
  def allocate(initial: Int, fill: Value): Unit =
    val arr = new Array[Value](initial)
    var k = 0
    while k < initial do { arr(k) = fill; k += 1 }
    slots = arr

  /** `table.grow` — append `delta` slots, each set to `fill`. Returns the
    * previous size on success, or `-1` if the grow exceeds the declared
    * max (or overflows Int range). Caller surfaces -1 via the wasm stack. */
  def grow(delta: Int, fill: Value): Int =
    val oldSize = slots.length
    if delta < 0 then return -1
    val newSizeL = oldSize.toLong + delta.toLong
    val capL     = max.map(_.toLong).getOrElse(Int.MaxValue.toLong)
    if newSizeL > capL || newSizeL > Int.MaxValue then -1
    else
      val arr = new Array[Value](newSizeL.toInt)
      System.arraycopy(slots, 0, arr, 0, oldSize)
      var k = oldSize
      while k < arr.length do { arr(k) = fill; k += 1 }
      slots = arr
      oldSize

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

    // Phase 8.D follow-up: two parallel HostModule surfaces.
    //   `functions`      — single-memory host functions (HostFunc).
    //   `functionsMulti` — multi-memory-aware host functions (HostFuncMulti).
    // A name present in both maps resolves to the multi-memory form;
    // single-memory functions are normalised by wrapping at resolution
    // time so the interpreter's HostBound dispatch path is uniform
    // (always HostFuncMulti). WASI and EnvModule predate this surface
    // and continue to expose only `functions` — they take memidx 0
    // implicitly through the wrapper below.
    val hostsSingle: Map[String, Map[String, HostFunc]] =
      hostModules.iterator.map(m => m.name -> m.functions).toMap
    val hostsMulti: Map[String, Map[String, HostFuncMulti]] =
      hostModules.iterator.map(m => m.name -> m.functionsMulti).toMap

    val funcs = ArrayBuffer.empty[Interpreter.ResolvedFunc]

    // === imports ============================================================
    module.imports.foreach { imp =>
      val multi  = hostsMulti.get(imp.module).flatMap(_.get(imp.name))
      val single = hostsSingle.get(imp.module).flatMap(_.get(imp.name))
      val resolved: HostFuncMulti = (multi, single) match
        case (Some(m), _) => m
        case (_, Some(s)) =>
          // Wrap a single-memory function so the interpreter sees the
          // uniform multi-memory shape. memoriesView.head is always the
          // first memory (and the only memory in any single-memory
          // module — which is what `s` was written against).
          (mems, args) => s(mems.head, args)
        case _ => fail(WasmError.UnknownImport(imp.module, imp.name))
      if imp.typeIdx < 0 || imp.typeIdx >= module.types.length then
        fail(WasmError.InvalidModule(
          s"import ${imp.module}.${imp.name} references type ${imp.typeIdx}",
        ))
      funcs += Interpreter.HostBound(module.types(imp.typeIdx), resolved)
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

    // === memories ===========================================================
    // Phase 8.D: surface multiple memories per module. Zero-memory modules
    // still get an implicit zero-page placeholder so `i32.load` / `i32.store`
    // validation doesn't crash; otherwise allocate one `Memory` per binary
    // entry.
    val memories =
      if module.memories.isEmpty then Array(new Memory(0))
      else
        val arr = new Array[Memory](module.memories.size)
        var mi = 0
        while mi < module.memories.size do
          val ml = module.memories(mi)
          if ml.min < 0 || ml.min.toLong * Memory.PageSize > Int.MaxValue then
            fail(WasmError.InvalidModule(s"memory $mi: unsupported size ${ml.min} pages"))
          arr(mi) = new Memory(ml.min, ml.max)
          mi += 1
        arr

    // === data segments =====================================================
    // Active segments copy into their target memory at instantiation as they
    // did before Phase 8.B; post-init they're marked "dropped" so subsequent
    // `memory.init` with n > 0 traps OOB (the spec models this as the
    // segment's byte vector becoming empty). Passive segments keep their
    // bytes addressable as dataidx until `data.drop` flips the bit. Phase
    // 8.D: active segments may target any memidx, not just memory 0.
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
          // Multi-memory modules can target memidx ≥ 1; range-check.
          if memIdx < 0 || memIdx >= memories.length then
            fail(WasmError.InvalidModule(
              s"active data segment $di: memidx $memIdx out of range (have ${memories.length} memories)"))
          val targetMem = memories(memIdx)
          val end       = offset.toLong + bytes.length
          if offset < 0 || end > targetMem.size then fail(WasmError.MemoryOutOfBounds)
          System.arraycopy(bytes, 0, targetMem.data, offset, bytes.length)
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
        case (ValueType.FuncRefType,   RefNull(RefType.FuncRef))   => true
        case (ValueType.FuncRefType,   RefFunc(_))                 => true
        case (ValueType.ExternRefType, RefNull(RefType.ExternRef)) => true
        case (ValueType.ExternRefType, RefExtern(_))               => true
        case _                                                     => false
      if !ok then fail(WasmError.InvalidModule(s"global $gi: init value doesn't match declared type"))
      globals(gi)       = g.initialValue
      globalMutable(gi) = g.mutable
      gi += 1

    // === tables =============================================================
    // One [[RuntimeTable]] per defined table. Each slot starts as a typed
    // null (`RefNull(table.refType)`); element segments then populate the
    // active subset. Imported tables aren't surfaced yet (Phase 5), so
    // tableidx N in the binary maps 1:1 to `tables(N)` here. Each segment's
    // refs resolve against the module's whole `funcs` index space (imports
    // + defined), the same way `call funcidx` does — so a `(elem
    // (i32.const 0) func 0)` referring to the first imported function
    // resolves correctly.
    val tables: Array[RuntimeTable] = new Array[RuntimeTable](module.tables.size)
    var ti = 0
    while ti < module.tables.size do
      val t = module.tables(ti)
      if t.min < 0 then fail(WasmError.InvalidModule(s"table $ti: negative min size"))
      val rt = new RuntimeTable(t.refType, t.max)
      rt.allocate(t.min, RefNull(t.refType))
      tables(ti) = rt
      ti += 1

    // === element segments ==================================================
    // Active segments still copy into their declared table at instantiation
    // (failure = `InvalidModule`, matching data-segment trap shape). Passive
    // segments stay addressable by elemidx for `table.init`. Declarative
    // segments are runtime no-ops (their funcidxs are kept in the
    // declared-funcs set so `ref.func` resolves). All three kinds share
    // one `elemRefs` array so `table.init` / `elem.drop` can index
    // uniformly.
    val nElem       = module.elements.size
    val elemRefs    = new Array[Vector[Value]](nElem)
    val elemDropped = new Array[Boolean](nElem)
    var ei = 0
    while ei < nElem do
      val seg = module.elements(ei)
      // Validate each funcidx referenced by a RefFunc up front — applies to
      // every kind (active, passive, declarative). Externref segments only
      // contain RefNull / RefExtern, so this is a no-op for those.
      var k = 0
      while k < seg.refs.length do
        seg.refs(k) match
          case RefFunc(fi) =>
            if fi < 0 || fi >= funcs.size then
              fail(WasmError.InvalidModule(s"element segment $ei references invalid function $fi"))
          case _ => ()
        k += 1
      seg match
        case ElementSegment.Active(tableIdx, offset, segRT, refs) =>
          if tableIdx < 0 || tableIdx >= tables.length then
            fail(WasmError.InvalidModule(s"element segment $ei references invalid table $tableIdx"))
          val tab = tables(tableIdx)
          if tab.refType != segRT then
            fail(WasmError.InvalidModule(
              s"element segment $ei reftype $segRT doesn't match table $tableIdx reftype ${tab.refType}"))
          val end = offset.toLong + refs.length
          if offset < 0 || end > tab.size then
            fail(WasmError.InvalidModule(
              s"element segment $ei overflows table $tableIdx (offset=$offset, len=${refs.length}, size=${tab.size})"))
          var j = 0
          while j < refs.length do
            tab.slots(offset + j) = refs(j)
            j += 1
          elemRefs(ei)    = refs
          elemDropped(ei) = true                                            // active = "dropped right after init"
        case ElementSegment.Passive(_, refs) =>
          elemRefs(ei)    = refs
          elemDropped(ei) = false
        case ElementSegment.Declarative(_, refs) =>
          elemRefs(ei)    = refs
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

    // Phase 8.D: surface MemoryExport so multi-memory modules can name
    // each memory and the host can pull them back out by name.
    val exportMemories: Map[String, Int] = module.exports.iterator.collect {
      case MemoryExport(name, idx) =>
        if idx < 0 || idx >= memories.length then
          fail(WasmError.InvalidModule(s"export `$name` references invalid memory $idx"))
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

    // === tags (EH proposal) ================================================
    // Build the per-tag payload-types vector — imports first, then defs —
    // indexed by the unified tagidx. The validator already checked that
    // each tag's referenced functype has empty results; here we just lift
    // the params into a plain Array[Vector[ValueType]] so the interpreter
    // can do a quick arity lookup at `throw tagidx`.
    val tagParams: Array[Vector[ValueType]] = {
      val n   = module.tagImports.length + module.tags.length
      val arr = new Array[Vector[ValueType]](n)
      var k   = 0
      module.tagImports.foreach { ti =>
        arr(k) = module.types(ti.typeIdx).params
        k += 1
      }
      module.tags.foreach { t =>
        arr(k) = module.types(t.typeIdx).params
        k += 1
      }
      arr
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
        funcs.toIndexedSeq, memories, globals, globalMutable, tables, module.types,
        dataBytes, dataDropped, elemRefs, elemDropped, tagParams,
      )
      interp.invoke(startIdx, Seq.empty) match
        case Right(_) => ()
        case Left(e)  => fail(e)
    }

    new ModuleInstance(
      funcs.toIndexedSeq,
      memories,
      globals,
      globalMutable,
      tables,
      module.types,
      exportFuncs,
      exportGlobals,
      exportMemories,
      dataBytes,
      dataDropped,
      elemRefs,
      elemDropped,
      tagParams,
    )
