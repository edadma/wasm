package io.github.edadma.wasm

/** Entry point for the interpreter test suite.
  *
  * Goal: virtually exhaustive coverage. Every implemented instruction, every
  * branch of the parser, every runtime error path, and every reported
  * `WasmError` variant gets at least one assertion. Bug fixes ship with a
  * regression test in the appropriate category below.
  *
  * The tests are split across self-contained category files so each one
  * stays focused enough to read top-to-bottom:
  *
  *   `NumericTests`           — i32 baseline + remaining (1.5), i64 (1.1),
  *                              f32 (1.2), f64 (1.3), conversions (1.4)
  *   `GlobalsTests`           — Phase 2 (globals)
  *   `TablesTests`            — Phase 3 (tables + call_indirect)
  *   `MemoryTests`            — memory baseline + Phase 4 (size/grow + 16-bit)
  *   `Leb128Tests`            — direct LEB128 encoder/decoder unit tests
  *   `ParserAndRuntimeTests`  — parser malformed, unsupported opcodes,
  *                              runtime/linking errors, env module smoke,
  *                              regressions, ModuleInstance accessors
  *   `MultiValueAndStartTests` — Section 8 (Start) + multi-value blocks /
  *                              loops / ifs / function signatures
  *
  * All shared state (`passed`, `failures`) and helpers (`test`, `check`,
  * `instantiate`, `callI32/64/F32/F64/V`, `expectError`, `patchByte`,
  * `patchFirst`, `b`, `Header`) live in `TestSupport`.
  *
  * Zero external deps — runs identically on JVM, Scala.js, and Scala Native.
  * Invoke:
  *
  *   sbt 'interpJVM/Test/run'
  *   sbt 'interpJS/Test/run'
  *   sbt 'interpNative/Test/run'
  */
object InterpreterTest:

  def main(args: Array[String]): Unit =
    println()
    println("== Interpreter tests ==")

    NumericTests.run()
    GlobalsTests.run()
    TablesTests.run()
    MemoryTests.run()
    Leb128Tests.run()
    ParserAndRuntimeTests.run()
    MultiValueAndStartTests.run()

    println()
    val total = TestSupport.passed + TestSupport.failures.size
    if TestSupport.failures.isEmpty then
      println(s"All $total tests passed.")
    else
      println(s"${TestSupport.failures.size} of $total tests failed:")
      TestSupport.failures.foreach(f => println(s"  - $f"))
      throw new RuntimeException(s"${TestSupport.failures.size} of $total tests failed")
