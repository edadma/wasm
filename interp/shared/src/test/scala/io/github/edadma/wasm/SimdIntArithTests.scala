package io.github.edadma.wasm

import TestSupport.*
import TestSupport.simd.{bytesEq, callV128, fromI8, fromI16, fromI32, fromI64}

/** Phase 8.E.D — SIMD integer arithmetic.
  *
  * Per-lane add/sub/neg/abs/mul and saturating add/sub + avgr_u across the
  * four integer shapes:
  *
  *   - i8x16: abs, neg, add, add_sat_s, add_sat_u, sub, sub_sat_s,
  *     sub_sat_u, avgr_u (no mul — the spec doesn't define i8x16.mul).
  *   - i16x8: abs, neg, add, add_sat_s, add_sat_u, sub, sub_sat_s,
  *     sub_sat_u, mul, avgr_u.
  *   - i32x4: abs, neg, add, sub, mul (no saturating variants past i16x8).
  *   - i64x2: abs, neg, add, sub, mul (no saturating + no avgr).
  *
  * Edge-case coverage:
  *   - `abs(MinValue) == MinValue` for every shape (signed-overflow wrap).
  *   - `neg(MinValue) == MinValue` likewise.
  *   - Non-saturating add/sub wrap mod 2^lane_width.
  *   - Saturating ops clamp at the signed / unsigned lane bounds.
  *   - `avgr_u` computes `(a + b + 1) / 2` per lane — note the +1, so
  *     200 / 201 rounds *up* to 201, not 200.
  */
