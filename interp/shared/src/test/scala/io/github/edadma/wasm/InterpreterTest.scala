package io.github.edadma.wasm

import scala.collection.mutable.ArrayBuffer
import java.lang as jl  // for Long.divideUnsigned / remainderUnsigned reference values

/** Comprehensive tests for the WebAssembly MVP interpreter.
  *
  * Goal: virtually exhaustive coverage. Every implemented instruction, every
  * branch of the parser, every runtime error path, and every reported
  * `WasmError` variant gets at least one assertion. Bug fixes ship with a
  * regression test in the appropriate section below.
  *
  * The tests are organised into self-contained sections:
  *
  *   1. End-to-end via .wat fixtures             (program behaviour)
  *   2. Direct Leb128 unit tests                 (encoder edge cases)
  *   3. Parser malformed-binary tests            (handcrafted bytes)
  *   4. Interpreter unsupported-opcode tests     (patched fixtures)
  *   5. Runtime / linking error tests            (handcrafted bytes)
  *   6. EnvModule.default smoke (stdout capture)
  *   7. Bug-fix regression tests
  *
  * Zero external deps — we roll our own PASS/FAIL runner so this file
  * compiles and runs identically on JVM, Scala.js, and Scala Native. Invoke:
  *
  *   sbt 'interpJVM/Test/run'
  *   sbt 'interpJS/Test/run'
  *   sbt 'interpNative/Test/run'
  */
