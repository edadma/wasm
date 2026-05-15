package io.github.edadma.wasm.wasi

import io.github.edadma.wasm.Runtime

import WasiTestSupport.{check, test}

/** Phase 7.D + 7.E.4 + 7.F — drives real rustc-built `wasm32-wasip1`
  * binaries through the shim end-to-end.
  *
  * 7.D ([[real_rust_hello.rs]]):
  *
  *   fn main() {
  *       println!("Hello, WASI!");
  *   }
  *
  * Built with `cargo build --target wasm32-wasip1 --release` using a
  * `Cargo.toml` of `opt-level = "s" + lto = true + codegen-units = 1 +
  * panic = "abort" + strip = true`. The resulting `.wasm` imports four
  * preview1 syscalls (environ_get, environ_sizes_get, fd_write, proc_exit),
  * exports a `_start` plus its memory + table, and reaches for the
  * sign-extension proposal (`i32.extend8_s`), the bulk-memory subset
  * (`memory.copy`, `memory.fill`), and `br_table`. Those gaps all
  * surfaced at Phase 7.D and were closed in `interp/` with their own
  * regression tests; this test is the integration check that proves the
  * gaps stay closed together.
  *
  * 7.E.4 ([[real_rust_fileread.rs]]):
  *
  *   fn main() {
  *       let s = std::fs::read_to_string("/sandbox/hello.txt")
  *           .expect("failed to read /sandbox/hello.txt");
  *       print!("{}", s);
  *   }
  *
  * Same release profile. The binary exercises the full 7.E surface
  * (`fd_prestat_get`, `fd_prestat_dir_name`, `path_open`, `fd_read`,
  * `fd_seek`, `fd_filestat_get`, `fd_close`) plus whatever else Rust's
  * `std::fs::read_to_string` reaches for — every gap surfaced here got
  * its own regression test in `WasiFsTests` before the integration test
  * went green.
  *
  * 7.F ([[real_rust_filewrite.rs]]):
  *
  *   fn main() {
  *       let path     = "/sandbox/out.txt";
  *       let contents = "Hello from rustc-wasm32-wasip1 file write!\n";
  *       std::fs::write(path, contents).expect(...);
  *   }
  *
  * `std::fs::write` is sugar for `OpenOptions::new().write(true)
  * .create(true).truncate(true).open(p)` + `write_all` + drop — so this
  * exercises `path_open` with `OFLAGS_CREAT | OFLAGS_TRUNC`, `fd_write`
  * to a non-stdio fd (routed through the FdTable in 7.F), and the
  * close-on-drop path. The test seeds [[InMemoryPreopen]] with an
  * empty map, runs the binary, and inspects `bytesOf("out.txt")` for
  * the written content. Stderr is pinned empty: a Rust panic on the
  * `.expect(...)` would land there.
  *
  * All three tests print to a [[WasiContext.Collecting]] sink and assert
  * on captured stdout / file post-state — the part of the contract most
  * "obviously broken" if anything regresses.
  */
