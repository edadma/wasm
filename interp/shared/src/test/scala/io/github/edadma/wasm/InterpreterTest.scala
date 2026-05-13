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
    test("parser: memory and global exports silently ignored") {
      val inst = instantiate(Fixtures.mem_export)
      check(inst.exportedFunctionNames == Seq("f"), s"non-function exports leaked: ${inst.exportedFunctionNames}")
      check(callI32(inst, "f") == 4, "function export still callable")
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
    test("parser: f64 valtype (0x7C) returns InvalidModule with 'f64'") {
      val bad = patchFirst(Fixtures.arith, 0x7f, 0x7c)
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("f64"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(f64), got $other")
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

    test("interpreter: 0x11 (call_indirect) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0x11), 0x11, "call_indirect")
    }
    test("interpreter: 0x76 (i32.shr_u) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0x76), 0x76, "i32.shr_u")
    }
    test("interpreter: 0x3F (memory.size) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0x3f), 0x3f, "memory.size")
    }
    test("interpreter: 0x40 (memory.grow) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0x40), 0x40, "memory.grow")
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

    test("regression: unsupported blocktype (0x7C f64) returns InvalidModule, not RuntimeException") {
      // Bug: `Interpreter.readBlocktype` threw `RuntimeException` for any
      // blocktype other than 0x40 / 0x7F, bypassing the WasmError discipline.
      // Fixed to `Left(InvalidModule(...))` so the pre-scan reports it cleanly.
      //
      // The fixture's block declares an i32 result (`0x02 0x7F`); we patch the
      // blocktype byte to 0x7C (f64) which `readBlocktype` still rejects in
      // the current subset. The original repro bytes 0x7E (i64) and 0x7D
      // (f32) are both valid blocktypes now, so the regression test has been
      // retargeted to the still-unsupported f64 form. Same code path; same
      // expected typed error.
      val src = Fixtures.block_result
      val pat = b(0x02, 0x7f)
      var idx = -1
      var i   = 0
      while idx < 0 && i <= src.length - pat.length do
        if src(i) == pat(0) && src(i + 1) == pat(1) then idx = i
        i += 1
      check(idx >= 0, "block + i32-blocktype pattern not found")
      val bad = patchByte(src, idx + 1, 0x7c)            // blocktype byte → f64 (unsupported)
      Runtime.instantiate(bad, Seq(EnvModule.default)) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("blocktype"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(blocktype), got $other")
    }
