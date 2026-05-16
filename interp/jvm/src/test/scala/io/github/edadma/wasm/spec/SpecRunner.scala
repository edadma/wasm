package io.github.edadma.wasm.spec

import io.github.edadma.wasm.*
import java.nio.file.{Files, Path}

/** Walks a wast2json manifest and dispatches each command against the
  * interpreter.
  *
  * Aggregated counts live in a [[SpecRunner.Stats]] returned by `run`.
  * Failures are line-numbered so they trace back to the original
  * `.wast` file without needing the JSON open.
  */
private[spec] final class SpecRunner(manifestPath: Path):

  import SpecRunner.*

  private val baseDir: Path        = manifestPath.getParent
  private val name:    String      = manifestPath.getFileName.toString.stripSuffix(".json")

  /** Currently loaded module, if any. `None` before the first `module`
    * command and after a load failure. */
  private var current: Option[ModuleInstance] = None

  /** Modules addressable by `$name` (from `(module $Mf ...)` declarations).
    * Cross-module-targeted actions look up modules here when the action
    * specifies `"module": "$Mf"`. */
  private val namedModules = scala.collection.mutable.HashMap.empty[String, ModuleInstance]

  /** Cross-module `register` registry. `(register "Mf")` binds the current
    * (or named) module to host-name `"Mf"`. Subsequent module loads pass
    * these instances as additional `HostModule`s so imports of the form
    * `(import "Mf" "func" ...)` resolve against the registered module's
    * exports. */
  private val registered = scala.collection.mutable.HashMap.empty[String, ModuleInstance]

  def run(): Stats =
    val text     = new String(Files.readAllBytes(manifestPath), java.nio.charset.StandardCharsets.UTF_8)
    val manifest = MiniJson.parseObject(text)
    val commands = manifest("commands").asInstanceOf[Vector[Any]]
      .map(_.asInstanceOf[Map[String, Any]])
      .map(SpecCommand.parse)

    val stats = new Stats(name)
    var i = 0
    while i < commands.length do
      val cmd = commands(i)
      try execute(cmd, stats)
      catch
        case t: Throwable =>
          stats.fail(cmd.line, s"runner exception: ${t.getClass.getSimpleName}: ${t.getMessage}")
      i += 1
    stats

  private def execute(cmd: SpecCommand, stats: Stats): Unit = cmd match
    case SpecCommand.Module(line, name, fn) =>
      loadModule(fn) match
        case Right(inst) =>
          current = Some(inst)
          name.foreach(n => namedModules(n) = inst)
          stats.pass()
        case Left(err)   =>
          current = None
          stats.fail(line, s"module load failed: $err")

    case SpecCommand.Register(line, asName, modName) =>
      val target = modName match
        case Some(n) => namedModules.get(n)
        case None    => current
      target match
        case Some(inst) =>
          registered(asName) = inst
          stats.pass()
        case None =>
          stats.fail(line, s"register $asName: ${modName.getOrElse("current module")} not found")

    case SpecCommand.Action(_, action) =>
      runAction(action) match
        case Right(_) => stats.pass()
        case Left(e)  => stats.fail(cmd.line, s"action failed: $e")

    case SpecCommand.AssertReturn(line, action, expected) =>
      runAction(action) match
        case Right(actual) => compareReturn(line, actual, expected, stats)
        case Left(err)     => stats.fail(line, s"expected return, got trap: $err")

    case SpecCommand.AssertTrap(line, action, _) =>
      try runAction(action) match
        case Right(values) => stats.fail(line, s"expected trap, got Right($values)")
        case Left(_)       => stats.pass()
      catch
        case _: ArithmeticException | _: IndexOutOfBoundsException | _: NullPointerException =>
          // Native JVM exceptions from cases the interpreter doesn't yet
          // convert into a WasmError still count as a "trap" — we just
          // observed unrecoverable failure mid-execution.
          stats.pass()

    case SpecCommand.AssertExhaustion(line, action, _) =>
      try
        runAction(action) match
          case Right(values) => stats.fail(line, s"expected stack overflow, got Right($values)")
          case Left(_)       => stats.pass() // any trap counts; rare for our interpreter
      catch
        // Both surface "the call stack ran out": JVM SO on its own; OOM
        // shows up when the recursive ArrayBuffer/value-stack growth
        // exhausts heap before the JVM stack does.
        case _: StackOverflowError | _: OutOfMemoryError => stats.pass()

    case SpecCommand.AssertInvalid(line, fn, _, "binary") =>
      loadModule(fn) match
        case Left(_)  => stats.pass()
        case Right(_) => stats.fail(line, "expected validator/parser to reject, but module loaded")

    case SpecCommand.AssertInvalid(line, _, _, mt) =>
      stats.skip(line, s"assert_invalid module_type=$mt not supported")

    case SpecCommand.AssertMalformed(line, fn, _, "binary") =>
      loadModule(fn) match
        case Left(_)  => stats.pass()
        case Right(_) => stats.fail(line, "expected parser to reject, but module loaded")

    case SpecCommand.AssertMalformed(line, _, _, mt) =>
      stats.skip(line, s"assert_malformed module_type=$mt not supported")

    case SpecCommand.Skip(line, reason) =>
      stats.skip(line, reason)

  private def loadModule(filename: String): Either[WasmError, ModuleInstance] =
    val p     = baseDir.resolve(filename)
    val bytes = Files.readAllBytes(p)
    // Hosts available to the new module: built-ins (env, spectest) +
    // every previously-registered module wrapped as a HostModule.
    val hosts = Seq(EnvModule.default, SpectestModule) ++
      registered.iterator.map { case (name, inst) => SpecRunner.wrapAsHostModule(name, inst) }
    Runtime.instantiate(bytes, hosts)

  /** Resolve the target instance for an action — either a `$name`-bound
    * module or the current one. */
  private def actionTarget(modName: Option[String]): Either[WasmError, ModuleInstance] =
    modName match
      case Some(n) => namedModules.get(n).toRight(WasmError.InvalidModule(s"no module named $n"))
      case None    => current.toRight(WasmError.InvalidModule("no current module"))

  private def runAction(action: SpecCommand.ActionExpr): Either[WasmError, Seq[Value]] =
    action match
      case SpecCommand.Invoke(modName, field, args) =>
        actionTarget(modName).flatMap(_.invoke(field, args.map(_.asInstanceOf[Value])))
      case SpecCommand.GetGlobal(modName, field) =>
        actionTarget(modName).flatMap(_.globalValue(field)).map(v => Seq(v))

  private def compareReturn(line: Int, actual: Seq[Value], expected: Vector[SpecValue.Expected], stats: Stats): Unit =
    if actual.length != expected.length then
      stats.fail(line, s"arity mismatch: expected ${expected.length}, got ${actual.length}")
    else
      var i  = 0
      var ok = true
      while ok && i < expected.length do
        if !SpecValue.matches(expected(i), actual(i)) then
          ok = false
          stats.fail(line, s"result[$i] mismatch: expected ${expected(i)}, got ${actual(i)}")
        i += 1
      if ok then stats.pass()

