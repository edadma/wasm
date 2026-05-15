package io.github.edadma.wasm.cli

import io.github.edadma.wasm.wasi.{HostPreopen, WasiContext}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.annotation.unused
import scala.collection.mutable.ArrayBuffer

/** Hand-rolled JVM tests for the `wasm` CLI dispatcher.
  *
  * Zero-dep style matches `interp`'s `InterpreterTest` and `wasi`'s
  * `WasiTest`: a single object with a `main`, a mutable failures
  * buffer, and a `test(name) { body }` helper. Run via
  * `sbt 'cliJVM/Test/run'`.
  *
  * The CLI's lower-level pieces (the wasm interpreter, the WASI shim's
  * fd_write / proc_exit / etc.) are already covered by the `interp`
  * and `wasi` test suites. This file's job is the *new* surface in
  * [[Cli]]: that the unconditional `Wasi.preview1` install + the
  * default-target auto-detect (`_start` if exported, else `main`)
  * dispatch the right entry and propagate exit codes correctly.
  *
  * Fixtures are pulled from the committed `examples/` and
  * `wasi/shared/src/test/resources/fixtures/` directories via paths
  * relative to the sbt project root. That makes these tests
  * JVM-only — the JS and Native test classpaths don't see those
  * directories — which is fine: the dispatch logic is platform-free,
  * the JVM run is enough to pin it.
  */
