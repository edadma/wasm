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
    private val exportTables: Map[String, Int],
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
    * stacks don't.
    *
    * `tracer` defaults to [[Tracer.NoOp]] — pass a [[Tracer.Counting]]
    * (or your own implementation) to receive callbacks at each opcode,
    * function transition, throw, and trap. */
  def invoke(
      name:   String,
      args:   Seq[Value] = Seq.empty,
      tracer: Tracer     = Tracer.NoOp,
  ): Either[WasmError, Seq[Value]] =
    exportFuncs.get(name) match
      case None      => Left(WasmError.ExportNotFound(name))
      case Some(idx) => new Interpreter(
        funcs, memories, globals, globalMutable, tables, types,
        dataBytes, dataDropped, elemRefs, elemDropped, tagParams, tracer,
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

  /** Look up an exported table by name. Used by hosts that want to forward
    * one module's exported table as another module's table import — the
    * canonical example is the W3C spec testsuite's `register` + cross-
    * module-import flow. */
  def exportedTable(name: String): Either[WasmError, RuntimeTable] =
    exportTables.get(name) match
      case None      => Left(WasmError.ExportNotFound(name))
      case Some(idx) => Right(tables(idx))

  /** Declared signature of an exported function. Needed when re-exporting
    * one module's function as another module's host import — the importer
    * needs to type-check the call site against the callee's actual
    * signature. */
  def exportedFunctionType(name: String): Either[WasmError, FuncType] =
    exportFuncs.get(name) match
      case None      => Left(WasmError.ExportNotFound(name))
      case Some(idx) => Right(funcs(idx).signature)

  /** Names of all exported memories / tables / globals, in declaration
    * order. Sibling to [[exportedFunctionNames]] — used by hosts that
    * want to enumerate everything an instance exposes (e.g. the spec
    * runner's cross-module register wrapper). */
  def exportedMemoryNames: Seq[String] = exportMemories.keys.toSeq.sorted
  def exportedTableNames:  Seq[String] = exportTables.keys.toSeq.sorted
  def exportedGlobalNames: Seq[String] = exportGlobals.keys.toSeq.sorted

  /** Mutability of an exported global — needed by hosts re-exporting one
    * module's globals as another module's imports, since wasm's
    * mutability-matching rule fails the import if the host advertises
    * the wrong flavour. */
  def exportedGlobalMutability(name: String): Either[WasmError, Boolean] =
    exportGlobals.get(name) match
      case None      => Left(WasmError.ExportNotFound(name))
      case Some(idx) => Right(globalMutable(idx))

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
    val hostsGlobals: Map[String, Map[String, HostGlobal]] =
      hostModules.iterator.map(m => m.name -> m.globals).toMap

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
    // Unified memidx space: imports first (live `Memory` instances supplied
    // by host modules), then module-defined memories. Zero-memory modules
    // still get an implicit zero-page placeholder so the validator's
    // memarg checks have something to point at.
    val hostsMemories: Map[String, Map[String, Memory]] =
      hostModules.iterator.map(m => m.name -> m.memories).toMap
    val hostsTables:   Map[String, Map[String, RuntimeTable]] =
      hostModules.iterator.map(m => m.name -> m.tables).toMap

    val nMemoryImports = module.memoryImports.length
    val nMemoryDefs    = module.memories.length
    val totalMemories  = nMemoryImports + nMemoryDefs
    val memories =
      if totalMemories == 0 then Array(new Memory(0))
      else
        val arr = new Array[Memory](totalMemories)
        var mim = 0
        while mim < nMemoryImports do
          val mi = module.memoryImports(mim)
          val hm = hostsMemories.get(mi.module).flatMap(_.get(mi.name)).getOrElse {
            fail(WasmError.UnknownImport(mi.module, mi.name))
          }
          // Host's current size must be at least the import's declared min;
          // host's max (if any) must be at most the import's declared max (if any).
          if hm.currentPages < mi.limits.min then
            fail(WasmError.InvalidModule(
              s"memory import ${mi.module}.${mi.name}: host size ${hm.currentPages} pages < declared min ${mi.limits.min}"))
          (mi.limits.max, hm.maxPages) match
            case (Some(declaredMax), Some(hostMax)) if hostMax > declaredMax =>
              fail(WasmError.InvalidModule(
                s"memory import ${mi.module}.${mi.name}: host max $hostMax pages > declared max $declaredMax"))
            case (Some(_), None) =>
              fail(WasmError.InvalidModule(
                s"memory import ${mi.module}.${mi.name}: module declared a max but host memory is unbounded"))
            case _ => ()
          if mi.limits.shared && !hm.shared then
            fail(WasmError.InvalidModule(
              s"memory import ${mi.module}.${mi.name}: module declared shared but host memory is unshared"))
          arr(mim) = hm
          mim += 1
        var mdi = 0
        while mdi < nMemoryDefs do
          val ml   = module.memories(mdi)
          val midx = nMemoryImports + mdi
          if ml.min < 0 || ml.min.toLong * Memory.PageSize > Int.MaxValue then
            fail(WasmError.InvalidModule(s"memory $midx: unsupported size ${ml.min} pages"))
          arr(midx) = new Memory(ml.min, ml.max, ml.shared)
          mdi += 1
        arr

    // === globals ============================================================
    // Imported globals occupy slots 0..k-1 in the unified globalidx space;
    // module-defined globals follow. Imports must be resolved BEFORE any
    // defined-global init that references one via `global.get` — and before
    // data / element segment offsets that do the same. We build the unified
    // arrays here, eagerly, so the rest of instantiation sees the same
    // imports-first layout the validator and interpreter expect.
    val nGlobalImports = module.globalImports.length
    val nGlobalDefs    = module.globals.length
    val gN             = nGlobalImports + nGlobalDefs
    val globals        = new Array[Value](gN)
    val globalMutable  = new Array[Boolean](gN)

    var gim = 0
    while gim < nGlobalImports do
      val gi = module.globalImports(gim)
      val hg = hostsGlobals.get(gi.module).flatMap(_.get(gi.name)).getOrElse {
        fail(WasmError.UnknownImport(gi.module, gi.name))
      }
      if hg.valueType != gi.valueType then
        fail(WasmError.InvalidModule(
          s"global import ${gi.module}.${gi.name}: type mismatch (host provides ${hg.valueType}, module imports ${gi.valueType})"))
      if hg.mutable != gi.mutable then
        fail(WasmError.InvalidModule(
          s"global import ${gi.module}.${gi.name}: mutability mismatch (host=${hg.mutable}, module=${gi.mutable})"))
      globals(gim)       = hg.value
      globalMutable(gim) = gi.mutable
      gim += 1

    // `maxGlobalIdx` is the exclusive upper bound on legal globalidx in
    // this evaluation context. The validator pre-pass enforces this rule
    // structurally; here we still range-check defensively so a bad
    // module that slipped past validation surfaces a clean diagnostic
    // rather than an array-bounds exception.
    def evalConstInit(where: String, init: ConstInit, expected: ValueType, maxGlobalIdx: Int): Value =
      def eval(e: ConstInit): Value = e match
        case ConstInit.Literal(value) => value
        case ConstInit.GlobalGet(idx) =>
          if idx < 0 || idx >= maxGlobalIdx then
            fail(WasmError.InvalidModule(
              s"$where: global.get $idx out of range (max $maxGlobalIdx)"))
          globals(idx)
        case ConstInit.BinOp(opcode, lhs, rhs, ty) =>
          (eval(lhs), eval(rhs), ty, opcode) match
            case (I32(a), I32(b), ValueType.I32Type, ConstBinOp.Add) => I32(a + b)
            case (I32(a), I32(b), ValueType.I32Type, ConstBinOp.Sub) => I32(a - b)
            case (I32(a), I32(b), ValueType.I32Type, ConstBinOp.Mul) => I32(a * b)
            case (I64(a), I64(b), ValueType.I64Type, ConstBinOp.Add) => I64(a + b)
            case (I64(a), I64(b), ValueType.I64Type, ConstBinOp.Sub) => I64(a - b)
            case (I64(a), I64(b), ValueType.I64Type, ConstBinOp.Mul) => I64(a * b)
            case (l, r, _, _) =>
              fail(WasmError.InvalidModule(s"$where: const-expr arithmetic type mismatch ($l $opcode $r, declared $ty)"))
      val v = eval(init)
      val ok = (expected, v) match
        case (ValueType.I32Type, _: I32) => true
        case (ValueType.I64Type, _: I64) => true
        case (ValueType.F32Type, _: F32) => true
        case (ValueType.F64Type, _: F64) => true
        case (ValueType.V128Type, _: V128) => true
        case (ValueType.FuncRefType,   RefNull(RefType.FuncRef))   => true
        case (ValueType.FuncRefType,   RefFunc(_))                 => true
        case (ValueType.ExternRefType, RefNull(RefType.ExternRef)) => true
        case (ValueType.ExternRefType, RefExtern(_))               => true
        case (ValueType.ExnRefType,    RefNull(RefType.ExnRef))    => true
        case (ValueType.ExnRefType,    RefExn(_))                  => true
        case _                                                     => false
      if !ok then fail(WasmError.InvalidModule(s"$where: init value doesn't match declared type $expected"))
      v

    var gdi = 0
    while gdi < nGlobalDefs do
      val g    = module.globals(gdi)
      val gidx = nGlobalImports + gdi
      // Self-references and forward references aren't legal — a defined
      // global's init expression can only see globals at indices < gidx.
      globals(gidx)       = evalConstInit(s"global $gidx init", g.init, g.valueType, maxGlobalIdx = gidx)
      globalMutable(gidx) = g.mutable
      gdi += 1

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
        case DataSegment.Active(memIdx, offsetInit, bytes) =>
          // Multi-memory modules can target memidx ≥ 1; range-check.
          if memIdx < 0 || memIdx >= memories.length then
            fail(WasmError.InvalidModule(
              s"active data segment $di: memidx $memIdx out of range (have ${memories.length} memories)"))
          val offset = evalConstInit(s"data segment $di offset", offsetInit, ValueType.I32Type, maxGlobalIdx = gN) match
            case I32(v) => v
            case other  => fail(WasmError.InvalidModule(s"data segment $di offset: expected i32, got $other"))
          val targetMem = memories(memIdx)
          val end       = (offset.toLong & 0xffffffffL) + bytes.length
          if offset < 0 || end > targetMem.size then fail(WasmError.MemoryOutOfBounds)
          System.arraycopy(bytes, 0, targetMem.data, offset, bytes.length)
          dataBytes(di)   = bytes
          dataDropped(di) = true                                            // active = "dropped right after init"
        case DataSegment.Passive(bytes) =>
          dataBytes(di)   = bytes
          dataDropped(di) = false
      di += 1

    // === tables =============================================================
    // Unified tableidx space: imports first (live `RuntimeTable` instances
    // from host modules), then module-defined tables. Each segment's refs
    // resolve against the module's whole `funcs` index space (imports +
    // defined), the same way `call funcidx` does — so a `(elem
    // (i32.const 0) func 0)` referring to the first imported function
    // resolves correctly.
    val nTableImports = module.tableImports.length
    val nTableDefs    = module.tables.length
    val totalTables   = nTableImports + nTableDefs
    val tables: Array[RuntimeTable] = new Array[RuntimeTable](totalTables)
    var tim = 0
    while tim < nTableImports do
      val ti = module.tableImports(tim)
      val ht = hostsTables.get(ti.module).flatMap(_.get(ti.name)).getOrElse {
        fail(WasmError.UnknownImport(ti.module, ti.name))
      }
      if ht.refType != ti.refType then
        fail(WasmError.InvalidModule(
          s"table import ${ti.module}.${ti.name}: reftype mismatch (host=${ht.refType}, module=${ti.refType})"))
      if ht.size < ti.min then
        fail(WasmError.InvalidModule(
          s"table import ${ti.module}.${ti.name}: host size ${ht.size} < declared min ${ti.min}"))
      (ti.max, ht.max) match
        case (Some(declMax), Some(hostMax)) if hostMax > declMax =>
          fail(WasmError.InvalidModule(
            s"table import ${ti.module}.${ti.name}: host max $hostMax > declared max $declMax"))
        case (Some(_), None) =>
          fail(WasmError.InvalidModule(
            s"table import ${ti.module}.${ti.name}: module declared a max but host table is unbounded"))
        case _ => ()
      tables(tim) = ht
      tim += 1
    var ti = 0
    while ti < nTableDefs do
      val t    = module.tables(ti)
      val tidx = nTableImports + ti
      if t.min < 0 then fail(WasmError.InvalidModule(s"table $tidx: negative min size"))
      val rt = new RuntimeTable(t.refType, t.max)
      rt.allocate(t.min, RefNull(t.refType))
      tables(tidx) = rt
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
        case ElementSegment.Active(tableIdx, offsetInit, segRT, refs) =>
          if tableIdx < 0 || tableIdx >= tables.length then
            fail(WasmError.InvalidModule(s"element segment $ei references invalid table $tableIdx"))
          val tab = tables(tableIdx)
          if tab.refType != segRT then
            fail(WasmError.InvalidModule(
              s"element segment $ei reftype $segRT doesn't match table $tableIdx reftype ${tab.refType}"))
          val offset = evalConstInit(s"element segment $ei offset", offsetInit, ValueType.I32Type, maxGlobalIdx = gN) match
            case I32(v) => v
            case other  => fail(WasmError.InvalidModule(s"element segment $ei offset: expected i32, got $other"))
          val end = (offset.toLong & 0xffffffffL) + refs.length
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

    // Surface TableExport so a host can pull an exported table back out
    // by name (used by the spec runner's cross-module `register` flow,
    // which forwards one module's tables as another module's imports).
    val exportTables: Map[String, Int] = module.exports.iterator.collect {
      case TableExport(name, idx) =>
        if idx < 0 || idx >= tables.length then
          fail(WasmError.InvalidModule(s"export `$name` references invalid table $idx"))
        name -> idx
    }.toMap

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
      exportTables,
      dataBytes,
      dataDropped,
      elemRefs,
      elemDropped,
      tagParams,
    )
