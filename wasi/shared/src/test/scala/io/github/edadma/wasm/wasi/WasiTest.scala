package io.github.edadma.wasm.wasi

/** Entry point for the wasi test suite.
  *
  * Hand-rolled to match `interp`'s zero-dep style — no scalatest /
  * munit / utest. Each backend (`wasiJVM/Test/run`, `wasiJS/Test/run`,
  * `wasiNative/Test/run`) runs the same suite identically.
  *
  * Tests are split across category files so each stays focused enough
  * to read top-to-bottom:
  *
  *   `WasiFdTests`     — fd_write, fd_close, proc_exit, Wasi.run misc
  *   `WasiArgsTests`   — args_*, environ_*
  *   `WasiClockTests`  — clock_time_get, random_get
  *
  * Shared state (`passed` / `failures`) and helpers live in
  * `WasiTestSupport`. `WasiTest.main` reads the totals at the end and
  * emits the summary (throwing on any failure so the sbt task exits
  * non-zero).
  */
object WasiTest:

  def main(args: Array[String]): Unit =
    println()
    println("== WASI Preview 1 tests ==")

    WasiFdTests.run()
    WasiArgsTests.run()
    WasiClockTests.run()

    println()
    val total = WasiTestSupport.passed + WasiTestSupport.failures.size
    if WasiTestSupport.failures.isEmpty then
      println(s"All $total WASI tests passed.")
    else
      println(s"${WasiTestSupport.failures.size} of $total WASI tests failed:")
      WasiTestSupport.failures.foreach(f => println(s"  - $f"))
      throw new RuntimeException(s"${WasiTestSupport.failures.size} of $total WASI tests failed")