object CliJvmTests:

  // === Tiny test framework =================================================

  private var passed: Int                   = 0
  private val failures: ArrayBuffer[String] = ArrayBuffer.empty

  private def test(name: String)(body: => Unit): Unit =
    try
      body
      passed += 1
      println(s"  OK    $name")
    catch
      case e: AssertionError =>
        failures += s"$name — ${e.getMessage}"
        println(s"  FAIL  $name — ${e.getMessage}")
      case e: Throwable =>
        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        failures += s"$name — ${e.getClass.getSimpleName}: $msg"
        println(s"  ERROR $name — ${e.getClass.getSimpleName}: $msg")

  private def check(cond: Boolean, msg: => String): Unit =
    if !cond then throw new AssertionError(msg)

  // === Platform double + Cli driver helper =================================

  /** A [[Cli.Platform]] that captures the exit code instead of calling
    * `sys.exit`. Throws [[Exited]] so the body of `Cli.run` unwinds
    * cleanly without continuing past the exit point — mirrors what
    * `sys.exit` would do in production. */
  private final class Exited(val code: Int)
      extends RuntimeException(null, null, false, false)

  private final class CapturingPlatform(@unused fixturePath: String) extends Cli.Platform:
    def readFile(path: String): Array[Byte] = Files.readAllBytes(Paths.get(path))
    def exit(code: Int): Nothing            = throw new Exited(code)
    // Delegate to the real JVM HostPreopen factory; the test suite drives
    // --preopen against actual on-disk temp directories below.
    def openPreopen(hostPath: String, virtualName: String): WasiContext.Preopen =
      HostPreopen.fromDir(hostPath, virtualName)

  /** Drive the CLI like a subprocess: redirect System.out / System.err
    * AND Scala's `Console.out` / `Console.err` to byte buffers for the
    * duration, invoke `Cli.run`, capture the intended exit code via the
    * [[Exited]] sentinel, and return all three. If the CLI returns
    * without ever calling `platform.exit` (shouldn't happen but
    * harmless), the captured code is `0`.
    *
    * Why both redirections: the CLI mixes two output styles. Byte
    * sinks like `EnvModule.default`'s putchar and `WasiContext.default`'s
    * fd_write go through `System.out.write(...)` — `System.setOut`
    * captures those. Top-level Scala `println(...)` calls (used for the
    * `--list-exports` listing and for printing invoke results) go
    * through `Console.out`, which `setOut` does NOT touch. We need
    * `Console.withOut` for those. Doing both keeps the test capture
    * faithful regardless of which surface emitted the bytes. */
  private def runCli(args: String*): (Int, String, String) =
    val outBuf = new ByteArrayOutputStream()
    val errBuf = new ByteArrayOutputStream()
    val origOut = System.out
    val origErr = System.err
    val newOut = new PrintStream(outBuf, true, StandardCharsets.UTF_8)
    val newErr = new PrintStream(errBuf, true, StandardCharsets.UTF_8)
    System.setOut(newOut)
    System.setErr(newErr)
    var code = 0
    try
      Console.withOut(newOut) {
        Console.withErr(newErr) {
          try
            Cli.run(args.toArray, new CapturingPlatform(args.headOption.getOrElse("")))
          catch case e: Exited => code = e.code
        }
      }
    finally
      newOut.flush()
      newErr.flush()
      System.setOut(origOut)
      System.setErr(origErr)
    (code, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8))

  // === Fixture paths =======================================================
  //
  // Project-relative — sbt sets the cwd to the build root for `Test/run`.

  private val HelloPutchar  = "examples/hello.wasm"
  private val HelloWasi     = "wasi/shared/src/test/resources/fixtures/hello_wasi.wasm"
  private val WasiExit42    = "wasi/shared/src/test/resources/fixtures/wasi_exit.wasm"
  private val RustFileRead  = "wasi/shared/src/test/resources/fixtures/real_rust_fileread.wasm"
  private val RustWordCount = "examples/rust/word_count.wasm"
  private val RustEnvEcho   = "examples/rust/env_echo.wasm"

  // === Tests ===============================================================

  def main(args: Array[String]): Unit =
    println()
    println("== Cli JVM tests ==")

    test("legacy main-mode: examples/hello.wasm prints via env.putchar and exits 0") {
      val (code, out, _) = runCli(HelloPutchar)
      check(code == 0, s"expected exit 0, got $code")
      check(out.contains("Hello, world!"), s"expected stdout to contain 'Hello, world!', was:\n$out")
    }

    test("WASI auto-detect: hello_wasi.wasm calls _start by default, prints via fd_write(1), exits 0") {
      val (code, out, _) = runCli(HelloWasi)
      check(code == 0, s"expected exit 0, got $code")
      check(out.contains("Hello, WASI!"), s"expected stdout to contain 'Hello, WASI!', was:\n$out")
    }

    test("proc_exit propagation: wasi_exit.wasm's _start calls proc_exit(42), CLI exits 42") {
      val (code, _, _) = runCli(WasiExit42)
      check(code == 42, s"expected exit 42 (from proc_exit), got $code")
    }

    test("explicit --invoke overrides _start auto-detect: invoking 'nwritten' on hello_wasi prints '0'") {
      // hello_wasi.wat exports `nwritten` as `() -> i32` (peek at the
      // nwritten slot). Pre-run it's still 0 — fd_write would stamp it
      // during _start, but we're skipping _start entirely with the
      // explicit invoke. So the printed result is the int 0.
      val (code, out, _) = runCli(HelloWasi, "--invoke", "nwritten")
      check(code == 0, s"expected exit 0, got $code")
      check(out.contains("I32(0)") || out.trim == "I32(0)" || out.contains("0"),
        s"expected stdout to contain the int 0 result, was:\n$out")
    }

    test("--list-exports on hello_wasi lists _start, memory, nwritten") {
      val (code, out, _) = runCli(HelloWasi, "--list-exports")
      check(code == 0, s"expected exit 0, got $code")
      check(out.contains("_start"),   s"expected _start in export listing:\n$out")
      check(out.contains("nwritten"), s"expected nwritten in export listing:\n$out")
    }

    test("unknown file: clear error and exit 1") {
      val (code, _, err) = runCli("/no/such/file.wasm")
      check(code == 1, s"expected exit 1 for missing file, got $code")
      check(err.contains("failed to read"), s"expected 'failed to read' in stderr, was:\n$err")
    }

    // === --preopen flag (Phase 7.E/F batch 5 follow-up) ===================

    test("--preopen mounts a real host directory: rustc fileread reads through it") {
      val tmp = Files.createTempDirectory("wasm-cli-preopen-").toFile
      try
        Files.writeString(tmp.toPath.resolve("hello.txt"), "from the host\n")
        val (code, out, err) = runCli(
          "--preopen", s"${tmp.getAbsolutePath}:/sandbox", RustFileRead,
        )
        check(code == 0, s"expected exit 0, got $code  err=$err")
        check(out.contains("from the host"),
          s"expected stdout to contain 'from the host', was:\n$out")
      finally
        // Best-effort cleanup; tests must not leak temp dirs.
        val _ = Files.deleteIfExists(tmp.toPath.resolve("hello.txt"))
        val _ = Files.deleteIfExists(tmp.toPath)
    }

    test("--preopen with a non-existent host directory fails cleanly with exit 1") {
      val (code, _, err) = runCli(
        "--preopen", "/this/path/does/not/exist:/sandbox", RustFileRead,
      )
      check(code == 1, s"expected exit 1, got $code")
      check(err.contains("--preopen") && err.contains("does not exist"),
        s"expected stderr to mention the bad preopen, was:\n$err")
    }

    // === wasi argv pass-through (positional args after the file) ============

    test("trailing positional args are passed to a WASI program as argv[1..]") {
      // examples/rust/word_count.wasm reads the path it's given as argv[1]
      // and prints `lines words bytes path`. We seed a small file, then run
      // word_count.wasm with that path as a trailing positional arg, and
      // expect the count line + the path on stdout.
      val tmp = Files.createTempDirectory("wasm-cli-argv-").toFile
      try
        Files.writeString(tmp.toPath.resolve("input.txt"), "one two\nthree four five\n")
        val (code, out, err) = runCli(
          "--preopen", s"${tmp.getAbsolutePath}:/data",
          RustWordCount,
          "/data/input.txt",
        )
        check(code == 0, s"expected exit 0, got $code  err=$err")
        check(out.contains("/data/input.txt"),
          s"expected stdout to contain the path the program echoed, was:\n$out")
        // 2 lines, 5 words, 24 bytes — pin all three so a regression in
        // argv passing surfaces unambiguously.
        check(out.contains("2") && out.contains("5") && out.contains("24"),
          s"expected stdout to contain the lines/words/bytes counts, was:\n$out")
      finally
        val _ = Files.deleteIfExists(tmp.toPath.resolve("input.txt"))
        val _ = Files.deleteIfExists(tmp.toPath)
    }

    test("`--` ahead of positional args separates wasi argv from CLI flag parsing") {
      // Same shape as the previous test, but with the explicit `--` token
      // between flags and positional args. scopt honours `--` as the
      // standard POSIX option terminator. The file basename (argv[0])
      // and the path (argv[1]) are reachable from the rust program either
      // way; we're just pinning that `--` doesn't break the surface.
      val tmp = Files.createTempDirectory("wasm-cli-argv-dash-").toFile
      try
        Files.writeString(tmp.toPath.resolve("a.txt"), "x\ny\nz\n")
        val (code, out, err) = runCli(
          "--preopen", s"${tmp.getAbsolutePath}:/data",
          RustWordCount,
          "--",
          "/data/a.txt",
        )
        check(code == 0, s"expected exit 0, got $code  err=$err")
        check(out.contains("/data/a.txt"),
          s"expected stdout to contain the path, was:\n$out")
      finally
        val _ = Files.deleteIfExists(tmp.toPath.resolve("a.txt"))
        val _ = Files.deleteIfExists(tmp.toPath)
    }

    // === --env flag =========================================================

    test("--env KEY=VALUE entries reach a WASI program through environ_get") {
      // env_echo prints every (k,v) pair sorted by key as "k=v\n".
      val (code, out, err) = runCli(
        "-e", "FOO=bar",
        "-e", "BAZ=qux",
        RustEnvEcho,
      )
      check(code == 0, s"expected exit 0, got $code  err=$err")
      // Sorted by key: BAZ comes before FOO.
      val expected = "BAZ=qux\nFOO=bar\n"
      check(out == expected, s"expected stdout '$expected', got:\n$out")
    }

    test("--env value may contain `=` signs (PATH=a:b:c works)") {
      val (code, out, err) = runCli(
        "-e", "PATH=/usr/bin:/bin",
        RustEnvEcho,
      )
      check(code == 0, s"expected exit 0, got $code  err=$err")
      check(out.contains("PATH=/usr/bin:/bin"),
        s"expected stdout to contain the full value with embedded colon, got:\n$out")
    }

    test("--env with an empty key fails validation before instantiation") {
      // `=value` has no key — scopt's validate-failure path.
      val (code, _, err) = runCli("-e", "=novalue", RustEnvEcho)
      check(code != 0, s"expected non-zero exit for empty env key, got $code")
      check(err.contains("env") && err.contains("key"),
        s"expected diagnostic about the empty key, got:\n$err")
    }

    test("--preopen without a colon fails validation before instantiation") {
      val (code, _, err) = runCli("--preopen", "/no/colon", RustFileRead)
      // scopt's validate-failure path takes a different exit (2 — usage
      // error) than our own early-exit (1). Either is acceptable so long
      // as we don't enter the interpreter; assertion verifies the
      // diagnostic mentions the malformed spec.
      check(code != 0, s"expected non-zero exit for bad --preopen, got $code")
      check(err.contains("preopen") || err.contains("host-path"),
        s"expected diagnostic about the spec format, was:\n$err")
    }

    println()
    val total = passed + failures.size
    if failures.isEmpty then
      println(s"All $total CLI tests passed.")
    else
      println(s"${failures.size} of $total CLI tests failed:")
      failures.foreach(f => println(s"  - $f"))
      throw new RuntimeException(s"${failures.size} of $total CLI tests failed")