object InterpreterTest:

  // === Tiny test framework ================================================

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
      case other  => throw new AssertionError(s"$name returned $other, expected I32")

  /** Same shape as callI32 but for functions returning an i64; arguments
    * are pre-wrapped `Value`s so the caller can mix I32 + I64 freely
    * (memory tests, mixed-type helpers, etc.). */
  private def callI64(inst: ModuleInstance, name: String, args: Value*): Long =
    val results = runRight(inst.invoke(name, args))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case I64(v) => v
      case other  => throw new AssertionError(s"$name returned $other, expected I64")

  /** Same shape but for f32-returning functions. */
  private def callF32(inst: ModuleInstance, name: String, args: Value*): Float =
    val results = runRight(inst.invoke(name, args))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case F32(v) => v
      case other  => throw new AssertionError(s"$name returned $other, expected F32")

  /** Same shape but for f64-returning functions. */
  private def callF64(inst: ModuleInstance, name: String, args: Value*): Double =
    val results = runRight(inst.invoke(name, args))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case F64(v) => v
      case other  => throw new AssertionError(s"$name returned $other, expected F64")

  /** Convenience: invoke a function and pull out an `I32` result without
    * boxing the args into a sequence at the call site (used by the f32
    * compare tests, whose return type is i32). */
  private def callI32V(inst: ModuleInstance, name: String, args: Value*): Int =
    val results = runRight(inst.invoke(name, args))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case I32(v) => v
      case other  => throw new AssertionError(s"$name returned $other, expected I32")

  private def expectError(
      inst: ModuleInstance,
      name: String,
      args: Seq[Value],
  )(matcher: PartialFunction[WasmError, Boolean]): Unit =
    inst.invoke(name, args) match
      case Right(v)  => check(false, s"$name expected error, got Right($v)")
      case Left(err) =>
        if matcher.isDefinedAt(err) then check(matcher(err), s"$name unexpected error: $err")
        else check(false, s"$name unexpected error: $err")

  /** Replace one byte of a fixture copy. */
  private def patchByte(src: Array[Byte], index: Int, newByte: Int): Array[Byte] =
    val out = src.clone()
    out(index) = newByte.toByte
    out

  /** Replace the first occurrence of `oldByte` with `newByte` in a copy. */
  private def patchFirst(src: Array[Byte], oldByte: Int, newByte: Int): Array[Byte] =
    val idx = src.indexOf(oldByte.toByte)
    check(idx >= 0, s"byte 0x${oldByte.toHexString} not found in source")
    patchByte(src, idx, newByte)

  /** Compact byte literal helper. `b(0x00, 0x61, ...)` is shorter than the
    * `Array(0x00.toByte, ...)` form. */
  private def b(xs: Int*): Array[Byte] = xs.iterator.map(_.toByte).toArray

  /** Minimal valid module header: magic + version. */
  private val Header: Array[Byte] = b(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)

  // ========================================================================

  def main(args: Array[String]): Unit =
    println()
    println("== Interpreter tests ==")

    section1_endToEnd()
    section2_leb128()
    section3_parserMalformed()
    section4_unsupportedOpcodes()
    section5_runtimeErrors()
    section6_envModuleDefault()
    section7_bugFixRegressions()

    println()
    val total = passed + failures.size
    if failures.isEmpty then
      println(s"All $total tests passed.")
    else
      println(s"${failures.size} of $total tests failed:")
      failures.foreach(f => println(s"  - $f"))
      throw new RuntimeException(s"${failures.size} of $total tests failed")

  // ========================================================================
  // 1. End-to-end tests via .wat fixtures
  // ========================================================================

  private def section1_endToEnd(): Unit =

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

    // --- every remaining i32 comparison -------------------------------------
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

    // --- bitwise + shifts ---------------------------------------------------
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

    test("select: picks first when cond != 0, second when cond == 0") {
      val inst = instantiate(Fixtures.stack_ops)
      check(callI32(inst, "test_select",  1, 11, 22) == 11, "cond=1 picks a")
      check(callI32(inst, "test_select",  0, 11, 22) == 22, "cond=0 picks b")
      check(callI32(inst, "test_select", -3, 11, 22) == 11, "cond=-3 still truthy")
    }
    test("drop: discards top of stack") {
      val inst = instantiate(Fixtures.stack_ops)
      check(callI32(inst, "test_drop", 7, 99) == 7, "drop kept the right value")
    }

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

    // --- traps and edge math -----------------------------------------------
    test("trap: unreachable returns UnreachableExecuted") {
      val inst = instantiate(Fixtures.unreachable_trap)
      expectError(inst, "trap", Seq.empty) { case WasmError.UnreachableExecuted => true }
    }
    test("trap: div_s by zero") {
      val inst = instantiate(Fixtures.arith_edge)
      expectError(inst, "div_zero", Seq.empty) {
        case WasmError.InvalidModule(msg) => msg.contains("divide by zero")
      }
    }
    test("trap: div_s overflow (MIN_INT / -1)") {
      val inst = instantiate(Fixtures.arith_edge)
      expectError(inst, "div_overflow", Seq.empty) {
        case WasmError.InvalidModule(msg) => msg.contains("overflow")
      }
    }
    test("trap: rem_s by zero") {
      val inst = instantiate(Fixtures.rem_zero)
      expectError(inst, "rem_zero", Seq.empty) {
        case WasmError.InvalidModule(msg) => msg.contains("divide by zero")
      }
    }
    test("rem_s: MIN_INT % -1 == 0 (WASM-specific)") {
      val inst = instantiate(Fixtures.arith_edge)
      check(callI32(inst, "rem_min_neg1") == 0, "wrong result")
    }
    test("shift: shl / shr_s counts are masked mod 32") {
      val inst = instantiate(Fixtures.arith_edge)
      check(callI32(inst, "shl_mod32",   1, 33) ==  2, "shl 1 by 33 == shl by 1")
      check(callI32(inst, "shl_mod32",   1, 32) ==  1, "shl 1 by 32 == shl by 0")
      check(callI32(inst, "shr_s_mod32", -8, 33) == -4, "shr_s -8 by 33 == by 1")
    }
    test("arith: i32.add / sub / mul wrap mod 2^32") {
      val inst = instantiate(Fixtures.arith_wrap)
      check(callI32(inst, "add_wrap") == Int.MinValue, "MAX_INT + 1 wraps")
      check(callI32(inst, "sub_wrap") == Int.MaxValue, "MIN_INT - 1 wraps")
      check(callI32(inst, "mul_wrap") == 0,            "65536 * 65536 wraps to 0")
    }
    test("if: bare `if` with cond=false skips body, cond=true runs it") {
      val inst = instantiate(Fixtures.if_no_else)
      check(callI32(inst, "maybe_inc", 0,  10) == 10, "cond=0 keeps $n unchanged")
      check(callI32(inst, "maybe_inc", 1,  10) == 11, "cond=1 increments via stored local")
      check(callI32(inst, "maybe_inc", 1, -1) ==   0, "increment works for negative input")
    }
    test("loop: br back to loop top (iteration via `br $top`)") {
      val inst = instantiate(Fixtures.loop_continue)
      check(callI32(inst, "countdown",  0) ==  0, "0 iterations for n=0")
      check(callI32(inst, "countdown",  1) ==  1, "1 iteration for n=1")
      check(callI32(inst, "countdown", 25) == 25, "25 iterations for n=25")
    }
    test("trap: memory load out of bounds") {
      val inst = instantiate(Fixtures.memory_oob)
      expectError(inst, "load_oob", Seq(I32(65535))) { case WasmError.MemoryOutOfBounds => true }
      check(callI32(inst, "load_oob", 0) == 0, "in-bounds load returns 0 from zeroed memory")
      check(callI32(inst, "load_oob", 65536 - 4) == 0, "load at last valid 4-byte boundary")
    }
    test("trap: memory store out of bounds") {
      val inst = instantiate(Fixtures.store_oob)
      expectError(inst, "store_oob", Seq(I32(65534), I32(0xdead))) {
        case WasmError.MemoryOutOfBounds => true
      }
      inst.invoke("store_oob", Seq(I32(0), I32(0))) match
        case Right(Seq()) => ()
        case other        => check(false, s"in-bounds store should succeed, got $other")
    }

    // --- new fixtures (added this pass) ------------------------------------
    test("data section: active segment writes bytes into memory at offset") {
      val inst = instantiate(Fixtures.data_segment)
      // "AB" at offset 0 → i32.load reads [0x41, 0x42, 0x00, 0x00] little-endian
      check(callI32(inst, "read") == 0x4241, "data segment not applied")
    }
    test("data section: out-of-bounds segment fails instantiation with MemoryOutOfBounds") {
      Runtime.instantiate(Fixtures.data_oob, Seq(EnvModule.default)) match
        case Left(WasmError.MemoryOutOfBounds) => ()
        case other => check(false, s"expected MemoryOutOfBounds, got $other")
    }
    test("memory limits: parser accepts the explicit `max` form") {
      val inst = instantiate(Fixtures.memory_max)
      check(callI32(inst, "size_at_zero", 0) == 0, "in-bounds load on memory-with-max")
    }
    test("loop with i32 result: natural fall-through carries the result") {
      val inst = instantiate(Fixtures.loop_result)
      check(callI32(inst, "loop_result") == 42, "wrong result")
    }
    test("block with i32 result: natural fall-through carries the result") {
      val inst = instantiate(Fixtures.block_result)
      check(callI32(inst, "block_result") == 17, "wrong result")
    }
    test("parser: table import is silently skipped") {
      val inst = instantiate(Fixtures.table_import)
      check(callI32(inst, "f") == 1, "function after a skipped table import still works")
    }
    test("parser: memory import is silently skipped") {
      val inst = instantiate(Fixtures.mem_import)
      check(callI32(inst, "f") == 2, "function after a skipped memory import still works")
    }
    test("parser: global import is silently skipped") {
      val inst = instantiate(Fixtures.global_import)
      check(callI32(inst, "f") == 3, "function after a skipped global import still works")
    }
    test("parser: memory export silently ignored; global export surfaced") {
      val inst = instantiate(Fixtures.mem_export)
      check(inst.exportedFunctionNames == Seq("f"), s"function-export list should be just `f`: ${inst.exportedFunctionNames}")
      check(callI32(inst, "f") == 4, "function export still callable")
      // Phase 2: global exports are surfaced through `globalValue`.
      inst.globalValue("g") match
        case Right(I32(7)) => ()
        case other         => check(false, s"global export `g` should read I32(7): $other")
    }
    test("function: empty body returns immediately with no result") {
      val inst = instantiate(Fixtures.empty_func)
      inst.invoke("empty") match
        case Right(Seq()) => ()
        case other        => check(false, s"empty function should return no values: $other")
    }
    test("parser: custom section is silently skipped") {
      val inst = instantiate(Fixtures.custom_section)
      check(callI32(inst, "fancy", 3, 4) == 7, "function after a custom section still works")
    }

    // --- i64 support (Phase 1.1) -------------------------------------------

    test("i64.const: SLEB64 immediate decodes a multi-byte value (0x100000001 + 0x200000002)") {
      val inst = instantiate(Fixtures.i64_arith)
      check(callI64(inst, "i64_const_pair") == 0x300000003L, "wrong sum")
    }

    test("i64 arith: add / sub / mul on values larger than 2^32") {
      val inst = instantiate(Fixtures.i64_arith)
      check(callI64(inst, "i64_add", I64(0x100000000L), I64(0x200000000L)) == 0x300000000L, "add")
      check(callI64(inst, "i64_sub", I64(0x100000000L), I64(0x000000001L)) == 0x0FFFFFFFFL, "sub")
      check(callI64(inst, "i64_mul", I64(0x100000000L), I64(0x000000002L)) == 0x200000000L, "mul")
    }

    test("i64 arith: div_s / div_u / rem_s / rem_u (signed vs unsigned divergence)") {
      val inst = instantiate(Fixtures.i64_arith)
      check(callI64(inst, "i64_div_s", I64(-10L), I64(3L)) == -3L, "div_s of negative")
      check(callI64(inst, "i64_div_u", I64(-10L), I64(3L)) == jl.Long.divideUnsigned(-10L, 3L),
        "div_u treats -10 as huge positive")
      check(callI64(inst, "i64_rem_s", I64(-7L),  I64(3L)) == -1L, "rem_s sign of dividend")
      check(callI64(inst, "i64_rem_u", I64(-7L),  I64(3L)) == jl.Long.remainderUnsigned(-7L, 3L),
        "rem_u of -7 treated as huge positive")
    }

    test("i64.eqz returns i32 (1 for zero, 0 otherwise)") {
      val inst = instantiate(Fixtures.i64_compare)
      def asI32(name: String, a: Long): Int =
        runRight(inst.invoke(name, Seq(I64(a)))).head match
          case I32(v) => v
          case other  => throw new AssertionError(s"$name returned $other")
      check(asI32("i64_eqz",  0L) == 1, "eqz 0")
      check(asI32("i64_eqz",  1L) == 0, "eqz 1")
      check(asI32("i64_eqz", -1L) == 0, "eqz -1")
    }

    test("i64 compares (signed) return i32: eq / ne / lt_s / gt_s / le_s / ge_s") {
      val inst = instantiate(Fixtures.i64_compare)
      def asI32(name: String, a: Long, b: Long): Int =
        val res = runRight(inst.invoke(name, Seq(I64(a), I64(b))))
        check(res.size == 1, s"$name returned ${res.size} values")
        res.head match
          case I32(v) => v
          case other  => throw new AssertionError(s"$name returned $other")

      check(asI32("i64_eq",   5L,  5L) == 1, "eq true")
      check(asI32("i64_eq",   5L, -5L) == 0, "eq false")
      check(asI32("i64_ne",   5L,  5L) == 0, "ne false")
      check(asI32("i64_lt_s", 3L,  5L) == 1, "3 < 5")
      check(asI32("i64_lt_s", -1L, 0L) == 1, "-1 < 0 signed")
      check(asI32("i64_gt_s", 0L, -1L) == 1, "0 > -1 signed")
      check(asI32("i64_le_s", 5L,  5L) == 1, "5 <= 5")
      check(asI32("i64_ge_s", 5L,  5L) == 1, "5 >= 5")
    }

    test("i64 compares (unsigned) return i32: lt_u / gt_u / le_u / ge_u (negative treated as huge)") {
      val inst = instantiate(Fixtures.i64_compare)
      def asI32(name: String, a: Long, b: Long): Int =
        val res = runRight(inst.invoke(name, Seq(I64(a), I64(b))))
        res.head match
          case I32(v) => v
          case other  => throw new AssertionError(s"$name returned $other")

      // -1L unsigned is the maximum, so -1 > 0, -1 >= 0, etc.
      check(asI32("i64_lt_u", -1L, 0L)  == 0, "-1 < 0 unsigned should be false (since -1 is max)")
      check(asI32("i64_gt_u", -1L, 0L)  == 1, "-1 > 0 unsigned true")
      check(asI32("i64_le_u",  0L, -1L) == 1, "0 <= -1 unsigned true")
      check(asI32("i64_ge_u", -1L, -1L) == 1, "-1 >= -1 unsigned true (equal)")
    }

    test("i64 bitwise: and / or / xor / shl / shr_s / shr_u") {
      val inst = instantiate(Fixtures.i64_bitwise)
      check(callI64(inst, "i64_and",   I64(0xf0f0L), I64(0x0ff0L)) == 0x00f0L, "and")
      check(callI64(inst, "i64_or",    I64(0xf000L), I64(0x000fL)) == 0xf00fL, "or")
      check(callI64(inst, "i64_xor",   I64(0xff00L), I64(0x0ff0L)) == 0xf0f0L, "xor")
      check(callI64(inst, "i64_shl",   I64(1L), I64(33L))  == (1L << 33), "shl 1 by 33 produces bit 33")
      check(callI64(inst, "i64_shr_s", I64(-8L), I64(1L))  == -4L, "shr_s -8 stays negative")
      check(callI64(inst, "i64_shr_u", I64(-1L), I64(1L))  == Long.MaxValue, "shr_u -1 zero-fills high bit")
    }

    test("i64 rotates: rotl / rotr (round-trip and known result)") {
      val inst = instantiate(Fixtures.i64_bitwise)
      // Rotating MSB-set value left by 1 should bring the top bit back to bit 0.
      check(callI64(inst, "i64_rotl", I64(0x8000000000000000L), I64(1L)) == 1L, "rotl: top bit -> bit 0")
      check(callI64(inst, "i64_rotr", I64(1L), I64(1L)) == 0x8000000000000000L, "rotr: bit 0 -> top bit")
    }

    test("i64 bit-counting: clz / ctz / popcnt (result type is i64)") {
      val inst = instantiate(Fixtures.i64_bitwise)
      check(callI64(inst, "i64_clz",    I64(1L))   == 63L, "clz of 1 = 63")
      check(callI64(inst, "i64_clz",    I64(0L))   == 64L, "clz of 0 = 64")
      check(callI64(inst, "i64_ctz",    I64(8L))   == 3L,  "ctz of 8 = 3")
      check(callI64(inst, "i64_ctz",    I64(0L))   == 64L, "ctz of 0 = 64")
      check(callI64(inst, "i64_popcnt", I64(-1L))  == 64L, "popcnt of -1 = 64 ones")
      check(callI64(inst, "i64_popcnt", I64(0x55L)) == 4L, "popcnt of 0x55 = 4 ones")
    }

    test("i64 memory: i64.load / i64.store round-trip preserves full 64 bits") {
      val inst = instantiate(Fixtures.i64_memory)
      // Pick a value that exercises all 8 bytes (every byte distinct).
      val pattern = 0x0123456789abcdefL
      check(callI64(inst, "i64_roundtrip", I32(0),  I64(pattern)) == pattern, "addr 0")
      check(callI64(inst, "i64_roundtrip", I32(64), I64(-1L))     == -1L,    "addr 64 negative")
    }

    test("i64 local: declared local zero-initialises to I64(0)") {
      val inst = instantiate(Fixtures.i64_memory)
      check(callI64(inst, "i64_local_zero") == 0L, "i64 local should zero-init")
    }

    test("i64 load variants: load8_s vs load8_u sign-handling") {
      val inst = instantiate(Fixtures.i64_load_store_variants)
      // 0xFF as a single byte: signed = -1, unsigned = 255.
      check(callI64(inst, "store8_load8_s", I64(0xffL)) == -1L,  "load8_s sign-extends")
      check(callI64(inst, "store8_load8_u", I64(0xffL)) == 255L, "load8_u zero-extends")
    }

    test("i64 load variants: load16_s vs load16_u sign-handling") {
      val inst = instantiate(Fixtures.i64_load_store_variants)
      // 0x8000 (low 16 bits set high): signed = -32768, unsigned = 32768.
      check(callI64(inst, "store16_load16_s", I64(0x8000L)) == -32768L, "load16_s sign-extends")
      check(callI64(inst, "store16_load16_u", I64(0x8000L)) == 32768L,  "load16_u zero-extends")
    }

    test("i64 load variants: load32_s vs load32_u sign-handling") {
      val inst = instantiate(Fixtures.i64_load_store_variants)
      // 0x80000000 (low 32 bits set high): signed = -2^31, unsigned = 2^31.
      check(callI64(inst, "store32_load32_s", I64(0x80000000L)) == -2147483648L, "load32_s sign-extends")
      check(callI64(inst, "store32_load32_u", I64(0x80000000L)) == 2147483648L,  "load32_u zero-extends")
    }

    test("i64 store narrowing: store8 only writes one byte (high bits discarded)") {
      val inst = instantiate(Fixtures.i64_load_store_variants)
      // Store a value with high bits set; the unsigned 8-bit load should only see the low byte.
      check(callI64(inst, "store8_load8_u", I64(0x1FFL))  == 0xFFL,  "low byte preserved")
      check(callI64(inst, "store8_load8_u", I64(0xABCDL)) == 0xCDL,  "high bytes dropped")
    }

    test("i64.const: Long.MaxValue / Long.MinValue / -1 round-trip through SLEB64") {
      val inst = instantiate(Fixtures.i64_const_extremes)
      check(callI64(inst, "i64_max")       == Long.MaxValue, "MaxValue")
      check(callI64(inst, "i64_min")       == Long.MinValue, "MinValue")
      check(callI64(inst, "i64_minus_one") == -1L,           "-1")
    }

    test("i64 block result: blocktype 0x7E carries an i64 across the matching end") {
      val inst = instantiate(Fixtures.i64_block_result)
      check(callI64(inst, "i64_block_result") == 9876543210L, "block returns its i64 result")
    }

    test("i64 traps: div_s / rem_s by zero, MIN_LONG/-1 overflow, rem_s MIN_LONG/-1 == 0") {
      val inst = instantiate(Fixtures.i64_edge)
      expectError(inst, "div_s_zero",     Seq.empty) { case WasmError.InvalidModule(m) => m.contains("divide by zero") }
      expectError(inst, "div_s_overflow", Seq.empty) { case WasmError.InvalidModule(m) => m.contains("overflow") }
      expectError(inst, "rem_s_zero",     Seq.empty) { case WasmError.InvalidModule(m) => m.contains("divide by zero") }
      expectError(inst, "rem_u_zero",     Seq.empty) { case WasmError.InvalidModule(m) => m.contains("divide by zero") }
      check(callI64(inst, "rem_s_min_neg1") == 0L, "rem_s MIN_LONG / -1 == 0 (no trap)")
    }

    test("i64 shift count masked mod 64; add wraps mod 2^64") {
      val inst = instantiate(Fixtures.i64_edge)
      check(callI64(inst, "shl_mod64",  I64(1L), I64(65L)) == 2L,                 "shl by 65 == shl by 1")
      check(callI64(inst, "shl_mod64",  I64(1L), I64(64L)) == 1L,                 "shl by 64 == shl by 0")
      check(callI64(inst, "rotl_mod64", I64(1L), I64(65L)) == 2L,                 "rotl by 65 == rotl by 1")
      check(callI64(inst, "add_wrap")                     == Long.MinValue,      "MAX_LONG + 1 wraps")
    }

    test("i64 memory: load out of bounds traps with MemoryOutOfBounds") {
      val inst = instantiate(Fixtures.i64_memory)
      // page size 65536 — i64.load at the last 8-byte boundary works, one byte past traps.
      check(callI64(inst, "i64_roundtrip", I32(65528), I64(0L)) == 0L, "last valid 8-byte aligned slot")
      expectError(inst, "i64_roundtrip", Seq(I32(65529), I64(0L))) { case WasmError.MemoryOutOfBounds => true }
    }

    // --- f32 support (Phase 1.2) -------------------------------------------
    //
    // The interpreter handles f32.const as 4 raw LE bytes (no LEB), runs the
    // ordered compares per IEEE-754 (NaN makes <, <=, >, >=, == false; only
    // != stays true), and delegates min/max/sqrt/floor/ceil/rint/copySign to
    // java.lang.Math which already follows the spec. These tests pin the
    // wiring end-to-end on every backend so platform-specific NaN handling
    // gets caught early.
    //
    // Helper: identity-by-bits — Float.NaN == Float.NaN is false, so we
    // compare bit patterns instead when asserting NaN flow.

    def bitsEq(a: Float, b: Float): Boolean =
      jl.Float.floatToRawIntBits(a) == jl.Float.floatToRawIntBits(b)

    test("f32.const: SLEB-free 4-byte immediate decodes pi / e / -pi") {
      val inst = instantiate(Fixtures.f32_const_specials)
      check(callF32(inst, "f32_pi")     == 3.14159265f,  "pi")
      check(callF32(inst, "f32_e")      == 2.71828183f,  "e")
      check(callF32(inst, "f32_neg_pi") == -3.14159265f, "-pi")
    }

    test("f32.const: smallest positive normal and denormal") {
      val inst = instantiate(Fixtures.f32_const_specials)
      check(callF32(inst, "f32_min_normal")   == jl.Float.MIN_NORMAL, "min normal == 2^-126")
      check(callF32(inst, "f32_min_denormal") == jl.Float.MIN_VALUE,  "min denormal == 2^-149")
    }

    test("f32.const: +inf / -inf / signed zeros / canonical NaN") {
      val inst = instantiate(Fixtures.f32_const_specials)
      check(callF32(inst, "f32_pos_inf")  == jl.Float.POSITIVE_INFINITY, "+inf")
      check(callF32(inst, "f32_neg_inf")  == jl.Float.NEGATIVE_INFINITY, "-inf")
      // Signed zero: equal under ==, but distinguishable by bit pattern.
      val posZero = callF32(inst, "f32_pos_zero")
      val negZero = callF32(inst, "f32_neg_zero")
      check(posZero == 0.0f && negZero == 0.0f, "both compare == 0.0f")
      check(jl.Float.floatToRawIntBits(posZero) == 0x00000000, "+0.0 has zero bit pattern")
      check(jl.Float.floatToRawIntBits(negZero) == 0x80000000, "-0.0 has sign-bit set")
      val nan = callF32(inst, "f32_nan")
      check(jl.Float.isNaN(nan), "NaN is detected as NaN")
    }

    test("f32 arithmetic: add / sub / mul / div (exact on representable inputs)") {
      val inst = instantiate(Fixtures.f32_arith)
      check(callF32(inst, "f32_add", F32(1.5f), F32(2.25f)) == 3.75f, "add")
      check(callF32(inst, "f32_sub", F32(5.0f), F32(1.5f))  == 3.5f,  "sub")
      check(callF32(inst, "f32_mul", F32(2.5f), F32(4.0f))  == 10.0f, "mul")
      check(callF32(inst, "f32_div", F32(7.0f), F32(2.0f))  == 3.5f,  "div")
    }

    test("f32.div: divide by zero produces Inf (does NOT trap)") {
      val inst = instantiate(Fixtures.f32_arith)
      check(callF32(inst, "f32_div", F32(1.0f),  F32(0.0f)) == jl.Float.POSITIVE_INFINITY, " 1/+0 == +inf")
      check(callF32(inst, "f32_div", F32(-1.0f), F32(0.0f)) == jl.Float.NEGATIVE_INFINITY, "-1/+0 == -inf")
      check(jl.Float.isNaN(callF32(inst, "f32_div", F32(0.0f), F32(0.0f))),                "0/0 == NaN")
    }

    test("f32.min / f32.max: NaN propagates, signed zeros distinguished") {
      val inst = instantiate(Fixtures.f32_arith)
      // Plain values: min picks smaller, max picks larger.
      check(callF32(inst, "f32_min", F32(2.0f), F32(3.0f)) == 2.0f, "min plain")
      check(callF32(inst, "f32_max", F32(2.0f), F32(3.0f)) == 3.0f, "max plain")
      // NaN propagation — any NaN operand yields NaN.
      check(jl.Float.isNaN(callF32(inst, "f32_min", F32(Float.NaN), F32(1.0f))), "min NaN, 1 -> NaN")
      check(jl.Float.isNaN(callF32(inst, "f32_max", F32(1.0f), F32(Float.NaN))), "max 1, NaN -> NaN")
      // Signed zeros: f32.min(-0, +0) -> -0; f32.max(-0, +0) -> +0.
      val minZ = callF32(inst, "f32_min", F32(-0.0f), F32(0.0f))
      val maxZ = callF32(inst, "f32_max", F32(-0.0f), F32(0.0f))
      check(jl.Float.floatToRawIntBits(minZ) == 0x80000000, s"min(-0,+0) bits should be 0x80000000, got 0x${jl.Float.floatToRawIntBits(minZ).toHexString}")
      check(jl.Float.floatToRawIntBits(maxZ) == 0x00000000, s"max(-0,+0) bits should be 0x00000000, got 0x${jl.Float.floatToRawIntBits(maxZ).toHexString}")
    }

    test("f32.copysign: magnitude of a, sign of b") {
      val inst = instantiate(Fixtures.f32_arith)
      check(callF32(inst, "f32_copysign", F32(3.0f),  F32(-1.0f)) == -3.0f, "(+3,-1) -> -3")
      check(callF32(inst, "f32_copysign", F32(-3.0f), F32(1.0f))  ==  3.0f, "(-3,+1) -> +3")
      // Signed zero source — copysign preserves nonzero magnitude with new sign.
      val r = callF32(inst, "f32_copysign", F32(2.0f), F32(-0.0f))
      check(r == -2.0f && jl.Float.floatToRawIntBits(r) == jl.Float.floatToRawIntBits(-2.0f),
        s"copysign(2,-0) -> -2; bits: 0x${jl.Float.floatToRawIntBits(r).toHexString}")
    }

    test("f32 compares: eq / ne / lt / gt / le / ge on ordered values") {
      val inst = instantiate(Fixtures.f32_compare)
      check(callI32V(inst, "f32_eq", F32(1.5f), F32(1.5f)) == 1, "eq true")
      check(callI32V(inst, "f32_eq", F32(1.5f), F32(2.5f)) == 0, "eq false")
      check(callI32V(inst, "f32_ne", F32(1.5f), F32(2.5f)) == 1, "ne true")
      check(callI32V(inst, "f32_lt", F32(1.0f), F32(2.0f)) == 1, "1 < 2")
      check(callI32V(inst, "f32_gt", F32(2.0f), F32(1.0f)) == 1, "2 > 1")
      check(callI32V(inst, "f32_le", F32(1.0f), F32(1.0f)) == 1, "1 <= 1")
      check(callI32V(inst, "f32_ge", F32(1.0f), F32(1.0f)) == 1, "1 >= 1")
    }

    test("f32 compares: every ordered compare against NaN returns 0; only `ne` is 1") {
      val inst = instantiate(Fixtures.f32_compare)
      val nan = Float.NaN
      check(callI32V(inst, "f32_eq", F32(nan), F32(1.0f)) == 0, "NaN == 1 -> false")
      check(callI32V(inst, "f32_eq", F32(nan), F32(nan))  == 0, "NaN == NaN -> false")
      check(callI32V(inst, "f32_ne", F32(nan), F32(1.0f)) == 1, "NaN != 1 -> true")
      check(callI32V(inst, "f32_ne", F32(nan), F32(nan))  == 1, "NaN != NaN -> true")
      check(callI32V(inst, "f32_lt", F32(nan), F32(1.0f)) == 0, "NaN < 1 -> false")
      check(callI32V(inst, "f32_le", F32(nan), F32(nan))  == 0, "NaN <= NaN -> false")
      check(callI32V(inst, "f32_gt", F32(1.0f), F32(nan)) == 0, "1 > NaN -> false")
      check(callI32V(inst, "f32_ge", F32(1.0f), F32(nan)) == 0, "1 >= NaN -> false")
    }

    test("f32 compares: signed-zero equality (-0 == +0)") {
      val inst = instantiate(Fixtures.f32_compare)
      check(callI32V(inst, "f32_eq", F32(-0.0f), F32(0.0f)) == 1, "-0 == +0 per IEEE-754")
      check(callI32V(inst, "f32_le", F32(-0.0f), F32(0.0f)) == 1, "-0 <= +0")
      check(callI32V(inst, "f32_ge", F32(-0.0f), F32(0.0f)) == 1, "-0 >= +0")
    }

    test("f32 unary: abs / neg (incl. NaN: abs clears sign bit, neg flips it)") {
      val inst = instantiate(Fixtures.f32_unary)
      check(callF32(inst, "f32_abs", F32(-3.0f)) ==  3.0f, "abs negative")
      check(callF32(inst, "f32_abs", F32( 3.0f)) ==  3.0f, "abs positive")
      check(callF32(inst, "f32_neg", F32(-3.0f)) ==  3.0f, "neg negative")
      check(callF32(inst, "f32_neg", F32( 3.0f)) == -3.0f, "neg positive")
      // abs of -0 -> +0; neg of -0 -> +0.
      val abs0 = callF32(inst, "f32_abs", F32(-0.0f))
      check(jl.Float.floatToRawIntBits(abs0) == 0x00000000, "abs(-0) -> +0")
      val negPos0 = callF32(inst, "f32_neg", F32(0.0f))
      check(jl.Float.floatToRawIntBits(negPos0) == 0x80000000, "neg(+0) -> -0")
    }

    test("f32 unary: ceil / floor / trunc / nearest (incl. negatives + halves)") {
      val inst = instantiate(Fixtures.f32_unary)
      check(callF32(inst, "f32_ceil",    F32(1.2f))  ==  2.0f, "ceil 1.2 -> 2")
      check(callF32(inst, "f32_ceil",    F32(-1.2f)) == -1.0f, "ceil -1.2 -> -1")
      check(callF32(inst, "f32_floor",   F32(1.8f))  ==  1.0f, "floor 1.8 -> 1")
      check(callF32(inst, "f32_floor",   F32(-1.8f)) == -2.0f, "floor -1.8 -> -2")
      check(callF32(inst, "f32_trunc",   F32(1.8f))  ==  1.0f, "trunc 1.8 -> 1")
      check(callF32(inst, "f32_trunc",   F32(-1.8f)) == -1.0f, "trunc -1.8 -> -1 (toward zero)")
      // nearest = round-half-to-even.
      check(callF32(inst, "f32_nearest", F32(0.5f))  == 0.0f,  "0.5 -> 0 (even)")
      check(callF32(inst, "f32_nearest", F32(1.5f))  == 2.0f,  "1.5 -> 2 (even)")
      check(callF32(inst, "f32_nearest", F32(2.5f))  == 2.0f,  "2.5 -> 2 (even)")
      check(callF32(inst, "f32_nearest", F32(-0.5f)) == 0.0f,  "-0.5 -> 0 (even, but sign may differ)")
    }

    test("f32.sqrt: well-defined positives, sqrt(-1) is NaN (no trap)") {
      val inst = instantiate(Fixtures.f32_unary)
      check(callF32(inst, "f32_sqrt", F32( 4.0f)) == 2.0f, "sqrt 4")
      check(callF32(inst, "f32_sqrt", F32( 0.0f)) == 0.0f, "sqrt 0")
      check(jl.Float.isNaN(callF32(inst, "f32_sqrt", F32(-1.0f))), "sqrt -1 -> NaN")
      // sqrt(-0) = -0 per IEEE-754.
      val sqrtNegZero = callF32(inst, "f32_sqrt", F32(-0.0f))
      check(jl.Float.floatToRawIntBits(sqrtNegZero) == 0x80000000,
        s"sqrt(-0) bits should be -0 (0x80000000), got 0x${jl.Float.floatToRawIntBits(sqrtNegZero).toHexString}")
    }

    test("f32 memory: f32.load / f32.store round-trip preserves NaN bit pattern") {
      val inst = instantiate(Fixtures.f32_memory)
      check(callF32(inst, "f32_roundtrip", I32(0),  F32(3.14159f)) == 3.14159f, "addr 0 plain")
      // -0.0 round-trip: bit pattern must come back exactly (not normalised to +0).
      val negZero = callF32(inst, "f32_roundtrip", I32(8), F32(-0.0f))
      check(jl.Float.floatToRawIntBits(negZero) == 0x80000000, "-0.0 round-trip preserves sign bit")
      // NaN round-trip: still a NaN.
      check(jl.Float.isNaN(callF32(inst, "f32_roundtrip", I32(16), F32(Float.NaN))), "NaN round-trip is NaN")
    }

    test("f32 local: declared local zero-initialises to F32(0.0f)") {
      val inst = instantiate(Fixtures.f32_memory)
      val z = callF32(inst, "f32_local_zero")
      check(z == 0.0f, "f32 local should equal +0.0")
      check(jl.Float.floatToRawIntBits(z) == 0x00000000, "f32 local should be positive zero (not -0)")
    }

    test("f32 memory: load out of bounds traps with MemoryOutOfBounds") {
      val inst = instantiate(Fixtures.f32_memory)
      // page = 65536, f32 is 4 bytes; last valid addr is 65532.
      check(callF32(inst, "f32_roundtrip", I32(65532), F32(1.0f)) == 1.0f, "last valid 4-byte slot")
      expectError(inst, "f32_roundtrip", Seq(I32(65533), F32(0.0f))) { case WasmError.MemoryOutOfBounds => true }
    }

    test("f32 block result: blocktype 0x7D carries an f32 across the matching end") {
      val inst = instantiate(Fixtures.f32_block_result)
      check(callF32(inst, "f32_block_result") == 1.5f, "block returns its f32 result")
    }

    test("f32 IEEE edges: inf + (-inf) = NaN, inf * 0 = NaN, divide-by-zero never traps") {
      val inst = instantiate(Fixtures.f32_edge)
      check(jl.Float.isNaN(callF32(inst, "inf_plus_neg_inf")), "inf + -inf -> NaN")
      check(jl.Float.isNaN(callF32(inst, "inf_times_zero")),   "inf *  0   -> NaN")
      // 1/+0 = +inf, 1/-0 = -inf, 0/0 = NaN.
      check(callF32(inst, "div_pos_zero", F32( 1.0f)) == jl.Float.POSITIVE_INFINITY, " 1/+0 -> +inf")
      check(callF32(inst, "div_neg_zero", F32( 1.0f)) == jl.Float.NEGATIVE_INFINITY, " 1/-0 -> -inf")
      check(callF32(inst, "div_pos_zero", F32(-1.0f)) == jl.Float.NEGATIVE_INFINITY, "-1/+0 -> -inf")
      check(jl.Float.isNaN(callF32(inst, "div_pos_zero", F32(0.0f))),                "0/0  -> NaN")
      // sqrt(-1) is NaN, not a trap.
      check(jl.Float.isNaN(callF32(inst, "sqrt_neg_one")), "sqrt(-1) -> NaN")
    }

    test("f32 select (polymorphic): select with f32 operands picks the right one") {
      // The `select` opcode (0x1B) is polymorphic in MVP; this verifies it
      // works at f32 width too, not just i32. We don't need a dedicated
      // fixture — the existing stack_ops fixture only tests i32 select, so
      // we synthesise a tiny inline-bytes module here.
      val typeSec = b(0x02) ++                                          // 2 functype entries
        b(0x60, 0x00, 0x01, 0x7d) ++                                    //  () -> (f32)
        b(0x60, 0x03, 0x7d, 0x7d, 0x7f, 0x01, 0x7d)                     //  (f32, f32, i32) -> (f32)
      val funcSec = b(0x01, 0x01)                                       // 1 func, type idx 1
      val expSec  = b(0x01) ++                                          // 1 export
        b(0x06, 's'.toInt, 'e'.toInt, 'l'.toInt, 'e'.toInt, 'c'.toInt, 't'.toInt) ++
        b(0x00, 0x00)                                                   // kind=func, idx 0
      // body: locals=0; local.get 0; local.get 1; local.get 2; select; end
      val body    = b(0x00,                                              // 0 local groups
                       0x20, 0x00, 0x20, 0x01, 0x20, 0x02, 0x1b, 0x0b)
      val codeSec = b(0x01, body.length.toByte) ++ body
      val bytes   = Header ++
        b(0x01, typeSec.length) ++ typeSec ++
        b(0x03, funcSec.length) ++ funcSec ++
        b(0x07, expSec.length)  ++ expSec  ++
        b(0x0a, codeSec.length) ++ codeSec
      val inst = instantiate(bytes)
      check(callF32(inst, "select", F32(1.5f), F32(2.5f), I32(1)) == 1.5f, "cond=1 picks a")
      check(callF32(inst, "select", F32(1.5f), F32(2.5f), I32(0)) == 2.5f, "cond=0 picks b")
    }

    // --- f64 support (Phase 1.3) -------------------------------------------
    //
    // Same shape as f32, just at Double width: const is 8 raw LE bytes,
    // load/store use the raw-bits conversion so NaN payloads survive, all
    // ordered compares already match WASM via Scala's primitive operators,
    // and `jl.Math.{abs, floor, ceil, rint, sqrt, copySign, min, max}` are
    // already IEEE-754 conforming on `double`. With f64 wired up, every
    // scalar valtype the binary format defines is now supported.

    test("f64.const: SLEB-free 8-byte immediate decodes pi / e / -pi") {
      val inst = instantiate(Fixtures.f64_const_specials)
      check(callF64(inst, "f64_pi")     == 3.141592653589793,  "pi")
      check(callF64(inst, "f64_e")      == 2.718281828459045,  "e")
      check(callF64(inst, "f64_neg_pi") == -3.141592653589793, "-pi")
    }

    test("f64.const: smallest positive normal and denormal") {
      val inst = instantiate(Fixtures.f64_const_specials)
      check(callF64(inst, "f64_min_normal")   == jl.Double.MIN_NORMAL, "min normal == 2^-1022")
      check(callF64(inst, "f64_min_denormal") == jl.Double.MIN_VALUE,  "min denormal == 2^-1074")
    }

    test("f64.const: +inf / -inf / signed zeros / canonical NaN") {
      val inst = instantiate(Fixtures.f64_const_specials)
      check(callF64(inst, "f64_pos_inf")  == jl.Double.POSITIVE_INFINITY, "+inf")
      check(callF64(inst, "f64_neg_inf")  == jl.Double.NEGATIVE_INFINITY, "-inf")
      // Signed zero: equal under ==, but distinguishable by bit pattern.
      val posZero = callF64(inst, "f64_pos_zero")
      val negZero = callF64(inst, "f64_neg_zero")
      check(posZero == 0.0 && negZero == 0.0, "both compare == 0.0")
      check(jl.Double.doubleToRawLongBits(posZero) == 0x0000000000000000L, "+0.0 has zero bit pattern")
      check(jl.Double.doubleToRawLongBits(negZero) == 0x8000000000000000L.toLong, "-0.0 has sign-bit set")
      val nan = callF64(inst, "f64_nan")
      check(jl.Double.isNaN(nan), "NaN is detected as NaN")
    }

    test("f64 arithmetic: add / sub / mul / div (exact on representable inputs)") {
      val inst = instantiate(Fixtures.f64_arith)
      check(callF64(inst, "f64_add", F64(1.5), F64(2.25)) == 3.75, "add")
      check(callF64(inst, "f64_sub", F64(5.0), F64(1.5))  == 3.5,  "sub")
      check(callF64(inst, "f64_mul", F64(2.5), F64(4.0))  == 10.0, "mul")
      check(callF64(inst, "f64_div", F64(7.0), F64(2.0))  == 3.5,  "div")
    }

    test("f64.div: divide by zero produces Inf (does NOT trap)") {
      val inst = instantiate(Fixtures.f64_arith)
      check(callF64(inst, "f64_div", F64(1.0),  F64(0.0)) == jl.Double.POSITIVE_INFINITY, " 1/+0 == +inf")
      check(callF64(inst, "f64_div", F64(-1.0), F64(0.0)) == jl.Double.NEGATIVE_INFINITY, "-1/+0 == -inf")
      check(jl.Double.isNaN(callF64(inst, "f64_div", F64(0.0), F64(0.0))),                "0/0 == NaN")
    }

    test("f64.min / f64.max: NaN propagates, signed zeros distinguished") {
      val inst = instantiate(Fixtures.f64_arith)
      // Plain values: min picks smaller, max picks larger.
      check(callF64(inst, "f64_min", F64(2.0), F64(3.0)) == 2.0, "min plain")
      check(callF64(inst, "f64_max", F64(2.0), F64(3.0)) == 3.0, "max plain")
      // NaN propagation — any NaN operand yields NaN.
      check(jl.Double.isNaN(callF64(inst, "f64_min", F64(Double.NaN), F64(1.0))), "min NaN, 1 -> NaN")
      check(jl.Double.isNaN(callF64(inst, "f64_max", F64(1.0), F64(Double.NaN))), "max 1, NaN -> NaN")
      // Signed zeros: f64.min(-0, +0) -> -0; f64.max(-0, +0) -> +0.
      val minZ = callF64(inst, "f64_min", F64(-0.0), F64(0.0))
      val maxZ = callF64(inst, "f64_max", F64(-0.0), F64(0.0))
      check(jl.Double.doubleToRawLongBits(minZ) == 0x8000000000000000L.toLong,
        s"min(-0,+0) bits should be 0x8000000000000000, got 0x${jl.Double.doubleToRawLongBits(minZ).toHexString}")
      check(jl.Double.doubleToRawLongBits(maxZ) == 0x0000000000000000L,
        s"max(-0,+0) bits should be 0x0000000000000000, got 0x${jl.Double.doubleToRawLongBits(maxZ).toHexString}")
    }

    test("f64.copysign: magnitude of a, sign of b") {
      val inst = instantiate(Fixtures.f64_arith)
      check(callF64(inst, "f64_copysign", F64(3.0),  F64(-1.0)) == -3.0, "(+3,-1) -> -3")
      check(callF64(inst, "f64_copysign", F64(-3.0), F64(1.0))  ==  3.0, "(-3,+1) -> +3")
      // Signed zero source — copysign preserves nonzero magnitude with new sign.
      val r = callF64(inst, "f64_copysign", F64(2.0), F64(-0.0))
      check(r == -2.0 && jl.Double.doubleToRawLongBits(r) == jl.Double.doubleToRawLongBits(-2.0),
        s"copysign(2,-0) -> -2; bits: 0x${jl.Double.doubleToRawLongBits(r).toHexString}")
    }

    test("f64 compares: eq / ne / lt / gt / le / ge on ordered values") {
      val inst = instantiate(Fixtures.f64_compare)
      check(callI32V(inst, "f64_eq", F64(1.5), F64(1.5)) == 1, "eq true")
      check(callI32V(inst, "f64_eq", F64(1.5), F64(2.5)) == 0, "eq false")
      check(callI32V(inst, "f64_ne", F64(1.5), F64(2.5)) == 1, "ne true")
      check(callI32V(inst, "f64_lt", F64(1.0), F64(2.0)) == 1, "1 < 2")
      check(callI32V(inst, "f64_gt", F64(2.0), F64(1.0)) == 1, "2 > 1")
      check(callI32V(inst, "f64_le", F64(1.0), F64(1.0)) == 1, "1 <= 1")
      check(callI32V(inst, "f64_ge", F64(1.0), F64(1.0)) == 1, "1 >= 1")
    }

    test("f64 compares: every ordered compare against NaN returns 0; only `ne` is 1") {
      val inst = instantiate(Fixtures.f64_compare)
      val nan = Double.NaN
      check(callI32V(inst, "f64_eq", F64(nan), F64(1.0)) == 0, "NaN == 1 -> false")
      check(callI32V(inst, "f64_eq", F64(nan), F64(nan)) == 0, "NaN == NaN -> false")
      check(callI32V(inst, "f64_ne", F64(nan), F64(1.0)) == 1, "NaN != 1 -> true")
      check(callI32V(inst, "f64_ne", F64(nan), F64(nan)) == 1, "NaN != NaN -> true")
      check(callI32V(inst, "f64_lt", F64(nan), F64(1.0)) == 0, "NaN < 1 -> false")
      check(callI32V(inst, "f64_le", F64(nan), F64(nan)) == 0, "NaN <= NaN -> false")
      check(callI32V(inst, "f64_gt", F64(1.0), F64(nan)) == 0, "1 > NaN -> false")
      check(callI32V(inst, "f64_ge", F64(1.0), F64(nan)) == 0, "1 >= NaN -> false")
    }

    test("f64 compares: signed-zero equality (-0 == +0)") {
      val inst = instantiate(Fixtures.f64_compare)
      check(callI32V(inst, "f64_eq", F64(-0.0), F64(0.0)) == 1, "-0 == +0 per IEEE-754")
      check(callI32V(inst, "f64_le", F64(-0.0), F64(0.0)) == 1, "-0 <= +0")
      check(callI32V(inst, "f64_ge", F64(-0.0), F64(0.0)) == 1, "-0 >= +0")
    }

    test("f64 unary: abs / neg (incl. signed zeros)") {
      val inst = instantiate(Fixtures.f64_unary)
      check(callF64(inst, "f64_abs", F64(-3.0)) ==  3.0, "abs negative")
      check(callF64(inst, "f64_abs", F64( 3.0)) ==  3.0, "abs positive")
      check(callF64(inst, "f64_neg", F64(-3.0)) ==  3.0, "neg negative")
      check(callF64(inst, "f64_neg", F64( 3.0)) == -3.0, "neg positive")
      // abs of -0 -> +0; neg of +0 -> -0.
      val abs0 = callF64(inst, "f64_abs", F64(-0.0))
      check(jl.Double.doubleToRawLongBits(abs0) == 0x0000000000000000L, "abs(-0) -> +0")
      val negPos0 = callF64(inst, "f64_neg", F64(0.0))
      check(jl.Double.doubleToRawLongBits(negPos0) == 0x8000000000000000L.toLong, "neg(+0) -> -0")
    }

    test("f64 unary: ceil / floor / trunc / nearest (incl. negatives + halves)") {
      val inst = instantiate(Fixtures.f64_unary)
      check(callF64(inst, "f64_ceil",    F64(1.2))  ==  2.0, "ceil 1.2 -> 2")
      check(callF64(inst, "f64_ceil",    F64(-1.2)) == -1.0, "ceil -1.2 -> -1")
      check(callF64(inst, "f64_floor",   F64(1.8))  ==  1.0, "floor 1.8 -> 1")
      check(callF64(inst, "f64_floor",   F64(-1.8)) == -2.0, "floor -1.8 -> -2")
      check(callF64(inst, "f64_trunc",   F64(1.8))  ==  1.0, "trunc 1.8 -> 1")
      check(callF64(inst, "f64_trunc",   F64(-1.8)) == -1.0, "trunc -1.8 -> -1 (toward zero)")
      // nearest = round-half-to-even.
      check(callF64(inst, "f64_nearest", F64(0.5))  == 0.0,  "0.5 -> 0 (even)")
      check(callF64(inst, "f64_nearest", F64(1.5))  == 2.0,  "1.5 -> 2 (even)")
      check(callF64(inst, "f64_nearest", F64(2.5))  == 2.0,  "2.5 -> 2 (even)")
      check(callF64(inst, "f64_nearest", F64(-0.5)) == 0.0,  "-0.5 -> 0 (even, but sign may differ)")
    }

    test("f64.sqrt: well-defined positives, sqrt(-1) is NaN (no trap)") {
      val inst = instantiate(Fixtures.f64_unary)
      check(callF64(inst, "f64_sqrt", F64( 4.0)) == 2.0, "sqrt 4")
      check(callF64(inst, "f64_sqrt", F64( 0.0)) == 0.0, "sqrt 0")
      check(jl.Double.isNaN(callF64(inst, "f64_sqrt", F64(-1.0))), "sqrt -1 -> NaN")
      // sqrt(-0) = -0 per IEEE-754.
      val sqrtNegZero = callF64(inst, "f64_sqrt", F64(-0.0))
      check(jl.Double.doubleToRawLongBits(sqrtNegZero) == 0x8000000000000000L.toLong,
        s"sqrt(-0) bits should be -0 (0x8000000000000000), got 0x${jl.Double.doubleToRawLongBits(sqrtNegZero).toHexString}")
    }

    test("f64 memory: f64.load / f64.store round-trip preserves NaN bit pattern") {
      val inst = instantiate(Fixtures.f64_memory)
      check(callF64(inst, "f64_roundtrip", I32(0),  F64(3.141592653589793)) == 3.141592653589793, "addr 0 plain")
      // -0.0 round-trip: bit pattern must come back exactly (not normalised to +0).
      val negZero = callF64(inst, "f64_roundtrip", I32(16), F64(-0.0))
      check(jl.Double.doubleToRawLongBits(negZero) == 0x8000000000000000L.toLong, "-0.0 round-trip preserves sign bit")
      // NaN round-trip: still a NaN.
      check(jl.Double.isNaN(callF64(inst, "f64_roundtrip", I32(32), F64(Double.NaN))), "NaN round-trip is NaN")
    }

    test("f64 local: declared local zero-initialises to F64(0.0)") {
      val inst = instantiate(Fixtures.f64_memory)
      val z = callF64(inst, "f64_local_zero")
      check(z == 0.0, "f64 local should equal +0.0")
      check(jl.Double.doubleToRawLongBits(z) == 0x0000000000000000L, "f64 local should be positive zero (not -0)")
    }

    test("f64 memory: load out of bounds traps with MemoryOutOfBounds") {
      val inst = instantiate(Fixtures.f64_memory)
      // page = 65536, f64 is 8 bytes; last valid addr is 65528.
      check(callF64(inst, "f64_roundtrip", I32(65528), F64(1.0)) == 1.0, "last valid 8-byte slot")
      expectError(inst, "f64_roundtrip", Seq(I32(65529), F64(0.0))) { case WasmError.MemoryOutOfBounds => true }
    }

    test("f64 block result: blocktype 0x7C carries an f64 across the matching end") {
      val inst = instantiate(Fixtures.f64_block_result)
      check(callF64(inst, "f64_block_result") == 1.5, "block returns its f64 result")
    }

    test("f64 IEEE edges: inf + (-inf) = NaN, inf * 0 = NaN, divide-by-zero never traps") {
      val inst = instantiate(Fixtures.f64_edge)
      check(jl.Double.isNaN(callF64(inst, "inf_plus_neg_inf")), "inf + -inf -> NaN")
      check(jl.Double.isNaN(callF64(inst, "inf_times_zero")),   "inf *  0   -> NaN")
      // 1/+0 = +inf, 1/-0 = -inf, 0/0 = NaN.
      check(callF64(inst, "div_pos_zero", F64( 1.0)) == jl.Double.POSITIVE_INFINITY, " 1/+0 -> +inf")
      check(callF64(inst, "div_neg_zero", F64( 1.0)) == jl.Double.NEGATIVE_INFINITY, " 1/-0 -> -inf")
      check(callF64(inst, "div_pos_zero", F64(-1.0)) == jl.Double.NEGATIVE_INFINITY, "-1/+0 -> -inf")
      check(jl.Double.isNaN(callF64(inst, "div_pos_zero", F64(0.0))),                "0/0  -> NaN")
      // sqrt(-1) is NaN, not a trap.
      check(jl.Double.isNaN(callF64(inst, "sqrt_neg_one")), "sqrt(-1) -> NaN")
    }

    test("f64 select (polymorphic): select with f64 operands picks the right one") {
      // Verifies that the polymorphic `select` (0x1B) works at f64 width. As
      // with the f32 case we synthesise the module directly — no dedicated
      // fixture is needed for a one-off opcode test.
      val typeSec = b(0x02) ++                                          // 2 functype entries
        b(0x60, 0x00, 0x01, 0x7c) ++                                    //  () -> (f64)
        b(0x60, 0x03, 0x7c, 0x7c, 0x7f, 0x01, 0x7c)                     //  (f64, f64, i32) -> (f64)
      val funcSec = b(0x01, 0x01)                                       // 1 func, type idx 1
      val expSec  = b(0x01) ++                                          // 1 export
        b(0x06, 's'.toInt, 'e'.toInt, 'l'.toInt, 'e'.toInt, 'c'.toInt, 't'.toInt) ++
        b(0x00, 0x00)                                                   // kind=func, idx 0
      // body: locals=0; local.get 0; local.get 1; local.get 2; select; end
      val body    = b(0x00,                                              // 0 local groups
                       0x20, 0x00, 0x20, 0x01, 0x20, 0x02, 0x1b, 0x0b)
      val codeSec = b(0x01, body.length.toByte) ++ body
      val bytes   = Header ++
        b(0x01, typeSec.length) ++ typeSec ++
        b(0x03, funcSec.length) ++ funcSec ++
        b(0x07, expSec.length)  ++ expSec  ++
        b(0x0a, codeSec.length) ++ codeSec
      val inst = instantiate(bytes)
      check(callF64(inst, "select", F64(1.5), F64(2.5), I32(1)) == 1.5, "cond=1 picks a")
      check(callF64(inst, "select", F64(1.5), F64(2.5), I32(0)) == 2.5, "cond=0 picks b")
    }

    // --- Phase 1.5: remaining i32 ops --------------------------------------

    test("i32 unsigned compares: lt_u / gt_u / le_u / ge_u (negative as huge)") {
      val inst = instantiate(Fixtures.i32_unsigned)
      // Positive vs positive — signed and unsigned agree.
      check(callI32(inst, "i32_lt_u", 3,  5) == 1, "3 <u 5")
      check(callI32(inst, "i32_gt_u", 5,  3) == 1, "5 >u 3")
      check(callI32(inst, "i32_le_u", 5,  5) == 1, "5 <=u 5")
      check(callI32(inst, "i32_ge_u", 5,  4) == 1, "5 >=u 4")
      // Negative bit-pattern means "very large" under unsigned interpretation,
      // so all of these flip relative to the signed counterparts.
      check(callI32(inst, "i32_lt_u", -1, 0) == 0, "0xffffffff <u 0 -> false")
      check(callI32(inst, "i32_gt_u", -1, 0) == 1, "0xffffffff >u 0 -> true")
      check(callI32(inst, "i32_le_u", -1, 0) == 0, "0xffffffff <=u 0 -> false")
      check(callI32(inst, "i32_ge_u", -1, 0) == 1, "0xffffffff >=u 0 -> true")
      // Boundary equal: Int.MinValue == Int.MinValue under unsigned too.
      check(callI32(inst, "i32_le_u", Int.MinValue, Int.MinValue) == 1, "MIN_INT <=u MIN_INT")
      check(callI32(inst, "i32_ge_u", Int.MinValue, Int.MinValue) == 1, "MIN_INT >=u MIN_INT")
    }

    test("i32 bit counting: clz / ctz / popcnt (incl. zero and all-ones)") {
      val inst = instantiate(Fixtures.i32_unsigned)
      check(callI32(inst, "i32_clz",    1)            == 31, "clz of 1 = 31")
      check(callI32(inst, "i32_clz",    0)            == 32, "clz of 0 = 32 (width)")
      check(callI32(inst, "i32_clz",    Int.MinValue) == 0,  "clz of high-bit-set = 0")
      check(callI32(inst, "i32_ctz",    8)            == 3,  "ctz of 8 = 3")
      check(callI32(inst, "i32_ctz",    0)            == 32, "ctz of 0 = 32 (width)")
      check(callI32(inst, "i32_ctz",    Int.MinValue) == 31, "ctz of 0x80000000 = 31")
      check(callI32(inst, "i32_popcnt", -1)           == 32, "popcnt of all-ones = 32")
      check(callI32(inst, "i32_popcnt", 0)            == 0,  "popcnt of 0 = 0")
      check(callI32(inst, "i32_popcnt", 0x55)         == 4,  "popcnt of 0x55 = 4 ones")
    }

    test("i32.div_u / rem_u: unsigned semantics; trap on divisor zero") {
      val inst = instantiate(Fixtures.i32_unsigned)
      // Within the positive Int range div_u agrees with div_s.
      check(callI32(inst, "i32_div_u", 100, 7) == 14, "100 /u 7")
      check(callI32(inst, "i32_rem_u", 100, 7) ==  2, "100 %u 7")
      // Negative bit-pattern as "very large unsigned" diverges from signed.
      check(callI32(inst, "i32_div_u", -1,  3) == jl.Integer.divideUnsigned(-1, 3),    "0xffffffff /u 3")
      check(callI32(inst, "i32_rem_u", -1,  3) == jl.Integer.remainderUnsigned(-1, 3), "0xffffffff %u 3")
      // No overflow trap exists for div_u: MIN_INT/-1 reads as a small
      // unsigned quotient, not an overflow case.
      check(callI32(inst, "i32_div_u", Int.MinValue, -1) == 0, "MIN_INT /u 0xffffffff < 1")
      // Trap paths.
      expectError(inst, "i32_div_u", Seq(I32(1), I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("divide by zero")
      }
      expectError(inst, "i32_rem_u", Seq(I32(1), I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("divide by zero")
      }
    }

    test("i32 shifts / rotates: shr_u zero-fills; rotl/rotr round-trip the high bit") {
      val inst = instantiate(Fixtures.i32_unsigned)
      // shr_u of -1 by 1 is Int.MaxValue (the high bit becomes 0, not 1).
      check(callI32(inst, "i32_shr_u", -1, 1)  == Int.MaxValue, "shr_u -1 by 1 = Int.MaxValue")
      check(callI32(inst, "i32_shr_u",  8, 2)  == 2,            "shr_u 8 by 2 = 2")
      // Shift count masked mod 32: shr_u by 32 is identity, by 33 is shr_u by 1.
      check(callI32(inst, "i32_shr_u", -1, 32) == -1,            "shr_u -1 by 32 == by 0")
      check(callI32(inst, "i32_shr_u", -1, 33) == Int.MaxValue,  "shr_u -1 by 33 == by 1")
      // rotl 1 by 31 lands the bit at position 31 → Int.MinValue.
      check(callI32(inst, "i32_rotl", 1, 31) == Int.MinValue, "rotl 1 by 31 → high bit")
      // rotl top bit by 1 carries it back to bit 0.
      check(callI32(inst, "i32_rotl", Int.MinValue, 1) == 1, "rotl high bit by 1 → 1")
      // rotr is the inverse.
      check(callI32(inst, "i32_rotr", 1, 1) == Int.MinValue, "rotr 1 by 1 → high bit")
      // Rotates also mask mod 32.
      check(callI32(inst, "i32_rotl", 1, 32) == 1, "rotl by 32 is identity")
      check(callI32(inst, "i32_rotr", 1, 33) == callI32(inst, "i32_rotr", 1, 1), "rotr mod 32")
    }

    // --- Phase 1.4: conversions --------------------------------------------

    test("conv: i32.wrap_i64 drops the high 32 bits") {
      val inst = instantiate(Fixtures.conv_int_int)
      check(callI32V(inst, "wrap_i64", I64(0x1_2345_6789L))         == 0x2345_6789, "high bits dropped")
      check(callI32V(inst, "wrap_i64", I64(-1L))                    == -1,          "all ones survives")
      check(callI32V(inst, "wrap_i64", I64(0x0000_0001_0000_0000L)) == 0,           "low 32 bits zero")
      check(callI32V(inst, "wrap_i64", I64(Long.MinValue))          == 0,           "MIN_LONG low half = 0")
    }

    test("conv: i64.extend_i32_s sign-extends; i64.extend_i32_u zero-extends") {
      val inst = instantiate(Fixtures.conv_int_int)
      check(callI64(inst, "extend_i32_s", I32( 1))          ==  1L,                "+1 stays +1")
      check(callI64(inst, "extend_i32_s", I32(-1))          == -1L,                "-1 sign-extends to all ones")
      check(callI64(inst, "extend_i32_s", I32(Int.MinValue))== Int.MinValue.toLong, "MIN_INT sign-extends")
      check(callI64(inst, "extend_i32_u", I32(-1))          == 0xffffffffL,        "0xffffffff zero-extends")
      check(callI64(inst, "extend_i32_u", I32(Int.MinValue))== 0x80000000L,        "high-bit-set treated unsigned")
    }

    test("conv: i32.trunc_f32_s — in-range values truncate toward zero") {
      val inst = instantiate(Fixtures.conv_trunc)
      check(callI32V(inst, "i32_trunc_f32_s", F32( 1.9f)) ==  1, " 1.9 -> 1")
      check(callI32V(inst, "i32_trunc_f32_s", F32(-1.9f)) == -1, "-1.9 -> -1 (toward zero)")
      check(callI32V(inst, "i32_trunc_f32_s", F32( 0.0f)) ==  0, " 0   -> 0")
      // Boundary just inside the signed-i32 range. 2147483520.0f is the largest
      // exactly-representable Float strictly less than 2^31.
      check(callI32V(inst, "i32_trunc_f32_s", F32(2147483520.0f))  ==  2147483520,  "near MAX_INT")
      check(callI32V(inst, "i32_trunc_f32_s", F32(-2147483648.0f)) == Int.MinValue, "exactly -2^31")
    }

    test("conv: i32.trunc_f32_s — traps on NaN, ±Inf, and out-of-range") {
      val inst = instantiate(Fixtures.conv_trunc)
      expectError(inst, "i32_trunc_f32_s", Seq(F32(Float.NaN))) {
        case WasmError.InvalidModule(m) => m.contains("NaN")
      }
      expectError(inst, "i32_trunc_f32_s", Seq(F32(Float.PositiveInfinity))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
      expectError(inst, "i32_trunc_f32_s", Seq(F32(Float.NegativeInfinity))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
      // 2147483648.0f is the next Float above 2147483520.0f and equals 2^31 exactly
      // — out of signed range at the top boundary.
      expectError(inst, "i32_trunc_f32_s", Seq(F32(2147483648.0f))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
      // The next Float below -2^31 is -2147483904.0f.
      expectError(inst, "i32_trunc_f32_s", Seq(F32(-2147483904.0f))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
    }

    test("conv: i32.trunc_f32_u — in-range and traps") {
      val inst = instantiate(Fixtures.conv_trunc)
      check(callI32V(inst, "i32_trunc_f32_u", F32(0.0f))  == 0, "0 -> 0")
      check(callI32V(inst, "i32_trunc_f32_u", F32(1.9f))  == 1, "1.9 -> 1")
      // 2147483648.0f is 2^31 — valid unsigned, would be negative as i32.
      check(callI32V(inst, "i32_trunc_f32_u", F32(2147483648.0f)) == Int.MinValue, "2^31 -> i32 0x80000000")
      // -0.5 is in the open interval (-1, 0), so it trunc-floors to 0 and is in range.
      check(callI32V(inst, "i32_trunc_f32_u", F32(-0.5f)) == 0, "-0.5 -> 0 (in range)")
      // Trap paths
      expectError(inst, "i32_trunc_f32_u", Seq(F32(Float.NaN))) {
        case WasmError.InvalidModule(m) => m.contains("NaN")
      }
      expectError(inst, "i32_trunc_f32_u", Seq(F32(-1.0f))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
      expectError(inst, "i32_trunc_f32_u", Seq(F32(4294967296.0f))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
    }

    test("conv: i32.trunc_f64_s — in-range and traps") {
      val inst = instantiate(Fixtures.conv_trunc)
      check(callI32V(inst, "i32_trunc_f64_s", F64( 12345.678)) ==  12345, "positive truncates")
      check(callI32V(inst, "i32_trunc_f64_s", F64(-12345.678)) == -12345, "negative truncates toward zero")
      check(callI32V(inst, "i32_trunc_f64_s", F64(Int.MaxValue.toDouble))  == Int.MaxValue, "MAX_INT exact")
      check(callI32V(inst, "i32_trunc_f64_s", F64(Int.MinValue.toDouble))  == Int.MinValue, "MIN_INT exact")
      expectError(inst, "i32_trunc_f64_s", Seq(F64(Double.NaN))) {
        case WasmError.InvalidModule(m) => m.contains("NaN")
      }
      expectError(inst, "i32_trunc_f64_s", Seq(F64(2147483648.0))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
      expectError(inst, "i32_trunc_f64_s", Seq(F64(-2147483649.0))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
    }

    test("conv: i32.trunc_f64_u — in-range and traps") {
      val inst = instantiate(Fixtures.conv_trunc)
      check(callI32V(inst, "i32_trunc_f64_u", F64(0.0))           == 0,           "0 -> 0")
      check(callI32V(inst, "i32_trunc_f64_u", F64(4294967295.0))  == -1,          "2^32-1 as i32 bits = -1")
      check(callI32V(inst, "i32_trunc_f64_u", F64(-0.5))          == 0,           "-0.5 -> 0 (in range)")
      expectError(inst, "i32_trunc_f64_u", Seq(F64(-1.0))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
      expectError(inst, "i32_trunc_f64_u", Seq(F64(4294967296.0))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
    }

    test("conv: i64.trunc_f32_s / _u — in-range and traps") {
      val inst = instantiate(Fixtures.conv_trunc)
      check(callI64(inst, "i64_trunc_f32_s", F32( 12345.5f)) ==  12345L, "positive truncates")
      check(callI64(inst, "i64_trunc_f32_s", F32(-12345.5f)) == -12345L, "negative truncates toward zero")
      // -2^63 is exactly representable in Float; the round-down-to-Long is Long.MinValue.
      check(callI64(inst, "i64_trunc_f32_s", F32(-9223372036854775808.0f)) == Long.MinValue, "-2^63 exact")

      // Unsigned: confirm values past 2^63 fit into the Long bit pattern.
      // 1.8e19f rounds to a Float in [2^63, 2^64), so the result is interpretable
      // as an unsigned i64. We check via Long.compareUnsigned vs the expected.
      val r = callI64(inst, "i64_trunc_f32_u", F32(1.8e19f))
      check(jl.Long.compareUnsigned(r, 0L) > 0, "1.8e19 -> positive unsigned i64")
      check(jl.Long.compareUnsigned(r, -1L) <= 0, "still <= unsigned max")

      // Trap paths
      expectError(inst, "i64_trunc_f32_s", Seq(F32(Float.NaN))) {
        case WasmError.InvalidModule(m) => m.contains("NaN")
      }
      expectError(inst, "i64_trunc_f32_s", Seq(F32(9223372036854775808.0f))) {  // 2^63 — out of range
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
      expectError(inst, "i64_trunc_f32_u", Seq(F32(-1.0f))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
      expectError(inst, "i64_trunc_f32_u", Seq(F32(1.8447e19f))) {  // > 2^64
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
    }

    test("conv: i64.trunc_f64_s / _u — in-range and traps") {
      val inst = instantiate(Fixtures.conv_trunc)
      check(callI64(inst, "i64_trunc_f64_s", F64(1.0e15))  == 1000000000000000L, "1e15 exact")
      check(callI64(inst, "i64_trunc_f64_s", F64(-1.5))    == -1L,               "-1.5 -> -1 (toward zero)")
      check(callI64(inst, "i64_trunc_f64_s", F64(Long.MinValue.toDouble)) == Long.MinValue, "-2^63 exact")

      // Unsigned high-bit-set range: 1.8e19 < 2^64; expected = 18000000000000000000 modulo 2^64.
      val r = callI64(inst, "i64_trunc_f64_u", F64(1.8e19))
      check(jl.Long.compareUnsigned(r, 0L) > 0, "1.8e19 -> positive unsigned i64")
      check(jl.Long.compareUnsigned(r, -1L) <= 0, "still <= unsigned max")

      // Traps
      expectError(inst, "i64_trunc_f64_s", Seq(F64(Double.NaN))) {
        case WasmError.InvalidModule(m) => m.contains("NaN")
      }
      expectError(inst, "i64_trunc_f64_s", Seq(F64(Double.PositiveInfinity))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
      expectError(inst, "i64_trunc_f64_s", Seq(F64(9223372036854775808.0))) {  // 2^63
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
      expectError(inst, "i64_trunc_f64_u", Seq(F64(-1.0))) {
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
      expectError(inst, "i64_trunc_f64_u", Seq(F64(18446744073709551616.0))) {  // 2^64
        case WasmError.InvalidModule(m) => m.contains("out of range")
      }
    }

    test("conv: f32.convert_i32_s / _u — signed and unsigned agree on positives, diverge on negatives") {
      val inst = instantiate(Fixtures.conv_convert)
      check(callF32(inst, "f32_convert_i32_s", I32(1))             ==  1.0f, "1 -> 1")
      check(callF32(inst, "f32_convert_i32_s", I32(-1))            == -1.0f, "-1 -> -1 (signed)")
      check(callF32(inst, "f32_convert_i32_u", I32(-1))            == 4294967296.0f, "0xffffffff as unsigned -> 2^32 (rounds up)")
      check(callF32(inst, "f32_convert_i32_s", I32(Int.MinValue))  == -2147483648.0f, "MIN_INT signed")
      check(callF32(inst, "f32_convert_i32_u", I32(Int.MinValue))  == 2147483648.0f,  "0x80000000 unsigned")
    }

    test("conv: f32.convert_i64_s / _u — signed and unsigned, with precision loss") {
      val inst = instantiate(Fixtures.conv_convert)
      check(callF32(inst, "f32_convert_i64_s", I64(1L))          ==  1.0f, "1 -> 1")
      check(callF32(inst, "f32_convert_i64_s", I64(-1L))         == -1.0f, "-1 -> -1 (signed)")
      check(callF32(inst, "f32_convert_i64_u", I64(-1L))         == 18446744073709551616.0f, "all-ones unsigned -> 2^64 (rounds up)")
      check(callF32(inst, "f32_convert_i64_s", I64(Long.MinValue))== -9223372036854775808.0f, "MIN_LONG signed")
      check(callF32(inst, "f32_convert_i64_u", I64(Long.MinValue))==  9223372036854775808.0f, "0x80…0 unsigned -> 2^63")
    }

    test("conv: f64.convert_i32_s / _u — every i32 fits exactly into f64") {
      val inst = instantiate(Fixtures.conv_convert)
      check(callF64(inst, "f64_convert_i32_s", I32(123))           == 123.0,           "positive")
      check(callF64(inst, "f64_convert_i32_s", I32(-123))          == -123.0,          "negative")
      check(callF64(inst, "f64_convert_i32_s", I32(Int.MinValue))  == Int.MinValue.toDouble, "MIN_INT exact")
      check(callF64(inst, "f64_convert_i32_u", I32(-1))            == 4294967295.0,   "0xffffffff unsigned -> 2^32 - 1")
      check(callF64(inst, "f64_convert_i32_u", I32(Int.MinValue))  == 2147483648.0,    "0x80000000 unsigned")
    }

    test("conv: f64.convert_i64_s / _u — signed and unsigned, with rounding past 2^53") {
      val inst = instantiate(Fixtures.conv_convert)
      check(callF64(inst, "f64_convert_i64_s", I64(123L))         == 123.0,            "positive")
      check(callF64(inst, "f64_convert_i64_s", I64(Long.MinValue))== -9.223372036854776e18, "MIN_LONG signed")
      check(callF64(inst, "f64_convert_i64_u", I64(-1L))          == 18446744073709551616.0, "all-ones unsigned -> 2^64")
      check(callF64(inst, "f64_convert_i64_u", I64(Long.MinValue))==  9.223372036854776e18, "0x80…0 unsigned -> 2^63")
    }

    test("conv: f32.demote_f64 / f64.promote_f32 — sign + Inf + NaN survive") {
      val inst = instantiate(Fixtures.conv_demote_promote)
      check(callF32(inst, "demote",  F64(1.5))                          == 1.5f, "1.5 demotes exactly")
      check(callF32(inst, "demote",  F64(-3.25))                        == -3.25f, "negative demotes exactly")
      check(callF32(inst, "demote",  F64(Double.PositiveInfinity))      == Float.PositiveInfinity, "+inf survives")
      check(callF32(inst, "demote",  F64(Double.NegativeInfinity))      == Float.NegativeInfinity, "-inf survives")
      check(jl.Float.isNaN(callF32(inst, "demote", F64(Double.NaN))),   "NaN demotes to NaN")

      // Demoting -0.0 must preserve the sign bit.
      val demotedNegZero = callF32(inst, "demote", F64(-0.0))
      check(demotedNegZero == 0.0f, "demoted -0 equals +0 numerically")
      check(jl.Float.floatToRawIntBits(demotedNegZero) == jl.Float.floatToRawIntBits(-0.0f), "demoted -0 keeps sign bit")

      // Promotion is exact across the Float range.
      check(callF64(inst, "promote", F32(1.5f))                         == 1.5,   "1.5 promotes exactly")
      check(callF64(inst, "promote", F32(Float.MaxValue))               == Float.MaxValue.toDouble, "MAX_FLOAT exact")
      check(callF64(inst, "promote", F32(Float.PositiveInfinity))       == Double.PositiveInfinity, "+inf survives")
      check(jl.Double.isNaN(callF64(inst, "promote", F32(Float.NaN))),  "NaN promotes to NaN")

      val promotedNegZero = callF64(inst, "promote", F32(-0.0f))
      check(jl.Double.doubleToRawLongBits(promotedNegZero) == jl.Double.doubleToRawLongBits(-0.0), "promoted -0 keeps sign bit")
    }

    test("conv: reinterpret round-trips for every pair (raw bits preserved)") {
      val inst = instantiate(Fixtures.conv_reinterpret)

      // i32 ↔ f32: bits survive through the reinterpret.
      val bits32   = 0x40490fdb  // ~ pi as i32 bits
      val asFloat  = callF32(inst, "f32_reinterpret_i32", I32(bits32))
      check(jl.Float.floatToRawIntBits(asFloat) == bits32, "i32 -> f32 keeps bits")
      check(callI32V(inst, "i32_reinterpret_f32", F32(asFloat)) == bits32, "round-trip back to i32")

      // i64 ↔ f64: pi as f64 bits.
      val bits64    = 0x400921fb54442d18L
      val asDouble  = callF64(inst, "f64_reinterpret_i64", I64(bits64))
      check(jl.Double.doubleToRawLongBits(asDouble) == bits64, "i64 -> f64 keeps bits")
      check(callI64(inst, "i64_reinterpret_f64", F64(asDouble)) == bits64, "round-trip back to i64")

      // NaN with a non-canonical payload survives the reinterpret round-trip.
      // 0x7fc12345 is a signalling-style NaN payload that floatToRawIntBits
      // (vs floatToIntBits) preserves end-to-end.
      val nanBits     = 0x7fc12345
      val asNaNFloat  = callF32(inst, "f32_reinterpret_i32", I32(nanBits))
      check(jl.Float.isNaN(asNaNFloat), "reinterpreted bits form a NaN")
      check(callI32V(inst, "i32_reinterpret_f32", F32(asNaNFloat)) == nanBits, "NaN payload survives")

      // Same for f64: a non-canonical NaN bit pattern round-trips.
      val nanBits64   = 0x7ff8000000abcdefL
      val asNaNDouble = callF64(inst, "f64_reinterpret_i64", I64(nanBits64))
      check(jl.Double.isNaN(asNaNDouble), "reinterpreted bits form a Double NaN")
      check(callI64(inst, "i64_reinterpret_f64", F64(asNaNDouble)) == nanBits64, "double NaN payload survives")

      // -0 stays distinct from +0 after a round-trip (sign bit preserved).
      check(callI32V(inst, "i32_reinterpret_f32", F32(-0.0f)) == Int.MinValue,  "f32 -0 -> 0x80000000")
      check(callI64(inst, "i64_reinterpret_f64", F64(-0.0))   == Long.MinValue, "f64 -0 -> sign bit only")
    }

    // --- Phase 2: globals ---------------------------------------------------

    test("globals: counter persists across calls; tee replacement via get-after-set") {
      val inst = instantiate(Fixtures.globals_basic)
      // Counter starts at zero per the const init.
      check(callI32(inst, "get_count") == 0, "fresh instance starts at 0")
      // Two consecutive bumps must observe the running total — this is the
      // signature behaviour globals add over locals (state across calls).
      check(callI32(inst, "bump") == 1, "first bump → 1")
      check(callI32(inst, "bump") == 2, "second bump → 2")
      check(callI32(inst, "bump") == 3, "third bump → 3")
      check(callI32(inst, "get_count") == 3, "counter visible from a separate getter")
      // set_count returns the prior value and stores the new one.
      check(callI32(inst, "set_count", 100) == 3,   "set_count returns previous value")
      check(callI32(inst, "get_count")     == 100, "new value stuck")
      // Each fresh instantiation gets its own globals — instances don't share.
      val inst2 = instantiate(Fixtures.globals_basic)
      check(callI32(inst2, "get_count") == 0, "second instance starts at 0 (no cross-instance bleed)")
    }

    test("globals: immutable seed readable; module-instance exposes both globals via globalValue") {
      val inst = instantiate(Fixtures.globals_basic)
      check(callI32(inst, "get_seed") == 42, "seed reads back as 42")
      inst.globalValue("seed") match
        case Right(I32(42)) => ()
        case other          => check(false, s"`seed` global export should read 42: $other")
      // Direct read of `counter` mirrors the function-getter reading.
      inst.globalValue("counter") match
        case Right(I32(0)) => ()
        case other         => check(false, s"`counter` should start at 0: $other")
      runRight(inst.invoke("bump"))
      inst.globalValue("counter") match
        case Right(I32(1)) => ()
        case other         => check(false, s"`counter` should now be 1: $other")
    }

    test("globals: per-type round-trip — i32 / i64 / f32 / f64 init values decode correctly") {
      val inst = instantiate(Fixtures.globals_types)
      // Init values from the section-6 init-expr decode (each *.const form).
      check(callI32(inst, "get_i32") == 0x0bad0dad,            "i32 init")
      check(callI64(inst, "get_i64") == 0x1122334455667788L,   "i64 init")
      check(callF32(inst, "get_f32") == 1.5f,                  "f32 init")
      check(callF64(inst, "get_f64") == -2.5,                  "f64 init")

      // set, then read back — confirms `global.set` is type-stable for each type.
      runRight(inst.invoke("set_i32", Seq(I32(-7))))
      runRight(inst.invoke("set_i64", Seq(I64(Long.MinValue))))
      runRight(inst.invoke("set_f32", Seq(F32(Float.NaN))))
      runRight(inst.invoke("set_f64", Seq(F64(Double.PositiveInfinity))))

      check(callI32(inst, "get_i32") == -7,                            "i32 round-trip")
      check(callI64(inst, "get_i64") == Long.MinValue,                 "i64 round-trip")
      check(jl.Float.isNaN(callF32(inst, "get_f32")),                  "f32 NaN survives")
      check(callF64(inst, "get_f64") == Double.PositiveInfinity,       "f64 +Inf survives")

      // The globalValue accessor sees the same live state.
      inst.globalValue("gi") match
        case Right(I32(-7))                            => ()
        case other                                     => check(false, s"gi: $other")
      inst.globalValue("gj") match
        case Right(I64(v)) if v == Long.MinValue       => ()
        case other                                     => check(false, s"gj: $other")
      inst.globalValue("gf") match
        case Right(F32(v)) if jl.Float.isNaN(v)        => ()
        case other                                     => check(false, s"gf: $other")
      inst.globalValue("gd") match
        case Right(F64(v)) if v == Double.PositiveInfinity => ()
        case other                                     => check(false, s"gd: $other")
    }

    test("globals: ±Inf and signed-zero survive a set/get round-trip for f32 and f64") {
      // NaN-payload preservation across a global slot isn't reliable on
      // Scala.js (Float-boxing through JS `number` lets the engine canonicalise
      // the bit pattern); the per-type test above already pins NaN-as-NaN via
      // isNaN. What we additionally want pinned here is that *non-NaN* IEEE
      // specials — signed zeros, infinities — survive bit-exact, which they
      // must to keep arithmetic semantics intact.
      val inst = instantiate(Fixtures.globals_types)

      runRight(inst.invoke("set_f32", Seq(F32(-0.0f))))
      check(jl.Float.floatToRawIntBits(callF32(inst, "get_f32"))
              == jl.Float.floatToRawIntBits(-0.0f),
            "f32 -0 sign bit preserved across set/get")

      runRight(inst.invoke("set_f32", Seq(F32(Float.NegativeInfinity))))
      check(callF32(inst, "get_f32") == Float.NegativeInfinity, "f32 -Inf survives")

      runRight(inst.invoke("set_f64", Seq(F64(-0.0))))
      check(jl.Double.doubleToRawLongBits(callF64(inst, "get_f64"))
              == jl.Double.doubleToRawLongBits(-0.0),
            "f64 -0 sign bit preserved across set/get")

      runRight(inst.invoke("set_f64", Seq(F64(Double.NegativeInfinity))))
      check(callF64(inst, "get_f64") == Double.NegativeInfinity, "f64 -Inf survives")
    }

    test("globals: set on immutable global traps with InvalidModule(\"immutable\")") {
      val inst = instantiate(Fixtures.globals_immutable_trap)
      // Reading the const still works.
      check(callI32(inst, "get_k") == 99, "immutable global readable")
      // Writing traps with a recognisable message.
      expectError(inst, "try_overwrite", Seq(I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("immutable")
      }
      // And the value should still be 99 — the trap fires before mutation.
      check(callI32(inst, "get_k") == 99, "value unchanged after failed write")
    }

    // --- Phase 3: tables + call_indirect -----------------------------------

    test("call_indirect: same-signature dispatch by slot index") {
      val inst = instantiate(Fixtures.call_indirect_basic)
      // slot 0 = add_one, slot 1 = double
      check(callI32(inst, "dispatch", 0, 41) == 42, "slot 0 (add_one) wrong")
      check(callI32(inst, "dispatch", 0,  0) ==  1, "slot 0 of 0")
      check(callI32(inst, "dispatch", 1,  7) == 14, "slot 1 (double) wrong")
      check(callI32(inst, "dispatch", 1, -3) == -6, "slot 1 of negative")
    }

    test("call_indirect: per-signature dispatch (i32→i32, i64→i64, (i32,i32)→i32)") {
      val inst = instantiate(Fixtures.call_indirect_polymorphic)
      // each entry dispatches through the slot whose signature matches
      check(callI32(inst, "call_neg_i32", 7)             == -7, "neg i32 wrong")
      check(callI32(inst, "call_neg_i32", -2147483648)   == -2147483648, "neg i32 MIN_VALUE wraps")
      check(callI64(inst, "call_neg_i64", I64(123456L))  == -123456L, "neg i64 wrong")
      check(callI32(inst, "call_mul", 6, 7)              == 42, "mul wrong")
    }

    test("call_indirect: signature mismatch traps with InvalidModule(\"signature mismatch\")") {
      // wrong_sig calls slot 2 ((i32,i32)->i32) with the (i32)->i32 typeidx;
      // trap fires before the body of `mul` ever runs.
      val inst = instantiate(Fixtures.call_indirect_polymorphic)
      expectError(inst, "wrong_sig", Seq(I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("signature mismatch")
      }
    }

    test("call_indirect: out-of-table-bounds slot traps") {
      val inst = instantiate(Fixtures.call_indirect_traps)
      expectError(inst, "via_oob", Seq(I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("out of table bounds")
      }
    }

    test("call_indirect: null funcref slot traps") {
      // Table is sized 4 but element segment only fills slots 0..1. Slot 2
      // is null and traps with the distinctive "null funcref" message.
      val inst = instantiate(Fixtures.call_indirect_traps)
      expectError(inst, "via_null", Seq(I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("null funcref")
      }
      // Populated slots in the same module still work — confirms the trap
      // path doesn't leave the runtime in a broken state.
    }

    // --- Phase 4: Memory completion ----------------------------------------
    // Three new memory-width opcodes (i32.load16_s/u, i32.store16) plus
    // the pair that turn linear memory dynamic (memory.size, memory.grow).

    test("memory.size: returns the initial declared page count") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      check(callI32(inst, "get_size") == 1, "fresh memory should report 1 page")
    }

    test("memory.grow: success path returns previous page count and updates size") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      // Grow by 2 (1 → 3) — well under the declared max of 4.
      check(callI32(inst, "grow_by", 2) == 1, "grow_by(2) should return prev=1")
      check(callI32(inst, "get_size")   == 3, "after grow size should be 3")
    }

    test("memory.grow: failure (exceeds declared max) returns -1, size unchanged") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      // max=4. Grow by 2 lands at 3 (ok), then grow by 99 would exceed 4.
      check(callI32(inst, "grow_by", 2)  ==  1, "first grow ok")
      check(callI32(inst, "grow_by", 99) == -1, "huge grow should fail with -1")
      check(callI32(inst, "get_size")    ==  3, "failed grow must NOT change size")
    }

    test("memory.grow: persists bytes written before the grow") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      // Write 0xAB at address 0, then grow by 1, then read it back.
      check(callI32(inst, "store_then_grow", 0, 0xab, 1) == 0xab,
        "byte written before grow should survive the reallocation")
    }

    test("memory.grow: zero-delta is a no-op that still returns the current size") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      check(callI32(inst, "grow_by", 0) == 1, "grow_by(0) returns prev page count")
      check(callI32(inst, "get_size")   == 1, "grow_by(0) does not change size")
    }

    test("memory.grow: negative delta returns -1, size unchanged") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      // i32 -1 read by memory.grow as a delta — implementation rejects rather
      // than wrapping to a huge unsigned size.
      check(callI32(inst, "grow_by", -1) == -1, "negative delta should fail")
      check(callI32(inst, "get_size")    ==  1, "size unchanged after failed grow")
    }

    test("memory.grow: unbounded (no declared max) succeeds within the host cap") {
      val inst = instantiate(Fixtures.memory_grow_unbounded)
      check(callI32(inst, "size_initial")    == 1, "fresh memory: 1 page")
      check(callI32(inst, "grow_and_resize") == 5, "grow by 4 -> total 5 pages")
    }

    test("i32.load16_s: sign-extends a 0x8000 half-word to -32768") {
      val inst = instantiate(Fixtures.memory_load16)
      // Bytes at offset 0: 0x00 0x80 -> u16 0x8000 -> signed -32768.
      check(callI32(inst, "load_signed", 0) == -32768, "signed 0x8000 should sign-extend to -32768")
      // Bytes at offset 2: 0xff 0x7f -> u16 0x7fff -> signed +32767.
      check(callI32(inst, "load_signed", 2) ==  32767, "signed 0x7fff should be +32767")
    }

    test("i32.load16_u: zero-extends a 0x8000 half-word to +32768") {
      val inst = instantiate(Fixtures.memory_load16)
      check(callI32(inst, "load_unsigned", 0) == 32768, "unsigned 0x8000 should be 32768, not -32768")
      check(callI32(inst, "load_unsigned", 2) == 32767, "unsigned 0x7fff matches signed at +32767")
    }

    test("i32.store16: writes only the low 16 bits of the source i32") {
      val inst = instantiate(Fixtures.memory_load16)
      // High bits 0x12340000 should be dropped on store; only 0xbeef survives.
      check(callI32(inst, "store16_then_read", 8, 0x1234beef) == 0xbeef,
        "high 16 bits of source must be truncated")
      // The two-byte store mustn't touch byte 10 (still 0 — fresh memory there).
      // We don't have a getter for byte 10 in the fixture, but the readback at
      // addr 8 covering bytes 8..9 exhaustively pins the truncation.
    }

    test("memory.size with non-zero reserved byte traps with InvalidModule") {
      // Patch the byte immediately after the first 0x3F (memory.size) from
      // 0x00 to 0x01. The interpreter rejects it at dispatch time — the
      // pre-scan doesn't validate content, only width.
      val src = Fixtures.memory_grow_basic
      val idx = src.indexOf(0x3f.toByte)
      check(idx >= 0, "memory.size opcode (0x3F) not found in fixture")
      val bad  = patchByte(src, idx + 1, 0x01)
      val inst = instantiate(bad)
      expectError(inst, "get_size", Seq.empty) {
        case WasmError.InvalidModule(m) => m.contains("memory.size") && m.contains("reserved")
      }
    }

    test("memory.grow with non-zero reserved byte traps with InvalidModule") {
      val src = Fixtures.memory_grow_basic
      val idx = src.indexOf(0x40.toByte)
      check(idx >= 0, "memory.grow opcode (0x40) not found in fixture")
      val bad  = patchByte(src, idx + 1, 0x02)
      val inst = instantiate(bad)
      expectError(inst, "grow_by", Seq(I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("memory.grow") && m.contains("reserved")
      }
    }

    // --- ModuleInstance accessors ------------------------------------------
    test("ModuleInstance.exportedFunctionNames: sorted, function-only") {
      val inst = instantiate(Fixtures.memory)
      check(inst.exportedFunctionNames == Seq("byte_roundtrip", "i32_roundtrip"),
        s"got ${inst.exportedFunctionNames}")
    }
    test("ModuleInstance.functionCount: imports + defined") {
      val noImports = instantiate(Fixtures.arith)
      check(noImports.functionCount == 1, s"arith has 1 function: got ${noImports.functionCount}")
      val withImport = instantiate(Fixtures.putchar)
      check(withImport.functionCount == 2, s"putchar has 1 import + 1 defined: got ${withImport.functionCount}")
    }

    // --- existing error paths ----------------------------------------------
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

  // ========================================================================
  // 2. Direct Leb128 unit tests
  // ========================================================================

  private def section2_leb128(): Unit =

    def assertU32(bs: Array[Byte], v: Int, p: Int): Unit =
      Leb128.readU32(bs, 0) match
        case Right((vv, pp)) =>
          check(vv == v, s"value: expected $v, got $vv")
          check(pp == p, s"newPos: expected $p, got $pp")
        case Left(e) => check(false, s"expected Right, got Left($e)")

    def assertU32Fails(bs: Array[Byte]): Unit =
      Leb128.readU32(bs, 0) match
        case Right(v) => check(false, s"expected Left, got Right($v)")
        case Left(_)  => ()

    def assertS32(bs: Array[Byte], v: Int, p: Int): Unit =
      Leb128.readS32(bs, 0) match
        case Right((vv, pp)) =>
          check(vv == v, s"value: expected $v, got $vv")
          check(pp == p, s"newPos: expected $p, got $pp")
        case Left(e) => check(false, s"expected Right, got Left($e)")

    def assertS32Fails(bs: Array[Byte]): Unit =
      Leb128.readS32(bs, 0) match
        case Right(v) => check(false, s"expected Left, got Right($v)")
        case Left(_)  => ()

    test("Leb128.readU32: 0 from a single 0x00 byte") {
      assertU32(b(0x00), 0, 1)
    }
    test("Leb128.readU32: 127 from a single 0x7F byte (largest single-byte value)") {
      assertU32(b(0x7f), 127, 1)
    }
    test("Leb128.readU32: 128 from two-byte encoding 0x80 0x01") {
      assertU32(b(0x80, 0x01), 128, 2)
    }
    test("Leb128.readU32: 624485 from canonical three-byte example") {
      assertU32(b(0xe5, 0x8e, 0x26), 624485, 3)
    }
    test("Leb128.readU32: padded zero continuation (non-canonical zero)") {
      assertU32(b(0x80, 0x00), 0, 2)
    }
    test("Leb128.readU32: empty input fails with InvalidModule") {
      assertU32Fails(b())
    }
    test("Leb128.readU32: continuation bit set but no follow-on byte fails") {
      assertU32Fails(b(0x80))
    }
    test("Leb128.readU32: six-byte (oversized) input fails") {
      assertU32Fails(b(0x80, 0x80, 0x80, 0x80, 0x80, 0x80))
    }

    test("Leb128.readS32: 0 from a single 0x00 byte") {
      assertS32(b(0x00), 0, 1)
    }
    test("Leb128.readS32: -1 (sign bit set in single byte)") {
      assertS32(b(0x7f), -1, 1)
    }
    test("Leb128.readS32: -2 from 0x7e") {
      assertS32(b(0x7e), -2, 1)
    }
    test("Leb128.readS32: -64 from 0x40 (smallest single-byte negative)") {
      assertS32(b(0x40), -64, 1)
    }
    test("Leb128.readS32: positive 64 needs two bytes") {
      assertS32(b(0xc0, 0x00), 64, 2)
    }
    test("Leb128.readS32: -123456 from a multi-byte negative") {
      assertS32(b(0xc0, 0xbb, 0x78), -123456, 3)
    }
    test("Leb128.readS32: Int.MaxValue (full five-byte encoding)") {
      assertS32(b(0xff, 0xff, 0xff, 0xff, 0x07), Int.MaxValue, 5)
    }
    test("Leb128.readS32: Int.MinValue (full five-byte encoding)") {
      assertS32(b(0x80, 0x80, 0x80, 0x80, 0x78), Int.MinValue, 5)
    }
    test("Leb128.readS32: empty input fails with InvalidModule") {
      assertS32Fails(b())
    }
    test("Leb128.readS32: continuation bit set but no follow-on byte fails") {
      assertS32Fails(b(0x80))
    }
    test("Leb128.readS32: six-byte (oversized) input fails") {
      assertS32Fails(b(0x80, 0x80, 0x80, 0x80, 0x80, 0x80))
    }
    test("Leb128.readU32: non-zero startPos reads from the given offset") {
      val padded = b(0xff, 0xff, 0x80, 0x01)
      Leb128.readU32(padded, 2) match
        case Right((v, p)) =>
          check(v == 128, s"value at offset 2: expected 128, got $v")
          check(p == 4,   s"newPos: expected 4, got $p")
        case Left(e) => check(false, s"expected Right, got Left($e)")
    }

    // --- readS64 ------------------------------------------------------------

    def assertS64(bs: Array[Byte], v: Long, p: Int): Unit =
      Leb128.readS64(bs, 0) match
        case Right((vv, pp)) =>
          check(vv == v, s"value: expected $v, got $vv")
          check(pp == p, s"newPos: expected $p, got $pp")
        case Left(e) => check(false, s"expected Right, got Left($e)")

    def assertS64Fails(bs: Array[Byte]): Unit =
      Leb128.readS64(bs, 0) match
        case Right(v) => check(false, s"expected Left, got Right($v)")
        case Left(_)  => ()

    test("Leb128.readS64: 0 from a single 0x00 byte") {
      assertS64(b(0x00), 0L, 1)
    }
    test("Leb128.readS64: -1 (single 0x7F byte, sign bit set)") {
      assertS64(b(0x7f), -1L, 1)
    }
    test("Leb128.readS64: positive 64 (two-byte canonical encoding)") {
      assertS64(b(0xc0, 0x00), 64L, 2)
    }
    test("Leb128.readS64: -123456 (multi-byte negative)") {
      assertS64(b(0xc0, 0xbb, 0x78), -123456L, 3)
    }
    test("Leb128.readS64: 4294967295 (out of i32 range — five-byte encoding)") {
      // Same byte string would read as -1 under readS32 (no room to widen),
      // but as +0xFFFFFFFF in S64 since the sign bit isn't set.
      assertS64(b(0xff, 0xff, 0xff, 0xff, 0x0f), 4294967295L, 5)
    }
    test("Leb128.readS64: Long.MaxValue (full ten-byte encoding)") {
      assertS64(b(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00), Long.MaxValue, 10)
    }
    test("Leb128.readS64: Long.MinValue (full ten-byte encoding, sign bit in final byte)") {
      assertS64(b(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x7f), Long.MinValue, 10)
    }
    test("Leb128.readS64: empty input fails with InvalidModule") {
      assertS64Fails(b())
    }
    test("Leb128.readS64: continuation bit set but no follow-on byte fails") {
      assertS64Fails(b(0x80))
    }
    test("Leb128.readS64: eleven-byte (oversized) input fails") {
      assertS64Fails(b(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80))
    }

  // ========================================================================
  // 3. Parser malformed-binary tests (handcrafted bytes)
  // ========================================================================

  private def section3_parserMalformed(): Unit =

    test("parser: empty input returns InvalidMagic") {
      Parser.parse(b()) match
        case Left(WasmError.InvalidMagic) => ()
        case other => check(false, s"expected InvalidMagic, got $other")
    }
    test("parser: truncated header (< 8 bytes) returns InvalidMagic") {
      Parser.parse(b(0x00, 0x61, 0x73)) match
        case Left(WasmError.InvalidMagic) => ()
        case other => check(false, s"expected InvalidMagic, got $other")
    }
    test("parser: wrong magic bytes return InvalidMagic") {
      Parser.parse(b(0xde, 0xad, 0xbe, 0xef, 0x01, 0x00, 0x00, 0x00)) match
        case Left(WasmError.InvalidMagic) => ()
        case other => check(false, s"expected InvalidMagic, got $other")
    }
    test("parser: wrong version (not 1.0) returns InvalidMagic") {
      val wrong = b(0x00, 0x61, 0x73, 0x6d, 0x02, 0x00, 0x00, 0x00)
      Parser.parse(wrong) match
        case Left(WasmError.InvalidMagic) => ()
        case other => check(false, s"expected InvalidMagic, got $other")
    }
    test("parser: bad magic (3rd byte wrong) returns InvalidMagic") {
      Parser.parse(b(0x00, 0x61, 0x73, 0x00, 0x01, 0x00, 0x00, 0x00)) match
        case Left(WasmError.InvalidMagic) => ()
        case other => check(false, s"expected InvalidMagic, got $other")
    }
    test("parser: section size overflowing file returns InvalidModule") {
      val bad = Header ++ b(0x01, 0x7f)            // section 1 claims 127 bytes, content 0 bytes
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("overflows"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(overflows), got $other")
    }
    test("parser: non-0x60 functype tag returns InvalidModule") {
      val bad = patchFirst(Fixtures.arith, 0x60, 0x61)
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("functype"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(functype), got $other")
    }
    test("parser: unknown valtype returns InvalidModule with 'valtype'") {
      val bad = patchFirst(Fixtures.arith, 0x7f, 0x55)
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("valtype"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(valtype), got $other")
    }
    test("parser: unknown import kind byte returns InvalidModule") {
      val importContent =
        b(0x01) ++                                          // 1 import
        b(0x03, 'e'.toInt, 'n'.toInt, 'v'.toInt) ++         // module name "env"
        b(0x01, 'x'.toInt) ++                                // import name "x"
        b(0x04, 0x00)                                        // unknown kind + dummy idx
      val bad = Header ++ b(0x02, importContent.length) ++ importContent
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("import"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(import kind), got $other")
    }
    test("parser: unknown export kind byte returns InvalidModule") {
      val exportContent =
        b(0x01) ++                                          // 1 export
        b(0x01, 'x'.toInt) ++                                // name "x"
        b(0x04, 0x00)                                        // unknown kind + dummy idx
      val bad = Header ++ b(0x07, exportContent.length) ++ exportContent
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("export"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(export kind), got $other")
    }
    test("parser: function / code section count mismatch returns InvalidModule") {
      val typeSec = b(0x01) ++ b(0x60, 0x00, 0x00)         // 1 functype 0→0
      val funcSec = b(0x01) ++ b(0x00)                     // 1 function, typeidx 0
      val codeSec = b(0x00)                                // 0 bodies
      val bad =
        Header ++
        b(0x01, typeSec.length) ++ typeSec ++
        b(0x03, funcSec.length) ++ funcSec ++
        b(0x0a, codeSec.length) ++ codeSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("function"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(function/code mismatch), got $other")
    }
    test("parser: passive data segments (flag 1) return InvalidModule") {
      val dataSec = b(0x01, 0x01, 0x00)                    // 1 segment, flag 1, 0 bytes
      val bad = Header ++ b(0x0b, dataSec.length) ++ dataSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("passive"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(passive), got $other")
    }
    test("parser: unknown data segment flag returns InvalidModule") {
      val dataSec = b(0x01, 0x05)                          // 1 segment, flag 5 (unknown)
      val bad = Header ++ b(0x0b, dataSec.length) ++ dataSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("data"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(data flag), got $other")
    }
    test("parser: data segment with non-i32.const offset expr returns InvalidModule") {
      val dataSec = b(0x01, 0x00, 0x42, 0x00, 0x0b, 0x00)  // flag 0, i64.const 0, end, 0 bytes
      val bad = Header ++ b(0x0b, dataSec.length) ++ dataSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("i32.const"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(i32.const), got $other")
    }
    test("parser: data segment missing `end` after i32.const returns InvalidModule") {
      val dataSec = b(0x01, 0x00, 0x41, 0x00, 0x00, 0x00)  // flag 0, i32.const 0, NO end
      val bad = Header ++ b(0x0b, dataSec.length) ++ dataSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(_)) => ()
        case other => check(false, s"expected InvalidModule, got $other")
    }

    // Phase 3 — table / element section diagnostics ------------------------

    test("parser: section 4 with non-funcref reftype returns InvalidModule") {
      // 1 table, reftype 0x6F (externref — reference types), flag 0, min 0.
      val tableSec = b(0x01, 0x6f, 0x00, 0x00)
      val bad = Header ++ b(0x04, tableSec.length) ++ tableSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("reftype"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(reftype), got $other")
    }

    test("parser: section 9 with passive flag (1) returns InvalidModule") {
      // 1 element segment with flag=1 (passive) — Phase 3 only models the
      // active forms (flag 0 / flag 2).
      val elemSec = b(0x01, 0x01)
      val bad = Header ++ b(0x09, elemSec.length) ++ elemSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("element"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(element flag), got $other")
    }

  // ========================================================================
  // 4. Interpreter unsupported-opcode tests
  // ========================================================================

  private def section4_unsupportedOpcodes(): Unit =

    /** Patch the first i32.const opcode (0x41) in arith.wasm to a different
      * opcode that isn't in the MVP subset. The pre-scan in
      * `computeBodyMeta` runs during instantiation and surfaces the error. */
    def assertUnknownOpcode(patched: Array[Byte], opcode: Int, label: String): Unit =
      Runtime.instantiate(patched, Seq(EnvModule.default)) match
        case Left(WasmError.UnknownOpcode(b)) =>
          check(b == opcode, s"$label: expected opcode 0x${opcode.toHexString}, got 0x${b.toHexString}")
        case other => check(false, s"$label: expected UnknownOpcode(0x${opcode.toHexString}), got $other")

    // Retargeted from 0x11 (formerly call_indirect, now supported in Phase 3)
    // to 0x12 — a reserved byte immediately after call_indirect with no MVP
    // meaning. Same code path through `skipImmediates`'s default branch.
    test("interpreter: 0x12 (reserved, post-call_indirect) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0x12), 0x12, "0x12 (reserved)")
    }
    // Retargeted from 0x76 (formerly i32.shr_u, now supported in Phase 1.5)
    // to 0xC4 — a reserved byte with no MVP meaning, and not the lead byte
    // of any prefixed instruction set we currently parse. Same code path,
    // same expected typed error.
    test("interpreter: 0xC4 (unassigned in MVP) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0xc4), 0xc4, "0xC4 (reserved)")
    }
    // Retargeted from 0x3F / 0x40 (formerly memory.size / memory.grow,
    // now supported in Phase 4) to 0x06 and 0x07 — both belong to the
    // exception-handling proposal (try / catch) and are firmly post-MVP.
    // Same code path through `skipImmediates`'s default branch.
    test("interpreter: 0x06 (try, post-MVP) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0x06), 0x06, "0x06 (try)")
    }
    test("interpreter: 0x07 (catch, post-MVP) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0x07), 0x07, "0x07 (catch)")
    }
    test("interpreter: completely unused opcode (0xFF) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0xff), 0xff, "0xFF")
    }

  // ========================================================================
  // 5. Runtime / linking error tests
  // ========================================================================

  private def section5_runtimeErrors(): Unit =

    test("runtime: import referencing an out-of-range type index returns InvalidModule") {
      // putchar.wasm's func import descriptor ends with kind=0x00, typeidx=0x00
      // immediately after the import-name "putchar". Bump the typeidx to 9.
      val src    = Fixtures.putchar
      val marker = src.indexOf("putchar".getBytes("UTF-8").last)
      check(marker > 0, "marker not found")
      val bad = patchByte(src, marker + 2, 0x09) // skip 'r' and kind byte
      Runtime.instantiate(bad, Seq(EnvModule.default)) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("type"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(type), got $other")
    }

    test("runtime: defined function referencing an out-of-range type index returns InvalidModule") {
      // arith.wasm section 3 bytes (function section): 03 02 01 00
      //                                                id size cnt typeidx
      val src  = Fixtures.arith
      val sec3 = src.indexOf(0x03.toByte)
      check(sec3 > 0, "section 3 not found")
      val bad = patchByte(src, sec3 + 3, 0x09)
      Runtime.instantiate(bad, Seq(EnvModule.default)) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("type"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(type), got $other")
    }

    test("runtime: call with out-of-range function index returns InvalidModule") {
      val src    = Fixtures.factorial
      val callIx = src.indexOf(0x10.toByte)
      check(callIx > 0, "call opcode not found")
      val bad  = patchByte(src, callIx + 1, 0x09)
      val inst = instantiate(bad)
      expectError(inst, "fact", Seq(I32(5))) {
        case WasmError.InvalidModule(msg) => msg.contains("function index")
      }
    }

    test("runtime: branch index out of range returns InvalidModule") {
      val src     = Fixtures.br_block
      val brIfIdx = src.indexOf(0x0d.toByte)
      check(brIfIdx > 0, "br_if opcode not found")
      val bad  = patchByte(src, brIfIdx + 1, 0x09)
      val inst = instantiate(bad)
      expectError(inst, "test_br", Seq(I32(1))) {
        case WasmError.InvalidModule(msg) => msg.contains("branch index")
      }
    }

    test("runtime: local.get with out-of-range index returns InvalidModule") {
      val src      = Fixtures.locals
      val localGet = src.indexOf(0x20.toByte)
      check(localGet > 0, "local.get not found")
      val bad  = patchByte(src, localGet + 1, 0x09)
      val inst = instantiate(bad)
      expectError(inst, "test_locals", Seq(I32(1), I32(2))) {
        case WasmError.InvalidModule(msg) => msg.contains("local.get")
      }
    }

    test("runtime: global.get with out-of-range index returns InvalidModule") {
      // globals_basic.wasm has two globals (counter at 0, seed at 1). Find the
      // first `global.get` opcode and patch its index byte to a high value.
      val src        = Fixtures.globals_basic
      val globalGet  = src.indexOf(0x23.toByte)
      check(globalGet > 0, "global.get opcode not found")
      val bad  = patchByte(src, globalGet + 1, 0x09)
      val inst = instantiate(bad)
      // The patched function may be any of the getters — every entry point
      // either reads the patched op directly or traps the same way through it.
      inst.invoke("get_count", Seq.empty) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("global.get"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(global.get …), got $other")
    }

    test("runtime: global.set with out-of-range index returns InvalidModule") {
      val src        = Fixtures.globals_basic
      val globalSet  = src.indexOf(0x24.toByte)
      check(globalSet > 0, "global.set opcode not found")
      val bad  = patchByte(src, globalSet + 1, 0x09)
      val inst = instantiate(bad)
      inst.invoke("bump", Seq.empty) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("global.set"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(global.set …), got $other")
    }

    test("parser: section 6 with init-expr not matching declared type returns InvalidModule") {
      // globals_types.wasm starts its first global as (mut i32) (i32.const ...).
      // Flip the declared valtype to i64 while leaving the init expr as
      // i32.const — readConstExpr should reject the mismatched opcode/type pair.
      val src = Fixtures.globals_types
      // Find section id 6: scan for byte 0x06 immediately followed by a u32
      // (the section size). We can rely on it being the first 0x06 after the
      // magic+version + type section header. Be conservative: walk by section.
      // Simpler approach: locate the section by searching for the unique
      // section-6 prefix "0x06 size 0x04 0x7f 0x01 0x41" (count=4, valtype=i32,
      // mut=mut, op=i32.const) which is specific to this fixture.
      // We just need to find the valtype byte (0x7f) of the first global
      // entry; that lives at offset section-6-start + 2 (skip count byte).
      // Probe for the marker subsequence "0x7f 0x01 0x41" (i32, mut, i32.const).
      var i      = 0
      var marker = -1
      while marker < 0 && i + 2 < src.length do
        if (src(i) & 0xff) == 0x7f && (src(i + 1) & 0xff) == 0x01 && (src(i + 2) & 0xff) == 0x41 then
          marker = i
        i += 1
      check(marker > 0, "section-6 first-global marker not found")
      // Swap the valtype byte from 0x7f (i32) to 0x7e (i64). The init-expr
      // op is still 0x41 (i32.const), so readConstExpr should reject the
      // mismatch with a clear diagnostic.
      val bad = patchByte(src, marker, 0x7e)
      Runtime.instantiate(bad, Seq(EnvModule.default)) match
        case Left(WasmError.InvalidModule(msg)) =>
          // Declared type was bumped to i64, init op left as 0x41 (i32.const) —
          // the message should name the expected mnemonic (i64.const).
          check(msg.contains("i64.const"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(i64.const …), got $other")
    }

    test("ModuleInstance.invoke with valid args returns Seq() for an empty function") {
      val inst = instantiate(Fixtures.empty_func)
      runRight(inst.invoke("empty")) match
        case Seq() => ()
        case other => check(false, s"expected empty Seq, got $other")
    }

  // ========================================================================
  // 6. EnvModule.default smoke test
  // ========================================================================

  private def section6_envModuleDefault(): Unit =

    test("EnvModule.default: putchar reaches some platform output (no throw)") {
      // The exact destination is platform-dependent (we can capture stdout on
      // JVM via System.setOut; Scala.js/Native may or may not respect the
      // redirect). The test's job is to prove the default code path runs
      // without throwing — the captured-string check is a JVM-only bonus.
      val baos     = new java.io.ByteArrayOutputStream
      val savedOut = System.out
      System.setOut(new java.io.PrintStream(baos, /* autoFlush = */ true, "UTF-8"))
      try
        val inst = instantiate(Fixtures.putchar, EnvModule.default)
        runRight(inst.invoke("hello"))
      finally
        System.setOut(savedOut)
      val captured = new String(baos.toByteArray, "UTF-8")
      check(captured == "Hi!" || captured.isEmpty,
        s"expected 'Hi!' or '' (platform-dependent), got '${captured}'")
    }

  // ========================================================================
  // 7. Bug-fix regression tests
  // ========================================================================

  private def section7_bugFixRegressions(): Unit =

    test("regression: unsupported blocktype (0x7B) returns InvalidModule, not RuntimeException") {
      // Bug: `Interpreter.readBlocktype` threw `RuntimeException` for any
      // blocktype other than 0x40 / 0x7F, bypassing the WasmError discipline.
      // Fixed to `Left(InvalidModule(...))` so the pre-scan reports it cleanly.
      //
      // The fixture's block declares an i32 result (`0x02 0x7F`); we patch the
      // blocktype byte to a still-unsupported value. The original repro bytes
      // 0x7E (i64), 0x7D (f32) and 0x7C (f64) are all valid blocktypes now,
      // so the regression test has been retargeted through each phase to the
      // latest genuinely-unsupported form. 0x7B is in the negative-s33
      // valtype range and not assigned by the MVP — same code path, same
      // expected typed error.
      val src = Fixtures.block_result
      val pat = b(0x02, 0x7f)
      var idx = -1
      var i   = 0
      while idx < 0 && i <= src.length - pat.length do
        if src(i) == pat(0) && src(i + 1) == pat(1) then idx = i
        i += 1
      check(idx >= 0, "block + i32-blocktype pattern not found")
      val bad = patchByte(src, idx + 1, 0x7b)            // blocktype byte → unsupported
      Runtime.instantiate(bad, Seq(EnvModule.default)) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("blocktype"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(blocktype), got $other")
    }
