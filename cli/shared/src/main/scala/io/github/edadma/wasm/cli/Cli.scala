package io.github.edadma.wasm.cli

import io.github.edadma.wasm.*
import scopt.OParser

/** Cross-platform CLI logic — file I/O and process exit are delegated to a
  * [[Platform]] supplied by each backend's [[Main]] entry point so the parser
  * and dispatch live in one place and stay testable.
  *
  * Argument shape (configured below):
  *   <file>                       path to a .wasm binary           (required)
  *   --invoke / -i <name>         export to call                   (default: "main")
  *   --args / -a <ints>           comma-separated decimal i32 args (default: none)
  *   --list-exports               print the export table and exit
  *   --help                       auto-generated help
  *   --version                    print the version
  */
object Cli:

  /** A thin abstraction over the platform — JVM/Native use java.nio, JS uses
    * Node's `fs`; either way Cli stays free of platform-specific imports. */
  trait Platform:
    def readFile(path: String): Array[Byte]
    def exit(code: Int): Nothing

  final case class Config(
      file:         String       = "",
      invoke:       String       = "main",
      args:         Seq[Int]     = Nil,
      listExports:  Boolean      = false,
  )

  private val builder = OParser.builder[Config]
  private val parser  =
    import builder.*
    OParser.sequence(
      programName("wasm"),
      head("wasm", BuildInfo.version),
      arg[String]("<file>")
        .required()
        .action((p, c) => c.copy(file = p))
        .text("path to a .wasm module"),
      opt[String]('i', "invoke")
        .valueName("<export>")
        .action((n, c) => c.copy(invoke = n))
        .text("name of the export to invoke (default: main)"),
      opt[Seq[Int]]('a', "args")
        .valueName("n1,n2,...")
        .action((xs, c) => c.copy(args = xs))
        .text("comma-separated decimal i32 arguments to the export"),
      opt[Unit]("list-exports")
        .action((_, c) => c.copy(listExports = true))
        .text("print exported function names and exit (no invocation)"),
      help("help").text("print this help message"),
      version("version").text("print version and exit"),
    )

  def run(rawArgs: Array[String], platform: Platform): Unit =
    OParser.parse(parser, rawArgs, Config()) match
      case None      => platform.exit(2)                                   // scopt already printed help / errors
      case Some(cfg) => execute(cfg, platform)

  // --- internals ----------------------------------------------------------

  private def execute(cfg: Config, platform: Platform): Unit =
    val bytes =
      try platform.readFile(cfg.file)
      catch case e: Throwable =>
        System.err.println(s"failed to read ${cfg.file}: ${e.getMessage}")
        platform.exit(1)

    Runtime.instantiate(bytes, Seq(EnvModule.default)) match
      case Left(err)   =>
        System.err.println(s"instantiate failed: $err")
        platform.exit(1)
      case Right(inst) =>
        if cfg.listExports then
          listExports(inst, platform)
        else
          invoke(inst, cfg, platform)

  private def listExports(inst: ModuleInstance, platform: Platform): Unit =
    val names = inst.exportedFunctionNames
    if names.isEmpty then println("(no exported functions)")
    else names.foreach(n => println(s"  $n"))
    platform.exit(0)

  private def invoke(inst: ModuleInstance, cfg: Config, platform: Platform): Unit =
    val argVals = cfg.args.map(I32(_))
    inst.invoke(cfg.invoke, argVals) match
      case Left(err)      =>
        System.err.println(s"runtime error in ${cfg.invoke}: $err")
        platform.exit(1)
      case Right(Seq())   => ()                                            // void export — nothing to print
      case Right(results) => results.foreach(println)
