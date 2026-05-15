package io.github.edadma.wasm

import java.lang as jl
import TestSupport.*

/** End-to-end numeric-opcode tests for every supported scalar type:
  * i32 baseline + remaining i32 ops (Phase 1.5), i64 (Phase 1.1), f32
  * (Phase 1.2), f64 (Phase 1.3), and conversions (Phase 1.4).
  *
  * Driven entirely off compiled `.wat` fixtures — no inline byte synthesis
  * here except the two polymorphic-`select` proofs at f32/f64 width.
  */
object NumericTests:

  def run(): Unit =
    i32Baseline()
    i32Remaining()
    i64Tests()
    f32Tests()
    f64Tests()
    conversionTests()
    signExtensionTests()

  // === i32 baseline =======================================================

  private def i32Baseline(): Unit =

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
      runOk(inst.invoke("hello"))
      check(collected.toString == "Hi!", s"got '${collected}'")
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

    // --- new fixtures (initial pass) ---------------------------------------
    test("loop with i32 result: natural fall-through carries the result") {
      val inst = instantiate(Fixtures.loop_result)
      check(callI32(inst, "loop_result") == 42, "wrong result")
    }
    test("block with i32 result: natural fall-through carries the result") {
      val inst = instantiate(Fixtures.block_result)
      check(callI32(inst, "block_result") == 17, "wrong result")
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

  // === Phase 1.5: remaining i32 ops =======================================

  private def i32Remaining(): Unit =

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

  // === i64 (Phase 1.1) ====================================================

  private def i64Tests(): Unit =

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

  // === f32 (Phase 1.2) ====================================================
  //
  // The interpreter handles f32.const as 4 raw LE bytes (no LEB), runs the
  // ordered compares per IEEE-754 (NaN makes <, <=, >, >=, == false; only
  // != stays true), and delegates min/max/sqrt/floor/ceil/rint/copySign to
  // java.lang.Math which already follows the spec. These tests pin the
  // wiring end-to-end on every backend so platform-specific NaN handling
  // gets caught early.

  private def f32Tests(): Unit =

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
      // The `select` opcode (0x1B) is polymorphic across the four scalar
      // types; this verifies it works at f32 width too, not just i32. We
      // don't need a dedicated
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

  // === f64 (Phase 1.3) ====================================================
  //
  // Same shape as f32, just at Double width: const is 8 raw LE bytes,
  // load/store use the raw-bits conversion so NaN payloads survive, all
  // ordered compares already match WASM via Scala's primitive operators,
  // and `jl.Math.{abs, floor, ceil, rint, sqrt, copySign, min, max}` are
  // already IEEE-754 conforming on `double`. With f64 wired up, every
  // scalar valtype the binary format defines is now supported.

  private def f64Tests(): Unit =

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

  // === Conversions (Phase 1.4) ============================================

  private def conversionTests(): Unit =

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

    // === Phase 8.A: non-trapping (saturating) float→int conversions =======
    //
    // Same input domains as the trapping versions (0xA8..0xAB, 0xAE..0xB1),
    // but instead of raising `InvalidModule("... NaN")` /
    // `InvalidModule("out of range")` they clamp:
    //   NaN              → 0
    //   v < MIN          → MIN  (signed) / 0   (unsigned)
    //   v > MAX          → MAX
    //   in-range         → truncate toward zero (same as the trapping form)
    // One export per sub-opcode in `trunc_sat.wat`; each test pins the
    // four saturation paths plus an in-range positive truncation.

    test("conv: i32.trunc_sat_f32_s — saturates instead of trapping") {
      val inst = instantiate(Fixtures.trunc_sat)
      check(callI32V(inst, "i32_trunc_sat_f32_s", F32(Float.NaN))               == 0,            "NaN -> 0")
      check(callI32V(inst, "i32_trunc_sat_f32_s", F32(Float.PositiveInfinity))  == Int.MaxValue, "+Inf -> MAX")
      check(callI32V(inst, "i32_trunc_sat_f32_s", F32(Float.NegativeInfinity))  == Int.MinValue, "-Inf -> MIN")
      check(callI32V(inst, "i32_trunc_sat_f32_s", F32( 2147483648.0f))          == Int.MaxValue, "exactly 2^31 -> MAX")
      check(callI32V(inst, "i32_trunc_sat_f32_s", F32(-2147483904.0f))          == Int.MinValue, "below MIN -> MIN")
      check(callI32V(inst, "i32_trunc_sat_f32_s", F32(-1.9f))                   == -1,           "-1.9 truncates to -1")
      check(callI32V(inst, "i32_trunc_sat_f32_s", F32( 1.9f))                   ==  1,           " 1.9 truncates to  1")
    }

    test("conv: i32.trunc_sat_f32_u — saturates to [0, 2^32)") {
      val inst = instantiate(Fixtures.trunc_sat)
      check(callI32V(inst, "i32_trunc_sat_f32_u", F32(Float.NaN))               ==  0, "NaN -> 0")
      check(callI32V(inst, "i32_trunc_sat_f32_u", F32(Float.NegativeInfinity))  ==  0, "-Inf -> 0")
      check(callI32V(inst, "i32_trunc_sat_f32_u", F32(-1.0f))                   ==  0, "-1.0 -> 0 (boundary)")
      check(callI32V(inst, "i32_trunc_sat_f32_u", F32(-0.5f))                   ==  0, "-0.5 -> 0 (in range, trunc to 0)")
      check(callI32V(inst, "i32_trunc_sat_f32_u", F32(Float.PositiveInfinity))  == -1, "+Inf -> 0xFFFFFFFF")
      check(callI32V(inst, "i32_trunc_sat_f32_u", F32(4294967296.0f))           == -1, "exactly 2^32 -> 0xFFFFFFFF")
      check(callI32V(inst, "i32_trunc_sat_f32_u", F32(2147483648.0f))           == Int.MinValue, "2^31 -> bit pattern 0x80000000")
    }

    test("conv: i32.trunc_sat_f64_s — same shape, f64 source") {
      val inst = instantiate(Fixtures.trunc_sat)
      check(callI32V(inst, "i32_trunc_sat_f64_s", F64(Double.NaN))              == 0,            "NaN -> 0")
      check(callI32V(inst, "i32_trunc_sat_f64_s", F64(Double.PositiveInfinity)) == Int.MaxValue, "+Inf -> MAX")
      check(callI32V(inst, "i32_trunc_sat_f64_s", F64(Double.NegativeInfinity)) == Int.MinValue, "-Inf -> MIN")
      check(callI32V(inst, "i32_trunc_sat_f64_s", F64( 2147483648.0))           == Int.MaxValue, "exactly 2^31 -> MAX")
      check(callI32V(inst, "i32_trunc_sat_f64_s", F64(-2147483649.0))           == Int.MinValue, "below MIN -> MIN")
      check(callI32V(inst, "i32_trunc_sat_f64_s", F64( 12345.678))              == 12345,        "in-range truncates")
    }

    test("conv: i32.trunc_sat_f64_u — same shape, f64 source") {
      val inst = instantiate(Fixtures.trunc_sat)
      check(callI32V(inst, "i32_trunc_sat_f64_u", F64(Double.NaN))              ==  0, "NaN -> 0")
      check(callI32V(inst, "i32_trunc_sat_f64_u", F64(-1.0))                    ==  0, "-1.0 -> 0 (boundary)")
      check(callI32V(inst, "i32_trunc_sat_f64_u", F64(Double.PositiveInfinity)) == -1, "+Inf -> 0xFFFFFFFF")
      check(callI32V(inst, "i32_trunc_sat_f64_u", F64( 4294967296.0))           == -1, "exactly 2^32 -> 0xFFFFFFFF")
      check(callI32V(inst, "i32_trunc_sat_f64_u", F64( 4294967295.0))           == -1, "2^32-1 fits as 0xFFFFFFFF")
      check(callI32V(inst, "i32_trunc_sat_f64_u", F64(-0.5))                    ==  0, "-0.5 -> 0 (in range)")
    }

    test("conv: i64.trunc_sat_f32_s — i64 saturation, f32 source") {
      val inst = instantiate(Fixtures.trunc_sat)
      check(callI64(inst, "i64_trunc_sat_f32_s", F32(Float.NaN))                == 0L,            "NaN -> 0")
      check(callI64(inst, "i64_trunc_sat_f32_s", F32(Float.PositiveInfinity))   == Long.MaxValue, "+Inf -> MAX")
      check(callI64(inst, "i64_trunc_sat_f32_s", F32(Float.NegativeInfinity))   == Long.MinValue, "-Inf -> MIN")
      check(callI64(inst, "i64_trunc_sat_f32_s", F32( 9223372036854775808.0f))  == Long.MaxValue, "exactly 2^63 -> MAX")
      check(callI64(inst, "i64_trunc_sat_f32_s", F32(-9223372036854775808.0f))  == Long.MinValue, "exactly -2^63 -> MIN exact")
      check(callI64(inst, "i64_trunc_sat_f32_s", F32( 100.5f))                  == 100L,          "100.5 -> 100")
    }

    test("conv: i64.trunc_sat_f32_u — i64 saturation [0, 2^64), f32 source") {
      val inst = instantiate(Fixtures.trunc_sat)
      check(callI64(inst, "i64_trunc_sat_f32_u", F32(Float.NaN))                ==  0L, "NaN -> 0")
      check(callI64(inst, "i64_trunc_sat_f32_u", F32(-1.0f))                    ==  0L, "-1.0 -> 0 (boundary)")
      check(callI64(inst, "i64_trunc_sat_f32_u", F32(Float.PositiveInfinity))   == -1L, "+Inf -> UINT64_MAX")
      check(callI64(inst, "i64_trunc_sat_f32_u", F32(18446744073709551616.0f))  == -1L, "exactly 2^64 -> UINT64_MAX")
      // 1.0e18 sits comfortably in [0, 2^63) — the bit-splice path is not used.
      check(jl.Long.compareUnsigned(callI64(inst, "i64_trunc_sat_f32_u", F32(1.0e18f)), 0L) > 0,
            "1e18 -> positive unsigned i64")
      // 1.0e19 > 2^63, so the bit-splice trick activates. Bit pattern must be high-bit-set.
      check(callI64(inst, "i64_trunc_sat_f32_u", F32(1.0e19f)) < 0L,
            "1e19 -> high-bit-set i64 (unsigned > 2^63)")
    }

    test("conv: i64.trunc_sat_f64_s — i64 saturation, f64 source") {
      val inst = instantiate(Fixtures.trunc_sat)
      check(callI64(inst, "i64_trunc_sat_f64_s", F64(Double.NaN))               == 0L,            "NaN -> 0")
      check(callI64(inst, "i64_trunc_sat_f64_s", F64(Double.PositiveInfinity))  == Long.MaxValue, "+Inf -> MAX")
      check(callI64(inst, "i64_trunc_sat_f64_s", F64(Double.NegativeInfinity))  == Long.MinValue, "-Inf -> MIN")
      check(callI64(inst, "i64_trunc_sat_f64_s", F64( 9223372036854775808.0))   == Long.MaxValue, "exactly 2^63 -> MAX")
      check(callI64(inst, "i64_trunc_sat_f64_s", F64(Long.MinValue.toDouble))   == Long.MinValue, "Long.MIN exact")
      check(callI64(inst, "i64_trunc_sat_f64_s", F64(-1.5))                     == -1L,           "-1.5 -> -1 (toward 0)")
    }

    test("conv: i64.trunc_sat_f64_u — i64 saturation [0, 2^64), f64 source") {
      val inst = instantiate(Fixtures.trunc_sat)
      check(callI64(inst, "i64_trunc_sat_f64_u", F64(Double.NaN))               ==  0L, "NaN -> 0")
      check(callI64(inst, "i64_trunc_sat_f64_u", F64(-1.0))                     ==  0L, "-1.0 -> 0 (boundary)")
      check(callI64(inst, "i64_trunc_sat_f64_u", F64(Double.PositiveInfinity))  == -1L, "+Inf -> UINT64_MAX")
      check(callI64(inst, "i64_trunc_sat_f64_u", F64(18446744073709551616.0))   == -1L, "exactly 2^64 -> UINT64_MAX")
      // 1.8e19 is < 2^64 but > 2^63 — bit-splice trick is exercised.
      val r = callI64(inst, "i64_trunc_sat_f64_u", F64(1.8e19))
      check(jl.Long.compareUnsigned(r, 0L) > 0,    "1.8e19 -> positive unsigned i64")
      check(jl.Long.compareUnsigned(r, -1L) <= 0,  "still <= UINT64_MAX")
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

  // === Phase 7.D: sign-extension proposal (0xC0–0xC4) =====================
  //
  // rustc emits `i32.extend8_s` for `as i8 as i32` and friends. The five
  // ops all reinterpret the low N bits of their operand as a signed N-bit
  // integer and sign-extend to the operand's full width. No traps.

  private def signExtensionTests(): Unit =

    test("i32.extend8_s: low 8 bits sign-extend into i32") {
      val inst = instantiate(Fixtures.sign_extend)
      check(callI32(inst, "i32_extend8_s", 0x00) ==  0,    "0 stays 0")
      check(callI32(inst, "i32_extend8_s", 0x7f) ==  127,  "max positive byte")
      check(callI32(inst, "i32_extend8_s", 0x80) == -128,  "min negative byte (high bit set)")
      check(callI32(inst, "i32_extend8_s", 0xff) == -1,    "0xff → -1")
      // High bits above the low byte are ignored — that's the whole point.
      check(callI32(inst, "i32_extend8_s", 0xdeadbe80.toInt) == -128, "high bits dropped")
    }

    test("i32.extend16_s: low 16 bits sign-extend into i32") {
      val inst = instantiate(Fixtures.sign_extend)
      check(callI32(inst, "i32_extend16_s", 0x0000) == 0,         "0 stays 0")
      check(callI32(inst, "i32_extend16_s", 0x7fff) == 32767,     "max positive short")
      check(callI32(inst, "i32_extend16_s", 0x8000) == -32768,    "min negative short")
      check(callI32(inst, "i32_extend16_s", 0xffff) == -1,        "0xffff → -1")
      check(callI32(inst, "i32_extend16_s", 0xdead8000.toInt) == -32768, "high bits dropped")
    }

    test("i64.extend8_s: low 8 bits sign-extend into i64") {
      val inst = instantiate(Fixtures.sign_extend)
      check(callI64(inst, "i64_extend8_s", I64(0x00L)) ==  0L,    "0 stays 0")
      check(callI64(inst, "i64_extend8_s", I64(0x7fL)) ==  127L,  "max positive byte")
      check(callI64(inst, "i64_extend8_s", I64(0x80L)) == -128L,  "min negative byte")
      check(callI64(inst, "i64_extend8_s", I64(0xffL)) == -1L,    "0xff → -1")
      check(callI64(inst, "i64_extend8_s", I64(0xdeadbeefcafe1234L)) == 0x34, "high bits dropped (0x34 is positive)")
    }

    test("i64.extend16_s: low 16 bits sign-extend into i64") {
      val inst = instantiate(Fixtures.sign_extend)
      check(callI64(inst, "i64_extend16_s", I64(0x0000L)) ==  0L,     "0 stays 0")
      check(callI64(inst, "i64_extend16_s", I64(0x7fffL)) ==  32767L, "max positive short")
      check(callI64(inst, "i64_extend16_s", I64(0x8000L)) == -32768L, "min negative short")
      check(callI64(inst, "i64_extend16_s", I64(0xffffL)) == -1L,     "0xffff → -1")
    }

    test("i64.extend32_s: low 32 bits sign-extend into i64") {
      val inst = instantiate(Fixtures.sign_extend)
      check(callI64(inst, "i64_extend32_s", I64(0x00000000L)) ==  0L,           "0 stays 0")
      check(callI64(inst, "i64_extend32_s", I64(0x7fffffffL)) ==  2147483647L,  "max positive i32")
      check(callI64(inst, "i64_extend32_s", I64(0x80000000L)) == -2147483648L,  "min negative i32")
      check(callI64(inst, "i64_extend32_s", I64(0xffffffffL)) == -1L,           "all-ones low 32 → -1")
      // High 32 bits get clobbered by the sign bit:
      check(callI64(inst, "i64_extend32_s", I64(0xdeadbeef80000000L)) == 0xffffffff80000000L,
            "high 32 bits replaced by sign extension of low 32")
    }