object SimdIntArithTests:

  def run(): Unit =

    // === i8x16 ===========================================================

    test("i8x16.abs takes the lane-wise absolute value; abs(-128) wraps to -128") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI8(0, 1, -1, 2, -2, 127, -127, -128, 50, -50, 100, -100, 0, 0, 0, 0)
      val out  = callV128(inst, "abs_i8x16", V128(a))
      // abs(-128) overflows i8 → wraps back to -128. Every other lane is
      // the natural absolute value.
      val want = fromI8(0, 1,  1, 2,  2, 127,  127, -128, 50,  50, 100,  100, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.abs: ${out.mkString(",")}")
    }

    test("i8x16.neg flips the sign per lane; neg(-128) wraps to -128") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI8(0, 1, -1, 2, -2, 127, -127, -128, 50, -50, 100, -100, 0, 0, 0, 0)
      val out  = callV128(inst, "neg_i8x16", V128(a))
      val want = fromI8(0, -1, 1, -2, 2, -127, 127, -128, -50, 50, -100, 100, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.neg: ${out.mkString(",")}")
    }

    test("i8x16.add wraps mod 256 per lane") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI8(  0,   1,   2,  127, -128, -1, 100,  100, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8(  0,   2,   3,    1,   -1,  1,  50,  -50, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "add_i8x16", V128(a), V128(b))
      // lane 3: 127+1 = 128 → wraps to -128
      // lane 4: -128+-1 = -129 → wraps to 127
      // lane 5: -1+1 = 0
      // lane 6: 100+50 = 150 → wraps to -106 (signed-byte view)
      val want = fromI8(  0,   3,   5, -128,  127,  0, -106,   50, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.add: ${out.mkString(",")}")
    }

    test("i8x16.add_sat_s clamps to [-128, 127]") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI8( 100,  -100,    50,   100, -50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8(  50,   -50,    20,   -50,  50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "add_sat_s_i8x16", V128(a), V128(b))
      // lane 0: 100+50 = 150 → clamps to 127
      // lane 1: -100+-50 = -150 → clamps to -128
      // lane 2: 50+20 = 70 (no clamp)
      // lane 3: 100+-50 = 50 (no clamp)
      val want = fromI8( 127,  -128,    70,    50,   0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.add_sat_s: ${out.mkString(",")}")
    }

    test("i8x16.add_sat_u clamps to [0, 255] (unsigned)") {
      val inst = instantiate(Fixtures.simd_int_arith)
      // 0xC8 = 200 unsigned; 0x64 = 100; 0xFF = 255
      val a    = fromI8(0xc8, 0xff, 0x64,  10, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8(0x64, 0x01, 0x32,  20, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "add_sat_u_i8x16", V128(a), V128(b))
      // lane 0: 200+100 = 300 → 255
      // lane 1: 255+1 = 256 → 255
      // lane 2: 100+50 = 150 (no clamp; 150 as byte = 0x96 = -106)
      // lane 3: 10+20 = 30
      // lane 4: 1+2 = 3
      val want = fromI8(0xff, 0xff, 0x96,  30,    3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.add_sat_u: ${out.mkString(",")}")
    }

    test("i8x16.sub wraps mod 256 per lane") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI8(  0,   5, -128,  100, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
      val b    = fromI8(  1,   3,    1, -100, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
      val out  = callV128(inst, "sub_i8x16", V128(a), V128(b))
      // lane 0: 0-1 = -1
      // lane 1: 5-3 = 2
      // lane 2: -128-1 = -129 → wraps to 127
      // lane 3: 100-(-100) = 200 → wraps to -56
      val want = fromI8( -1,   2,  127,  -56, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.sub: ${out.mkString(",")}")
    }

    test("i8x16.sub_sat_s clamps to [-128, 127]") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI8(-100,   100,    0,   50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8(  50,  -100, -128,   20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "sub_sat_s_i8x16", V128(a), V128(b))
      // lane 0: -100-50 = -150 → -128
      // lane 1: 100-(-100) = 200 → 127
      // lane 2: 0-(-128) = 128 → 127
      // lane 3: 50-20 = 30 (no clamp)
      val want = fromI8(-128,   127,  127,   30, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.sub_sat_s: ${out.mkString(",")}")
    }

    test("i8x16.sub_sat_u clamps to 0 (unsigned)") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI8(0x32,  10, 0x00, 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8(0x64,  20, 0x01, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "sub_sat_u_i8x16", V128(a), V128(b))
      // lane 0: 50-100 = -50 → 0
      // lane 1: 10-20 = -10 → 0
      // lane 2: 0-1 = -1 → 0
      // lane 3: 255-1 = 254 = 0xFE
      val want = fromI8(   0,   0,    0, 0xfe, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.sub_sat_u: ${out.mkString(",")}")
    }

    test("i8x16.avgr_u computes (a+b+1)/2 unsigned per lane (rounds up)") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI8(200,  3,  0, 0xff,  100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8(201,  4,  0, 0xff,    1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "avgr_u_i8x16", V128(a), V128(b))
      // (200+201+1)/2 = 402/2 = 201
      // (3+4+1)/2 = 8/2 = 4
      // (0+0+1)/2 = 0 (integer div)
      // (255+255+1)/2 = 511/2 = 255
      // (100+1+1)/2 = 102/2 = 51
      val want = fromI8(201,  4,  0, 0xff,   51, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.avgr_u: ${out.mkString(",")}")
    }

    // === i16x8 ===========================================================

    test("i16x8.abs takes lane-wise abs; abs(-32768) wraps to -32768") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI16(0, 1, -1, 32767, -32767, -32768, 1000, -1000)
      val out  = callV128(inst, "abs_i16x8", V128(a))
      val want = fromI16(0, 1,  1, 32767,  32767, -32768, 1000,  1000)
      check(bytesEq(out, want), s"i16x8.abs: ${out.mkString(",")}")
    }

    test("i16x8.neg flips signs; neg(-32768) wraps to -32768") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI16(0, 1, -1, 32767, -32767, -32768, 5,    -5)
      val out  = callV128(inst, "neg_i16x8", V128(a))
      val want = fromI16(0, -1, 1, -32767, 32767, -32768, -5,    5)
      check(bytesEq(out, want), s"i16x8.neg: ${out.mkString(",")}")
    }

    test("i16x8.add wraps mod 65536 per lane") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI16(1, 32767, -32768,  100,    0, 0, 0, 0)
      val b    = fromI16(2,     1,     -1, -100,    0, 0, 0, 0)
      val out  = callV128(inst, "add_i16x8", V128(a), V128(b))
      // lane 1: 32767+1 = 32768 → wraps to -32768
      // lane 2: -32768+-1 = -32769 → wraps to 32767
      val want = fromI16(3, -32768, 32767,    0,    0, 0, 0, 0)
      check(bytesEq(out, want), s"i16x8.add: ${out.mkString(",")}")
    }

    test("i16x8.add_sat_s clamps to [-32768, 32767]") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI16( 30000, -30000,  100,    0,    0, 0, 0, 0)
      val b    = fromI16(  5000,  -5000,   50,    0,    0, 0, 0, 0)
      val out  = callV128(inst, "add_sat_s_i16x8", V128(a), V128(b))
      val want = fromI16( 32767, -32768,  150,    0,    0, 0, 0, 0)
      check(bytesEq(out, want), s"i16x8.add_sat_s: ${out.mkString(",")}")
    }

    test("i16x8.add_sat_u clamps to [0, 65535] unsigned") {
      val inst = instantiate(Fixtures.simd_int_arith)
      // 0x8000 = 32768 unsigned; 0xFFFF = 65535 unsigned (= -1 signed).
      val a    = fromI16(0x8000.toShort.toInt, 0xffff.toShort.toInt, 100,   0,   0, 0, 0, 0)
      val b    = fromI16(0x8000.toShort.toInt,                    1,  50,   0,   0, 0, 0, 0)
      val out  = callV128(inst, "add_sat_u_i16x8", V128(a), V128(b))
      // 32768+32768 = 65536 → clamps to 65535 = -1 signed
      // 65535+1 = 65536 → clamps to 65535 = -1
      // 100+50 = 150 (no clamp)
      val want = fromI16(-1, -1, 150, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i16x8.add_sat_u: ${out.mkString(",")}")
    }

    test("i16x8.sub wraps mod 65536 per lane") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI16(   0, -32768, 1000,  500, 0, 0, 0, 0)
      val b    = fromI16(   1,      1,  500, 1000, 0, 0, 0, 0)
      val out  = callV128(inst, "sub_i16x8", V128(a), V128(b))
      // lane 0: 0-1 = -1
      // lane 1: -32768-1 = -32769 → wraps to 32767
      val want = fromI16(  -1,  32767,  500, -500, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i16x8.sub: ${out.mkString(",")}")
    }

    test("i16x8.sub_sat_s clamps to [-32768, 32767]") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI16(-30000,  30000,     0,   500, 0, 0, 0, 0)
      val b    = fromI16(  5000, -10000, -32768,   100, 0, 0, 0, 0)
      val out  = callV128(inst, "sub_sat_s_i16x8", V128(a), V128(b))
      // lane 0: -30000-5000 = -35000 → -32768
      // lane 1: 30000-(-10000) = 40000 → 32767
      // lane 2: 0-(-32768) = 32768 → 32767
      // lane 3: 500-100 = 400
      val want = fromI16(-32768,  32767, 32767,   400, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i16x8.sub_sat_s: ${out.mkString(",")}")
    }

    test("i16x8.sub_sat_u clamps to 0 unsigned") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI16(   100,     0,   1000, 0xffff.toShort.toInt, 0, 0, 0, 0)
      val b    = fromI16(   200,     1,    500,                    1, 0, 0, 0, 0)
      val out  = callV128(inst, "sub_sat_u_i16x8", V128(a), V128(b))
      // lane 0: 100-200 = -100 → 0
      // lane 1: 0-1 → 0
      // lane 2: 1000-500 = 500
      // lane 3: 65535-1 = 65534 = -2 signed
      val want = fromI16(     0,     0,    500,                   -2, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i16x8.sub_sat_u: ${out.mkString(",")}")
    }

    test("i16x8.mul keeps the low 16 bits of each lane's full-width product") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI16(  256,    3,    -1, 1000, 100, 32767, -32768, 2)
      val b    = fromI16(   10,    4,     2,  100,  50,     2,      2, -1)
      val out  = callV128(inst, "mul_i16x8", V128(a), V128(b))
      // 256*10 = 2560
      // 3*4 = 12
      // -1*2 = -2
      // 1000*100 = 100000 → wraps mod 65536 = 100000 - 65536 = 34464; as i16 = -31072
      // 100*50 = 5000
      // 32767*2 = 65534 → as i16 = -2
      // -32768*2 = -65536 → as i16 = 0
      // 2*-1 = -2
      val want = fromI16( 2560,   12,    -2, -31072, 5000,    -2,      0, -2)
      check(bytesEq(out, want), s"i16x8.mul: ${out.mkString(",")}")
    }

    test("i16x8.avgr_u rounds up via (a+b+1)/2 unsigned") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI16(   200,  3, 0, 0xffff.toShort.toInt, 65500, 0, 0, 0)
      val b    = fromI16(   201,  4, 0, 0xffff.toShort.toInt,     0, 0, 0, 0)
      val out  = callV128(inst, "avgr_u_i16x8", V128(a), V128(b))
      // (200+201+1)/2 = 201
      // (3+4+1)/2 = 4
      // (0+0+1)/2 = 0
      // (65535+65535+1)/2 = 65535 = -1 signed
      // (65500+1)/2 = 32750
      val want = fromI16(   201,  4, 0,                   -1, 32750, 0, 0, 0)
      check(bytesEq(out, want), s"i16x8.avgr_u: ${out.mkString(",")}")
    }

    // === i32x4 ===========================================================

    test("i32x4.abs takes lane-wise abs; abs(Int.MinValue) wraps to MinValue") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI32(  -5, 1000000, Int.MinValue, 42)
      val out  = callV128(inst, "abs_i32x4", V128(a))
      val want = fromI32(   5, 1000000, Int.MinValue, 42)
      check(bytesEq(out, want), s"i32x4.abs: ${out.mkString(",")}")
    }

    test("i32x4.neg flips signs; neg(Int.MinValue) wraps to MinValue") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI32(  0,  5, Int.MinValue, Int.MaxValue)
      val out  = callV128(inst, "neg_i32x4", V128(a))
      val want = fromI32(  0, -5, Int.MinValue, -Int.MaxValue)
      check(bytesEq(out, want), s"i32x4.neg: ${out.mkString(",")}")
    }

    test("i32x4.add wraps mod 2^32 per lane") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI32(   1, Int.MaxValue, Int.MinValue,  1000000)
      val b    = fromI32(   2,            1,           -1,  2000000)
      val out  = callV128(inst, "add_i32x4", V128(a), V128(b))
      val want = fromI32(   3, Int.MinValue, Int.MaxValue,  3000000)
      check(bytesEq(out, want), s"i32x4.add: ${out.mkString(",")}")
    }

    test("i32x4.sub wraps mod 2^32 per lane") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI32(   0, Int.MinValue,    1000000, 100)
      val b    = fromI32(   1,            1,    2000000, 200)
      val out  = callV128(inst, "sub_i32x4", V128(a), V128(b))
      val want = fromI32(  -1, Int.MaxValue,   -1000000, -100)
      check(bytesEq(out, want), s"i32x4.sub: ${out.mkString(",")}")
    }

    test("i32x4.mul keeps the low 32 bits of each lane's product") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI32(   3,      1000,     65536,    -1)
      val b    = fromI32(   7,      1000,     65536,    -1)
      val out  = callV128(inst, "mul_i32x4", V128(a), V128(b))
      // 3*7 = 21
      // 1000*1000 = 1_000_000
      // 65536*65536 = 2^32 → wraps to 0
      // -1*-1 = 1
      val want = fromI32(  21,   1000000,         0,     1)
      check(bytesEq(out, want), s"i32x4.mul: ${out.mkString(",")}")
    }

    // === i64x2 ===========================================================

    test("i64x2.abs takes lane-wise abs; abs(Long.MinValue) wraps to MinValue") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI64(-5L, Long.MinValue)
      val out  = callV128(inst, "abs_i64x2", V128(a))
      val want = fromI64( 5L, Long.MinValue)
      check(bytesEq(out, want), s"i64x2.abs: ${out.mkString(",")}")
    }

    test("i64x2.neg flips signs; neg(Long.MinValue) wraps to MinValue") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI64( 42L,             Long.MinValue)
      val out  = callV128(inst, "neg_i64x2", V128(a))
      val want = fromI64(-42L,             Long.MinValue)
      check(bytesEq(out, want), s"i64x2.neg: ${out.mkString(",")}")
    }

    test("i64x2.add wraps mod 2^64 per lane") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI64( Long.MaxValue, 1000000000000L)
      val b    = fromI64(            1L,     2000000L)
      val out  = callV128(inst, "add_i64x2", V128(a), V128(b))
      val want = fromI64( Long.MinValue, 1000002000000L)
      check(bytesEq(out, want), s"i64x2.add: ${out.mkString(",")}")
    }

    test("i64x2.sub wraps mod 2^64 per lane") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI64( Long.MinValue, 1000000000000L)
      val b    = fromI64(            1L,           1L)
      val out  = callV128(inst, "sub_i64x2", V128(a), V128(b))
      val want = fromI64( Long.MaxValue,  999999999999L)
      check(bytesEq(out, want), s"i64x2.sub: ${out.mkString(",")}")
    }

    test("i64x2.mul keeps the low 64 bits of each lane's product") {
      val inst = instantiate(Fixtures.simd_int_arith)
      val a    = fromI64( 1000000L,   2L)
      val b    = fromI64( 1000000L,   3L)
      val out  = callV128(inst, "mul_i64x2", V128(a), V128(b))
      // 10^6 * 10^6 = 10^12; 2*3 = 6
      val want = fromI64( 1000000000000L, 6L)
      check(bytesEq(out, want), s"i64x2.mul: ${out.mkString(",")}")
    }

    // === regressions surfaced by the W3C spec runner =====================

    test("i8x16.popcnt counts set bits per byte lane (SIMD sub-opcode 0x62)") {
      // Regression — surfaced by simd_i8x16_arith2.wast. Sub-opcode 0x62
      // was unimplemented (we jumped from 0x61 neg to 0x63 all_true).
      val inst = instantiate(Fixtures.simd_popcnt)
      val a    = fromI8(0, 1, 2, 3, 4, 5, 6, 7, 0xff.toByte, 0x80.toByte,
                        0x55, 0xaa.toByte, 0x0f, 0xf0.toByte, 127, -128)
      val out  = callV128(inst, "popcnt", V128(a))
      val want = fromI8(0, 1, 1, 2, 1, 2, 2, 3, 8, 1,
                        4, 4, 4, 4, 7, 1)
      check(bytesEq(out, want), s"i8x16.popcnt: ${out.mkString(",")}")
    }

    test("i16x8.q15mulr_sat_s — Q15 mulr with saturation at lane bounds") {
      // Regression — surfaced by simd_i16x8_q15mulr_sat_s.wast. Only the
      // relaxed-SIMD variant (0x111) was implemented; non-relaxed 0x82
      // was missing. Spec: (a*b + 0x4000) >> 15, saturated to i16 range.
      // The shift is arithmetic (sign-extending), so for negative
      // products it rounds toward negative infinity. (-32768) * (-32768)
      // is the only case that needs the clamp.
      val inst = instantiate(Fixtures.simd_q15mulr)
      val a    = fromI16(   0,  16384,  -32768, 32767, 32767, -32768,  100,  -1)
      val b    = fromI16(   0,  16384,  -32768, 32767, -32768, 32767,  200,  -1)
      val out  = callV128(inst, "q15mulr", V128(a), V128(b))
      // lane 0: 0
      // lane 1: 16384² + 0x4000 = 0x10004000; >>15 = 0x2000 = 8192
      // lane 2: 32768² + 0x4000 = 32769 → clamp 32767
      // lane 3: 32767² + 0x4000 = 0x3FFE4001; >>15 = 0x7FFE = 32766
      // lane 4 / 5: 32767 * -32768 + 0x4000 = -0x3FFF4000;
      //             arithmetic >>15 floors to -32767
      // lane 6: (20000+16384)>>15 = 1
      // lane 7: (1+16384)>>15 = 0
      val want = fromI16( 0, 8192, 32767, 32766, -32767, -32767, 1, 0)
      check(bytesEq(out, want), s"i16x8.q15mulr_sat_s: ${out.mkString(",")}")
    }

    test("select (untyped 0x1B) accepts v128 operands (SIMD numtype rule)") {
      // Regression — surfaced by simd_select.wast. The validator's
      // numeric predicate excluded v128; the SIMD proposal extends
      // "numtype" to include v128 for the purpose of the untyped
      // select form.
      val inst = instantiate(Fixtures.simd_v128_select)
      val a    = fromI32(1, 2, 3, 4)
      val b    = fromI32(10, 20, 30, 40)
      val pick = callV128(inst, "vselect", V128(a), V128(b), I32(1))
      check(bytesEq(pick, a), s"cond=1 should pick first operand, got ${pick.mkString(",")}")
      val drop = callV128(inst, "vselect", V128(a), V128(b), I32(0))
      check(bytesEq(drop, b), s"cond=0 should pick second operand, got ${drop.mkString(",")}")
    }
