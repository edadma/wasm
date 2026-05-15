package io.github.edadma.wasm.cli

import io.github.edadma.wasm.*
import io.github.edadma.wasm.wasi.{Wasi, WasiContext}
import scopt.OParser

/** Cross-platform CLI logic — file I/O and process exit are delegated to a
  * [[Platform]] supplied by each backend's [[Main]] entry point so the parser
  * and dispatch live in one place and stay testable.
  *
  * Argument shape (configured below):
  *   <file>                       path to a .wasm binary           (required)
  *   --invoke / -i <name>         export to call                   (default: auto-detect)
  *   --args / -a <ints>           comma-separated decimal i32 args (default: none)
  *   --list-exports               print the export table and exit
  *   --help                       auto-generated help
  *   --version                    print the version
  *
  * == Default dispatch (no `--invoke`) ==
  *
  *   1. If the module exports `_start`, treat it as a WASI command-mode
  *      module: invoke `_start` via `Wasi.run` so a `proc_exit(N)` call
  *      propagates as the process exit code. This is the convention
  *      wasmtime uses, and what clang `--target=wasm32-unknown-wasi`
  *      produces.
  *   2. Otherwise, fall back to invoking `main` with the supplied `--args`
  *      and printing any return values. This preserves the original
  *      pre-WASI dispatch shape for hand-written WAT examples (see
  *      `examples/hello.wat`).
  *
  * Both `env` (interp's tiny default host) and `wasi_snapshot_preview1`
  * (the `Wasi` shim) are wired unconditionally; a module that imports
  * neither sees them as no-ops, and a module that imports one but not the
  * other gets only the imports it declared resolved. `WasiContext.default`
  * routes fd 1 / fd 2 to `System.out` / `System.err`, which is what end
  * users invoking `wasm <file>` expect.
  */
object Cli:

  /** A thin abstraction over the platform — JVM/Native use java.nio, JS uses
    * Node's `fs`; either way Cli stays free of platform-specific imports.
    *
    * `openPreopen` constructs a host-backed preopen from a real on-disk
    * directory. Each platform's `Main.scala` wires it to its own
    * `HostPreopen.fromDir` factory (JVM/Native go through `java.nio.file`
    * + `FileChannel`; JS goes through Node `fs.*Sync`). Constructor throws
    * `IllegalArgumentException` if `hostPath` isn't an existing directory;
    * [[execute]] catches that and exits with a clear error before
    * instantiation begins. */
  trait Platform:
    def readFile(path: String): Array[Byte]
    def exit(code: Int): Nothing
    def openPreopen(hostPath: String, virtualName: String): WasiContext.Preopen

  final case class Config(
      file:         String                = "",
      invoke:       Option[String]        = None,
      args:         Seq[Int]              = Nil,
      listExports:  Boolean               = false,
      preopens:     Seq[(String, String)] = Nil,
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
        .action((n, c) => c.copy(invoke = Some(n)))
        .text("name of the export to invoke (default: _start if exported, else main)"),
      opt[Seq[Int]]('a', "args")
        .valueName("n1,n2,...")
        .action((xs, c) => c.copy(args = xs))
        .text("comma-separated decimal i32 arguments to the export"),
      opt[Unit]("list-exports")
        .action((_, c) => c.copy(listExports = true))
        .text("print exported function names and exit (no invocation)"),
      // Split on the LAST `:` so Windows-style host paths like `C:\data:/d`
      // still work (host = `C:\data`, virtual = `/d`). Wasi virtual names are
      // sandboxed strings the program sees through fd_prestat_dir_name and
      // typically don't contain `:`, so this is a safe choice.
      opt[String]('p', "preopen")
        .valueName("<host-path>:<virtual-name>")
        .unbounded()
        .validate { s =>
          if s.lastIndexOf(':') < 0 then
            failure(s"--preopen value must be host-path:virtual-name, got '$s'")
          else success
        }
        .action { (s, c) =>
          val n = s.lastIndexOf(':')
          c.copy(preopens = c.preopens :+ ((s.take(n), s.drop(n + 1))))
        }
        .text("mount a host directory as a wasi preopen (repeatable)"),
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

    // Open each --preopen up front so a bad host path fails BEFORE we
    // start instantiating the module. The factory throws
    // `IllegalArgumentException` for missing / non-directory paths; we
    // surface that as a clean exit with a descriptive message.
    val preopens: Seq[WasiContext.Preopen] = cfg.preopens.map { case (host, virt) =>
      try platform.openPreopen(host, virt)
      catch case e: Throwable =>
        System.err.println(s"--preopen $host:$virt failed: ${e.getMessage}")
        platform.exit(1)
    }

    // Wire both host modules unconditionally. The interpreter only resolves
    // imports the module actually declares, so a putchar-only module sees
    // the wasi shim as inert and a wasi command-mode module sees env as
    // inert. `WasiContext.default` sends fd 1 / fd 2 to System.out /
    // System.err — exactly what `wasm <file>` users expect; any `--preopen`
    // flags add real on-disk directories on top.
    val ctx         = WasiContext.default.copy(preopens = preopens)
    val hostModules = Seq(EnvModule.default, Wasi.preview1(ctx))
    Runtime.instantiate(bytes, hostModules) match
      case Left(err)   =>
        System.err.println(s"instantiate failed: $err")
        platform.exit(1)
      case Right(inst) =>
        if cfg.listExports then
          listExports(inst, platform)
        else
          dispatch(inst, cfg, platform)

  private def listExports(inst: ModuleInstance, platform: Platform): Unit =
    val names = inst.exportedFunctionNames
    if names.isEmpty then println("(no exported functions)")
    else names.foreach(n => println(s"  $n"))
    platform.exit(0)

  /** Dispatch the configured target.
    *
    *   - `--invoke X` explicit override always wins. The result of a
    *     non-void call is printed, and a non-zero exit is reserved for
    *     interpreter-level errors.
    *   - Auto: `_start` if exported (WASI command mode — `proc_exit(N)`
    *     becomes the process exit code; a clean return is exit 0); else
    *     fall back to `main` with the supplied `--args`.
    */
  private def dispatch(inst: ModuleInstance, cfg: Config, platform: Platform): Unit =
    cfg.invoke match
      case Some(name) => invokeNamed(inst, name, cfg.args, platform)
      case None       =>
        if inst.exportedFunctionNames.contains("_start") then
          runWasiStart(inst, platform)
        else
          invokeNamed(inst, "main", cfg.args, platform)

  /** WASI command-mode entry. `proc_exit(N)` becomes the process exit
    * code; a clean return is exit 0; an interpreter error is exit 1. */
  private def runWasiStart(inst: ModuleInstance, platform: Platform): Unit =
    Wasi.run(inst, "_start") match
      case Right(code) => platform.exit(code)
      case Left(err)   =>
        System.err.println(s"runtime error in _start: $err")
        platform.exit(1)

  /** Explicit-export entry. Prints any returned values; a void export is
    * exit 0 with no output; an interpreter error is exit 1. */
  private def invokeNamed(inst: ModuleInstance, name: String, args: Seq[Int],
                          platform: Platform): Unit =
    val argVals = args.map(I32(_))
    inst.invoke(name, argVals) match
      case Left(err)      =>
        System.err.println(s"runtime error in $name: $err")
        platform.exit(1)
      case Right(Seq())   => ()                                            // void export — nothing to print
      case Right(results) => results.foreach(println)
