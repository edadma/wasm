package io.github.edadma.wasm.spec

/** A single command from a wast2json manifest.
  *
  * Only the variants our curated `.wast` slice actually emits are
  * represented; if a manifest contains a command type we don't know,
  * `SpecCommand.parse` throws and the surrounding test marks the file
  * as a hard error rather than silently skipping. That keeps coverage
  * gaps loud.
  */
private[spec] sealed trait SpecCommand:
  def line: Int

private[spec] object SpecCommand:

  /** Load `filename` from the manifest's directory and install it as the
    * "current" module for subsequent actions / asserts. If `name` is set
    * (e.g. `"$Mf"` from a `(module $Mf ...)` text-form declaration), the
    * module is also addressable by that name for later `Register`
    * commands and action-targeted invokes. */
  final case class Module(line: Int, name: Option[String], filename: String) extends SpecCommand

  /** `(register "as" $name?)` — bind a previously-loaded module to the
    * host name `asName` so subsequent module instantiations can import
    * from it. `modName` selects which module to register; if `None`,
    * the current module is used. */
  final case class Register(line: Int, asName: String, modName: Option[String]) extends SpecCommand

  /** A top-level action whose effects are observed (typically used to
    * call into the module without checking results). */
  final case class Action(line: Int, action: ActionExpr) extends SpecCommand

  /** Run `action` and require the returned values to match `expected`
    * (NaN classes resolve per [[SpecValue.matches]]). */
  final case class AssertReturn(line: Int, action: ActionExpr, expected: Vector[SpecValue.Expected]) extends SpecCommand

  /** Run `action` and require any wasm-level trap (`Left[WasmError]` or
    * a host-mapped exception). The `text` string is for diagnostics
    * only — we don't pattern-match it against our trap messages. */
  final case class AssertTrap(line: Int, action: ActionExpr, text: String) extends SpecCommand

  /** Run `action` and require the call stack to overflow. Our
    * interpreter doesn't catch `StackOverflowError`, so the runner
    * traps the JVM error itself. */
  final case class AssertExhaustion(line: Int, action: ActionExpr, text: String) extends SpecCommand

  /** Load the binary at `filename` and require the validator to reject
    * it. `text` describes the reason in spec terms; we don't compare
    * against our diagnostic text. */
  final case class AssertInvalid(line: Int, filename: String, text: String, moduleType: String) extends SpecCommand

  /** Load the binary at `filename` and require the parser to reject it. */
  final case class AssertMalformed(line: Int, filename: String, text: String, moduleType: String) extends SpecCommand

  /** Skip command — wast2json emitted a kind we don't run (text-form
    * modules, register, link asserts, etc.). The runner tallies these
    * separately so coverage gaps stay visible. */
  final case class Skip(line: Int, reason: String) extends SpecCommand

  sealed trait ActionExpr

  final case class Invoke(modName: Option[String], field: String, args: Vector[Any]) extends ActionExpr
  final case class GetGlobal(modName: Option[String], field: String)                 extends ActionExpr

  // ----------------------------------------------------------------------

  /** Turn the parsed JSON record into a [[SpecCommand]]. */
  def parse(rec: Map[String, Any]): SpecCommand =
    val line = rec("line").asInstanceOf[Long].toInt
    rec("type") match
      case "module" =>
        Module(
          line,
          rec.get("name").map(_.asInstanceOf[String]),
          rec("filename").asInstanceOf[String],
        )

      case "register" =>
        Register(
          line,
          rec("as").asInstanceOf[String],
          rec.get("name").map(_.asInstanceOf[String]),
        )

      case "action" =>
        Action(line, parseAction(rec("action").asInstanceOf[Map[String, Any]]))

      case "assert_return" =>
        val act      = parseAction(rec("action").asInstanceOf[Map[String, Any]])
        val expected = rec("expected").asInstanceOf[Vector[Any]]
          .map(_.asInstanceOf[Map[String, Any]])
          .map(SpecValue.decodeExpected)
        AssertReturn(line, act, expected)

      case "assert_trap" =>
        val act  = parseAction(rec("action").asInstanceOf[Map[String, Any]])
        val text = rec.getOrElse("text", "").asInstanceOf[String]
        AssertTrap(line, act, text)

      case "assert_exhaustion" =>
        val act  = parseAction(rec("action").asInstanceOf[Map[String, Any]])
        val text = rec.getOrElse("text", "").asInstanceOf[String]
        AssertExhaustion(line, act, text)

      case "assert_invalid" =>
        AssertInvalid(
          line,
          rec("filename").asInstanceOf[String],
          rec.getOrElse("text", "").asInstanceOf[String],
          rec.getOrElse("module_type", "binary").asInstanceOf[String],
        )

      case "assert_malformed" =>
        AssertMalformed(
          line,
          rec("filename").asInstanceOf[String],
          rec.getOrElse("text", "").asInstanceOf[String],
          rec.getOrElse("module_type", "binary").asInstanceOf[String],
        )

      case other =>
        Skip(line, s"unsupported command type '$other'")

  private def parseAction(rec: Map[String, Any]): ActionExpr =
    val modName = rec.get("module").map(_.asInstanceOf[String])
    rec("type") match
      case "invoke" =>
        val field = rec("field").asInstanceOf[String]
        val args  = rec.getOrElse("args", Vector.empty[Any]).asInstanceOf[Vector[Any]]
          .map(_.asInstanceOf[Map[String, Any]])
          .map(SpecValue.decodeArg)
        Invoke(modName, field, args)
      case "get" =>
        GetGlobal(modName, rec("field").asInstanceOf[String])
      case other => sys.error(s"unsupported action type '$other'")