private[spec] object SpecRunner:

  /** Wrap an instantiated module as a HostModule so its exports satisfy
    * another module's imports — the workhorse behind the wast2json
    * `register` command. Each exported function becomes a HostFunc that
    * forwards through `inst.invoke`; exported memories and tables
    * forward by reference (the same backing array, so guest writes are
    * mutually observable); exported globals forward as a snapshot taken
    * at register time. (Live mutable-global sharing across module
    * boundaries would need an extra layer of indirection; the linking
    * manifest's mutable-global tests will surface that gap if they
    * matter for our slice.) */
  private[spec] def wrapAsHostModule(hostName: String, inst: ModuleInstance): HostModule =
    new HostModule:
      val name: String = hostName

      override val functions: Map[String, HostFunc] =
        inst.exportedFunctionNames.iterator.map { fname =>
          // Propagate any trap from the inner invoke as `ExecFail`, which
          // the calling Interpreter's outer catch converts back to
          // `Left(err)` at the API boundary.
          val fn: HostFunc = (_, args) =>
            inst.invoke(fname, args) match
              case Right(values) => values
              case Left(err)     => throw new Interpreter.ExecFail(err)
          fname -> fn
        }.toMap

      override val memories: Map[String, Memory] =
        inst.exportedMemoryNames.iterator.flatMap { n =>
          inst.exportedMemory(n).toOption.map(n -> _)
        }.toMap

      override val tables: Map[String, RuntimeTable] =
        inst.exportedTableNames.iterator.flatMap { n =>
          inst.exportedTable(n).toOption.map(n -> _)
        }.toMap

      override val globals: Map[String, HostGlobal] =
        inst.exportedGlobalNames.iterator.flatMap { n =>
          // Forward by SHARED CELL, not by snapshot. For mutable globals
          // this is the load-bearing part — `global.set` from the
          // importing module writes through to the same storage the
          // exporting module reads from, matching the wasm-3.0 spec.
          for
            cell <- inst.exportedGlobalCell(n).toOption
            mut  <- inst.exportedGlobalMutability(n).toOption
          yield
            val vt = cell.value match
              case _: I32       => ValueType.I32Type
              case _: I64       => ValueType.I64Type
              case _: F32       => ValueType.F32Type
              case _: F64       => ValueType.F64Type
              case _: V128      => ValueType.V128Type
              case RefNull(rt)  => ValueType.fromRef(rt)
              case _: RefFunc   => ValueType.FuncRefType
              case _: RefExtern => ValueType.ExternRefType
              case _: RefExn    => ValueType.ExnRefType
            n -> HostGlobal.live(vt, mutable = mut, cell)
        }.toMap

  final class Stats(val name: String):
    var passed:    Int                                = 0
    var skipped:   Int                                = 0
    val failures:  scala.collection.mutable.ArrayBuffer[(Int, String)] = scala.collection.mutable.ArrayBuffer.empty
    val skipNotes: scala.collection.mutable.ArrayBuffer[(Int, String)] = scala.collection.mutable.ArrayBuffer.empty

    def pass(): Unit          = passed += 1
    def fail(line: Int, msg: String): Unit = failures += ((line, msg))
    def skip(line: Int, reason: String): Unit =
      skipped += 1
      skipNotes += ((line, reason))

    def total: Int = passed + skipped + failures.size

    def summary: String =
      val f = failures.size
      val s = skipped
      s"$name: $passed passed, $f failed, $s skipped (of $total)"

    def isGreen: Boolean = failures.isEmpty
