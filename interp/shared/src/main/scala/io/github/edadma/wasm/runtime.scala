package io.github.edadma.wasm

import scala.collection.mutable.ArrayBuffer

/** An instantiated module — imports resolved, memory allocated and primed,
  * exports indexed by name. Ready to `invoke`.
  *
  * `globals` and `globalMutable` are parallel arrays indexed by globalIdx.
  * They persist across `invoke` calls — that persistence is what globals
  * exist to provide. Each fresh `Interpreter` shares the same arrays, so a
  * `global.set` in one call is visible to the next.
  */
final class ModuleInstance private[wasm] (
    private val funcs: IndexedSeq[Interpreter.ResolvedFunc],
    val memory: Memory,
    private val globals: Array[Value],
    private val globalMutable: Array[Boolean],
    private val exportFuncs: Map[String, Int],
    private val exportGlobals: Map[String, Int],
):

  /** Invoke an exported function. Each call gets a fresh interpreter so
    * memory and globals persist across calls but the value/call stacks
    * don't. */
  def invoke(name: String, args: Seq[Value] = Seq.empty): Either[WasmError, Seq[Value]] =
    exportFuncs.get(name) match
      case None      => Left(WasmError.ExportNotFound(name))
      case Some(idx) => new Interpreter(funcs, memory, globals, globalMutable).invoke(idx, args)

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
      val meta = Interpreter.computeBodyMeta(body.body) match
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
    val pages = if module.memories.nonEmpty then module.memories.head.min else 0
    if pages < 0 || pages.toLong * Memory.PageSize > Int.MaxValue then
      fail(WasmError.InvalidModule(s"unsupported memory size: $pages pages"))
    val memory = new Memory(pages)

    // === active data segments ==============================================
    module.data.foreach { seg =>
      val end = seg.offset.toLong + seg.bytes.length
      if seg.offset < 0 || end > memory.size then fail(WasmError.MemoryOutOfBounds)
      System.arraycopy(seg.bytes, 0, memory.data, seg.offset, seg.bytes.length)
    }

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

    new ModuleInstance(funcs.toIndexedSeq, memory, globals, globalMutable, exportFuncs, exportGlobals)