object WasiRealRustTests:

  def run(): Unit =
    println()
    println("-- WasiRealRustTests --")

    test("rustc-built hello: _start exits 0 and prints 'Hello, WASI!'") {
      val collecting = WasiContext.collecting()
      val inst = Runtime.instantiate(
        WasiFixtures.real_rust_hello,
        Seq(Wasi.preview1(collecting.context)),
      ) match
        case Right(i) => i
        case Left(e)  => throw new AssertionError(s"instantiate failed: $e")

      Wasi.run(inst) match
        case Right(code) =>
          check(code == 0, s"exit code=$code (want 0)")
        case Left(err) =>
          check(false, s"Wasi.run failed: $err")

      val out = collecting.stdoutString
      check(out == "Hello, WASI!\n", s"stdout=${quote(out)} (want \"Hello, WASI!\\n\")")
      // stderr is silent for a clean run — pin it so a future panic-print
      // or trace-flush appearing on stderr would FAIL loudly rather than
      // being ignored.
      val errOut = collecting.stderrString
      check(errOut.isEmpty, s"stderr expected empty, got ${quote(errOut)}")
    }

    test("rustc-built fileread: reads /sandbox/hello.txt via std::fs::read_to_string") {
      val contents = "Hello from /sandbox/hello.txt — read via std::fs in rustc-wasm32-wasip1.\n"
      val bytes    = contents.getBytes("UTF-8")
      val preopen  = WasiContext.Preopen.inMemory("/sandbox", Map("hello.txt" -> bytes))
      val ctx      = WasiContext.collecting(preopens = Seq(preopen))

      val inst = Runtime.instantiate(
        WasiFixtures.real_rust_fileread,
        Seq(Wasi.preview1(ctx.context)),
      ) match
        case Right(i) => i
        case Left(e)  => throw new AssertionError(s"instantiate failed: $e")

      Wasi.run(inst) match
        case Right(code) =>
          check(code == 0, s"exit code=$code (want 0)")
        case Left(err) =>
          check(false, s"Wasi.run failed: $err")

      val out = ctx.stdoutString
      check(out == contents, s"stdout=${quote(out)} (want ${quote(contents)})")
      // stderr empty: a panic-print (e.g. failed .expect) would land here.
      val errOut = ctx.stderrString
      check(errOut.isEmpty, s"stderr expected empty, got ${quote(errOut)}")
    }

    test("rustc-built filewrite: writes /sandbox/out.txt via std::fs::write") {
      // Empty in-memory preopen: the binary must CREAT the file before
      // writing. Asserting `paths == Seq("out.txt")` post-run also pins
      // that no spurious intermediates land in the FS.
      val expected = "Hello from rustc-wasm32-wasip1 file write!\n"
      val preopen  = WasiContext.Preopen.inMemory("/sandbox")
      val ctx      = WasiContext.collecting(preopens = Seq(preopen))

      val inst = Runtime.instantiate(
        WasiFixtures.real_rust_filewrite,
        Seq(Wasi.preview1(ctx.context)),
      ) match
        case Right(i) => i
        case Left(e)  => throw new AssertionError(s"instantiate failed: $e")

      Wasi.run(inst) match
        case Right(code) =>
          check(code == 0, s"exit code=$code (want 0)")
        case Left(err) =>
          check(false, s"Wasi.run failed: $err")

      // Post-state: exactly one file at "out.txt" with the bytes Rust
      // handed std::fs::write.
      check(preopen.paths == Seq("out.txt"),
            s"paths=${preopen.paths} (want Seq(out.txt))")
      preopen.bytesOf("out.txt") match
        case Some(bytes) =>
          val got = new String(bytes, "UTF-8")
          check(got == expected, s"file=${quote(got)} (want ${quote(expected)})")
        case None =>
          check(false, "out.txt did not appear in the InMemoryFs")

      // Stdout/stderr both silent — std::fs::write doesn't print and a
      // failing .expect would land on stderr.
      check(ctx.stdoutString.isEmpty,
            s"stdout expected empty, got ${quote(ctx.stdoutString)}")
      check(ctx.stderrString.isEmpty,
            s"stderr expected empty, got ${quote(ctx.stderrString)}")
    }

  /** Escape control characters so a failing assertion is debuggable.
    * Cross-platform without depending on Scala-stdlib quoting helpers
    * that don't exist on every backend. */
  private def quote(s: String): String =
    val sb = new StringBuilder
    sb += '"'
    s.foreach {
      case '\n' => sb ++= "\\n"
      case '\r' => sb ++= "\\r"
      case '\t' => sb ++= "\\t"
      case '"'  => sb ++= "\\\""
      case '\\' => sb ++= "\\\\"
      case c if c < ' ' => sb ++= f"\\x$c%02x"
      case c    => sb += c
    }
    sb += '"'
    sb.toString
