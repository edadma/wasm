package io.github.edadma.wasm

import scala.collection.mutable.ArrayBuffer

/** Tests for the WebAssembly MVP interpreter.
  *
  * Zero external deps — we roll our own tiny framework (just `test`,
  * `check`, `runRight`) so the suite compiles and runs identically on JVM,
  * Scala.js, and Scala Native. Invoke with:
  *
  *   sbt 'wasmJVM/Test/run'
  *   sbt 'wasmJS/Test/run'
  *   sbt 'wasmNative/Test/run'
  *
  * Each `.wasm` fixture comes from a hand-written `.wat` (committed under
  * `src/test/resources/fixtures/`) compiled by `wat2wasm` and embedded as a
  * byte-array constant in [[Fixtures]].
  *
  * Coverage goal: every instruction in the MVP subset has at least one
  * positive assertion, plus the obvious edge cases (division traps,
  * memory bounds, sign-extension, shift modulo, etc.).
  */
object InterpreterTest:

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
        failures += s"$name — ${e.getClass.getSimpleName}: ${e.getMessage}"
        println(s"  ERROR $name — ${e.getClass.getSimpleName}: ${e.getMessage}")

  private def check(cond: Boolean, msg: => String): Unit =
    if !cond then throw new AssertionError(msg)

  private def runRight[A](e: Either[WasmError, A]): A = e match
    case Right(a)  => a
    case Left(err) => throw new AssertionError(s"unexpected error: $err")

  private def instantiate(bytes: Array[Byte], env: HostModule = EnvModule.default): ModuleInstance =
    runRight(Runtime.instantiate(bytes, Seq(env)))

  private def callI32(inst: ModuleInstance, name: String, args: Int*): Int =
    val results = runRight(inst.invoke(name, args.map(I32(_))))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case I32(v) => v

  /** Expect `invoke(name, args)` to fail with the given error. */
  private def expectError[E <: WasmError](
      inst: ModuleInstance,
      name: String,
      args: Seq[Value],
  )(matcher: WasmError => Boolean): Unit =
    inst.invoke(name, args) match
      case Right(v)  => check(false, s"$name expected error, got Right($v)")
      case Left(err) => check(matcher(err), s"$name unexpected error: $err")

  // ========================================================================

  def main(args: Array[String]): Unit =
    println()
    println("== Interpreter tests ==")

    // === module-level / basic exec ======================================

    test("arith: ((10*3)+2-7)/5 == 5 [const, mul, add, sub, div_s]") {
      val inst = instantiate(Fixtures.arith)
      check(callI32(inst, "test_arith") == 5, "wrong result")
    }

    test("locals: (3+4)^2 == 49 [local.get, local.set]") {
      val inst = instantiate(Fixtures.locals)
      check(callI32(inst, "test_locals", 3, 4) == 49, "wrong result")
    }

    test("if/else: sign with local.tee [if/else, local.tee, gt_s]") {
      val inst = instantiate(Fixtures.if_else)
      check(callI32(inst, "test_if",  5) ==  1, "positive case wrong")
      check(callI32(inst, "test_if",  0) == -1, "zero case wrong")
      check(callI32(inst, "test_if", -3) == -1, "negative case wrong")
    }

    test("loop: sum(1..n) [block, loop, br_if]") {
      val inst = instantiate(Fixtures.loop)
      check(callI32(inst, "test_loop", 10) == 55, "10 case wrong")
      check(callI32(inst, "test_loop",  1) ==  1, "1 case wrong")
      check(callI32(inst, "test_loop",  0) ==  0, "0 case wrong")
    }

    test("factorial: recursive [call, le_s]") {
      val inst = instantiate(Fixtures.factorial)
      check(callI32(inst, "fact", 0) ==    1, "0! wrong")
      check(callI32(inst, "fact", 1) ==    1, "1! wrong")
      check(callI32(inst, "fact", 5) ==  120, "5! wrong")
      check(callI32(inst, "fact", 7) == 5040, "7! wrong")
    }

    test("putchar: env.putchar writes \"Hi!\" through host module") {
      val collected = new StringBuilder
      val env       = EnvModule.withWriter(c => collected.append(c.toChar))
      val inst      = instantiate(Fixtures.putchar, env)
      runRight(inst.invoke("hello"))
      check(collected.toString == "Hi!", s"got '${collected}'")
    }

    test("memory: i32 + byte store/load round-trip [load, store, store8, load8_u]") {
      val inst = instantiate(Fixtures.memory)
      check(callI32(inst, "i32_roundtrip",  0, 12345) == 12345, "addr 0 wrong")
      check(callI32(inst, "i32_roundtrip", 64,   -42) ==   -42, "negative round-trip wrong")
      check(callI32(inst, "byte_roundtrip", 0, 0xab)  ==  0xab, "unsigned byte wrong")
      check(callI32(inst, "byte_roundtrip", 7, 0x1ff) ==  0xff, "high bits dropped on store8")
    }

    test("br_block: br_if 0 exits the enclosing block early [drop]") {
      val inst = instantiate(Fixtures.br_block)
      check(callI32(inst, "test_br", 1) == 100, "branch-taken wrong")
      check(callI32(inst, "test_br", 0) == 200, "fall-through wrong")
    }

    // === every remaining i32 comparison =====================================

    test("comparisons: i32.eq") {
      val inst = instantiate(Fixtures.comparisons)
      check(callI32(inst, "i32_eq", 5,  5) == 1, "eq true")
      check(callI32(inst, "i32_eq", 5, -5) == 0, "eq false")
      check(callI32(inst, "i32_eq", 0,  0) == 1, "eq 0,0")
    }
    test("comparisons: i32.ne") {
      val inst = instantiate(Fixtures.comparisons)
      check(callI32(inst, "i32_ne", 5,  5) == 0, "ne false")
      check(callI32(inst, "i32_ne", 5, -5) == 1, "ne true")
    }
    test("comparisons: i32.lt_s (signed)") {
      val inst = instantiate(Fixtures.comparisons)
      check(callI32(inst, "i32_lt_s",  3,  5) == 1, "3 < 5")
      check(callI32(inst, "i32_lt_s",  5,  5) == 0, "5 < 5 false")
      check(callI32(inst, "i32_lt_s", -1,  0) == 1, "-1 < 0 (signed)")
      check(callI32(inst, "i32_lt_s",  0, -1) == 0, "0 < -1 false (signed)")
    }
    test("comparisons: i32.le_s") {
      val inst = instantiate(Fixtures.comparisons)
      check(callI32(inst, "i32_le_s", 5, 5) == 1, "5 <= 5")
      check(callI32(inst, "i32_le_s", 6, 5) == 0, "6 <= 5 false")
    }
    test("comparisons: i32.ge_s") {
      val inst = instantiate(Fixtures.comparisons)
      check(callI32(inst, "i32_ge_s", 5,  5) == 1, "5 >= 5")
      check(callI32(inst, "i32_ge_s", 4,  5) == 0, "4 >= 5 false")
      check(callI32(inst, "i32_ge_s", 0, -1) == 1, "0 >= -1 (signed)")
    }
    test("comparisons: i32.eqz") {
      val inst = instantiate(Fixtures.comparisons)
      check(callI32(inst, "i32_eqz",  0) == 1, "eqz 0")
      check(callI32(inst, "i32_eqz",  1) == 0, "eqz 1")
      check(callI32(inst, "i32_eqz", -1) == 0, "eqz -1")
    }

    // === bitwise + shifts ==================================================

    test("bitwise: i32.and / i32.or / i32.xor") {
      val inst = instantiate(Fixtures.bitwise)
      check(callI32(inst, "i32_and", 0xf0f0, 0x0ff0) == 0x00f0, "and")
      check(callI32(inst, "i32_or",  0xf000, 0x000f) == 0xf00f, "or")
      check(callI32(inst, "i32_xor", 0xff00, 0x0ff0) == 0xf0f0, "xor")
    }
    test("bitwise: i32.shl") {
      val inst = instantiate(Fixtures.bitwise)
      check(callI32(inst, "i32_shl", 1, 4)  == 16,  "shl 1 by 4")
      check(callI32(inst, "i32_shl", 1, 0)  == 1,   "shl 1 by 0")
      check(callI32(inst, "i32_shl", 1, 31) == Int.MinValue, "shl 1 by 31 -> sign bit")
    }
    test("bitwise: i32.shr_s (arithmetic, preserves sign)") {
      val inst = instantiate(Fixtures.bitwise)
      check(callI32(inst, "i32_shr_s",  16, 2)  ==  4, "16 >>s 2")
      check(callI32(inst, "i32_shr_s",  -8, 1)  == -4, "-8 >>s 1 sign-preserved")
      check(callI32(inst, "i32_shr_s",  -1, 31) == -1, "-1 >>s 31 stays -1")
    }

    test("rem_s: i32.rem_s") {
      val inst = instantiate(Fixtures.rem)
      check(callI32(inst, "i32_rem_s", 10,  3) ==  1,  "10 % 3")
      check(callI32(inst, "i32_rem_s", -7,  3) == -1,  "-7 % 3 (sign of dividend)")
      check(callI32(inst, "i32_rem_s",  7, -3) ==  1,  "7 % -3")
    }

    test("load8_s: byte load sign-extends to i32") {
      val inst = instantiate(Fixtures.load_signed)
      check(callI32(inst, "store_load_signed", 0, 0x7f) ==  127, "0x7f -> +127")
      check(callI32(inst, "store_load_signed", 1, 0x80) == -128, "0x80 -> -128 (sign-extend)")
      check(callI32(inst, "store_load_signed", 2, 0xff) ==   -1, "0xff -> -1")
    }

    // === stack ops ==========================================================

    test("select: picks first when cond != 0, second when cond == 0") {
      val inst = instantiate(Fixtures.stack_ops)
      check(callI32(inst, "test_select", 1, 11, 22) == 11, "cond=1 picks a")
      check(callI32(inst, "test_select", 0, 11, 22) == 22, "cond=0 picks b")
      // Negative-non-zero cond is still "true".
      check(callI32(inst, "test_select", -3, 11, 22) == 11, "cond=-3 still truthy")
    }
    test("drop: discards top of stack") {
      val inst = instantiate(Fixtures.stack_ops)
      check(callI32(inst, "test_drop", 7, 99) == 7, "drop kept the right value")
    }

    // === control flow extras ===============================================

    test("return: explicit early return from inside an if") {
      val inst = instantiate(Fixtures.early_return)
      check(callI32(inst, "early_return",  5) == 42, "positive -> early return")
      check(callI32(inst, "early_return", -1) ==  0, "non-positive -> fall through")
    }
    test("nop: surrounding computation is unaffected") {
      val inst = instantiate(Fixtures.nop_test)
      check(callI32(inst, "with_nops", 7)  == 8,  "7 + 1")
      check(callI32(inst, "with_nops", 41) == 42, "41 + 1")
    }
    test("br: non-zero label index targets the outer block") {
      val inst = instantiate(Fixtures.nested_br)
      check(callI32(inst, "nested", 1) == 100, "br_if 1 carries 100 out of $outer")
      check(callI32(inst, "nested", 0) == 200, "fall-through drops 100, returns 200")
    }

    // === traps ==============================================================

    test("trap: unreachable returns UnreachableExecuted") {
      val inst = instantiate(Fixtures.unreachable_trap)
      expectError(inst, "trap", Seq.empty):
        case WasmError.UnreachableExecuted => true
        case _                             => false
    }

    test("trap: div_s by zero") {
      val inst = instantiate(Fixtures.arith_edge)
      expectError(inst, "div_zero", Seq.empty):
        case WasmError.InvalidModule(msg) => msg.contains("divide by zero")
        case _                            => false
    }
    test("trap: div_s overflow (MIN_INT / -1)") {
      val inst = instantiate(Fixtures.arith_edge)
      expectError(inst, "div_overflow", Seq.empty):
        case WasmError.InvalidModule(msg) => msg.contains("overflow")
        case _                            => false
    }
    test("rem_s: MIN_INT % -1 == 0 (WASM-specific)") {
      val inst = instantiate(Fixtures.arith_edge)
      check(callI32(inst, "rem_min_neg1") == 0, "wrong result")
    }
    test("shift: shl shift count is taken mod 32") {
      val inst = instantiate(Fixtures.arith_edge)
      check(callI32(inst, "shl_mod32", 1, 33) == 2,                       "shl 1 by 33 == shl by 1")
      check(callI32(inst, "shl_mod32", 1, 32) == 1,                       "shl 1 by 32 == shl by 0")
      check(callI32(inst, "shr_s_mod32", -8, 33) == -4,                   "shr_s -8 by 33 == by 1")
    }

    test("trap: memory load out of bounds") {
      val inst = instantiate(Fixtures.memory_oob)
      expectError(inst, "load_oob", Seq(I32(65535))):
        case WasmError.MemoryOutOfBounds => true
        case _                           => false
      // A fully in-bounds load should succeed.
      check(callI32(inst, "load_oob", 0) == 0, "in-bounds load returns 0 from zeroed memory")
    }

    // === error paths in linking + parsing ==================================

    test("error: invoking a non-existent export returns ExportNotFound") {
      val inst = instantiate(Fixtures.arith)
      inst.invoke("does_not_exist") match
        case Left(WasmError.ExportNotFound("does_not_exist")) => ()
        case other                                            => check(false, s"expected ExportNotFound, got $other")
    }
    test("error: instantiating with an unresolved import returns UnknownImport") {
      Runtime.instantiate(Fixtures.putchar, Seq.empty) match
        case Left(WasmError.UnknownImport("env", "putchar")) => ()
        case other                                           => check(false, s"expected UnknownImport, got $other")
    }
    test("error: bad magic returns InvalidMagic") {
      val bad = Array.fill[Byte](16)(0)
      Parser.parse(bad) match
        case Left(WasmError.InvalidMagic) => ()
        case other                        => check(false, s"expected InvalidMagic, got $other")
    }

    // === report ============================================================

    println()
    val total = passed + failures.size
    if failures.isEmpty then
      println(s"All $total tests passed.")
    else
      println(s"${failures.size} of $total tests failed:")
      failures.foreach(f => println(s"  - $f"))
      throw new RuntimeException(s"${failures.size} of $total tests failed")
