package io.github.edadma.wasm.wasi

import io.github.edadma.wasm.{I32, Runtime}

import WasiTestSupport.{check, test}

/** Phase 7.D — drives a real rustc-built `wasm32-wasip1` binary through the
  * shim end-to-end. The source `real_rust_hello.rs` (committed alongside
  * the `.wasm` for documentation) is:
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
  * The test prints to a [[WasiContext.Collecting]] sink and asserts on the
  * captured stdout — that's the part of the contract that's most
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
