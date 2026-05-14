package io.github.edadma.wasm

import java.lang as jl

import TestSupport.*
import TestSupport.simd.{bytesEq, callV128, fromF32, fromF64, fromI32, fromI64}

/** Phase 8.E.F — SIMD float arithmetic.
  *
  * Per-shape coverage:
  *
  *   - f32x4 / f64x2: ceil, floor, trunc, nearest (4 rounding ops).
  *   - f32x4 / f64x2: abs, neg, sqrt (3 unary).
  *   - f32x4 / f64x2: add, sub, mul, div (4 binary).
  *   - f32x4 / f64x2: min, max, pmin, pmax (4 NaN-sensitive binary).
  *
  * Edge cases the tests pin:
  *   - NaN propagation through every binary op (the JVM rolls input NaNs
  *     into its canonical 0x7FC00000 / 0x7FF8000000000000 quietly).
  *   - `abs` / `neg` are bit-level — they preserve NaN payloads exactly
  *     (we read back the raw bits and confirm only the sign bit moved).
  *   - Signed zeros: `min(-0, +0) = -0`, `max(-0, +0) = +0`, and `neg`
  *     flips the sign on zero too (so `neg(+0) = -0`).
  *   - `nearest` is round-half-to-even: `2.5 → 2`, `3.5 → 4`, `-2.5 → -2`.
  *   - `trunc` rounds toward zero: `-0.7 → -0.0` (not `-1.0`).
  *   - `sqrt(-1.0) = NaN`; `sqrt(-0.0) = -0.0` (signed zero preserved).
  *   - `pmin` / `pmax` use the spec's `if b<a then b else a` / `if a<b
  *     then b else a` formula — NaN-involving compares return `false`,
  *     so the result picks `a` whenever either operand is NaN.
  *   - Infinity arithmetic: `1/0 = +Inf`, `-1/0 = -Inf`, `0/0 = NaN`,
  *     `Inf - Inf = NaN`, `Inf + 1 = Inf`.
  */
object SimdFloatArithTests:

  /** Read one f32 lane as raw int bits for NaN-payload assertions. */
  private def laneBitsF32(bs: Array[Byte], lane: Int): Int =
    val o = lane * 4
    (bs(o)     & 0xff)        |
    ((bs(o + 1) & 0xff) <<  8) |
    ((bs(o + 2) & 0xff) << 16) |
    ((bs(o + 3) & 0xff) << 24)

  /** Read one f64 lane as raw long bits. */
  private def laneBitsF64(bs: Array[Byte], lane: Int): Long =
    val o = lane * 8
    var k = 0
    var r = 0L
    while k < 8 do
      r |= (bs(o + k).toLong & 0xffL) << (k * 8)
      k += 1
    r

  private def laneF32(bs: Array[Byte], lane: Int): Float =
    jl.Float.intBitsToFloat(laneBitsF32(bs, lane))

  private def laneF64(bs: Array[Byte], lane: Int): Double =
    jl.Double.longBitsToDouble(laneBitsF64(bs, lane))

  def run(): Unit =

    // === f32x4 unary — rounding =============================================

    test("f32x4.ceil rounds each lane toward +Inf") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a    = fromF32(1.4f, -1.4f, 2.0f, -0.1f)
      val out  = callV128(inst, "ceil_f32x4", V128(a))
      val want = fromF32(2.0f, -1.0f, 2.0f,  0.0f)        // -0.0 → 0.0 ? JVM Math.ceil returns -0.0 for (-1,0)
      // Math.ceil(-0.1) = -0.0; bit pattern preserved.
      check(laneF32(out, 0) == 2.0f, s"ceil(1.4) = ${laneF32(out, 0)}")
      check(laneF32(out, 1) == -1.0f, s"ceil(-1.4) = ${laneF32(out, 1)}")
      check(laneF32(out, 2) == 2.0f, s"ceil(2.0) = ${laneF32(out, 2)}")
      check(laneF32(out, 3) == -0.0f && laneBitsF32(out, 3) == 0x80000000, s"ceil(-0.1) lane3 bits = ${laneBitsF32(out, 3).toHexString}")
      val _ = want
    }

    test("f32x4.floor rounds each lane toward -Inf") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a    = fromF32(1.7f, -1.4f, 3.0f, 0.99f)
      val out  = callV128(inst, "floor_f32x4", V128(a))
      val want = fromF32(1.0f, -2.0f, 3.0f, 0.0f)
      check(bytesEq(out, want), s"floor: lane0=${laneF32(out, 0)} lane1=${laneF32(out, 1)} lane2=${laneF32(out, 2)} lane3=${laneF32(out, 3)}")
    }

    test("f32x4.trunc rounds each lane toward zero") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a    = fromF32(1.7f, -1.7f, 0.3f, -0.7f)
      val out  = callV128(inst, "trunc_f32x4", V128(a))
      check(laneF32(out, 0) == 1.0f, s"trunc(1.7) = ${laneF32(out, 0)}")
      check(laneF32(out, 1) == -1.0f, s"trunc(-1.7) = ${laneF32(out, 1)}")
      // trunc(0.3) → +0.0; trunc(-0.7) → -0.0 (sign preserved).
      check(laneBitsF32(out, 2) == 0,            s"trunc(0.3) bits = ${laneBitsF32(out, 2).toHexString}")
      check(laneBitsF32(out, 3) == 0x80000000,   s"trunc(-0.7) bits = ${laneBitsF32(out, 3).toHexString}")
    }

    test("f32x4.nearest rounds half to even per lane") {
      val inst = instantiate(Fixtures.simd_float_arith)
      // 2.5 → 2 (even), 3.5 → 4 (even), -2.5 → -2, 0.5 → 0.
      val a    = fromF32(2.5f, 3.5f, -2.5f, 0.5f)
      val out  = callV128(inst, "nearest_f32x4", V128(a))
      val want = fromF32(2.0f, 4.0f, -2.0f, 0.0f)
      check(bytesEq(out, want), s"nearest: ${(0 until 4).map(laneF32(out, _)).mkString(",")}")
    }

    // === f32x4 unary — abs/neg/sqrt =========================================

    test("f32x4.abs clears the sign bit (preserves NaN payloads)") {
      val inst = instantiate(Fixtures.simd_float_arith)
      // Mix: a normal negative, signed zero, +Inf, a NaN with custom payload.
      val nanBits = 0x7fa01234
      val a       = fromI32(jl.Float.floatToRawIntBits(-1.5f),
                            0x80000000,                                                 // -0.0
                            jl.Float.floatToRawIntBits(Float.PositiveInfinity),
                            nanBits)
      val out     = callV128(inst, "abs_f32x4", V128(a))
      check(laneF32(out, 0) == 1.5f,                          s"abs(-1.5) = ${laneF32(out, 0)}")
      check(laneBitsF32(out, 1) == 0,                         s"abs(-0.0) bits = ${laneBitsF32(out, 1).toHexString}")
      check(laneF32(out, 2) == Float.PositiveInfinity,        s"abs(+Inf) = ${laneF32(out, 2)}")
      check(laneBitsF32(out, 3) == (nanBits & 0x7fffffff),    s"abs(NaN) preserves payload, got ${laneBitsF32(out, 3).toHexString}")
    }

    test("f32x4.neg flips the sign bit (preserves NaN payloads)") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val nanBits = 0x7fa01234
      val a = fromI32(jl.Float.floatToRawIntBits(1.5f),
                      0,                                                                // +0.0
                      jl.Float.floatToRawIntBits(Float.NegativeInfinity),
                      nanBits)
      val out = callV128(inst, "neg_f32x4", V128(a))
      check(laneF32(out, 0) == -1.5f,                         s"neg(1.5) = ${laneF32(out, 0)}")
      check(laneBitsF32(out, 1) == 0x80000000,                s"neg(+0.0) bits = ${laneBitsF32(out, 1).toHexString}")
      check(laneF32(out, 2) == Float.PositiveInfinity,        s"neg(-Inf) = ${laneF32(out, 2)}")
      check(laneBitsF32(out, 3) == (nanBits ^ 0x80000000),    s"neg(NaN) flips sign only, got ${laneBitsF32(out, 3).toHexString}")
    }

    test("f32x4.sqrt: positive reals, NaN, signed zero") {
      val inst = instantiate(Fixtures.simd_float_arith)
      // sqrt(4) = 2; sqrt(-1) = NaN; sqrt(-0.0) = -0.0; sqrt(0) = 0.
      val a   = fromF32(4.0f, -1.0f, -0.0f, 0.0f)
      val out = callV128(inst, "sqrt_f32x4", V128(a))
      check(laneF32(out, 0) == 2.0f,                          s"sqrt(4) = ${laneF32(out, 0)}")
      check(jl.Float.isNaN(laneF32(out, 1)),                  s"sqrt(-1) = ${laneF32(out, 1)}")
      check(laneBitsF32(out, 2) == 0x80000000,                s"sqrt(-0.0) preserves sign, bits = ${laneBitsF32(out, 2).toHexString}")
      check(laneBitsF32(out, 3) == 0,                         s"sqrt(0.0) bits = ${laneBitsF32(out, 3).toHexString}")
    }

    // === f32x4 binary — arithmetic ==========================================

    test("f32x4.add: finite + NaN propagation + infinity") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a   = fromF32(1.0f, Float.NaN,         Float.PositiveInfinity, 1.0f)
      val b   = fromF32(2.5f, 1.0f,              1.0f,                   Float.NegativeInfinity)
      val out = callV128(inst, "add_f32x4", V128(a), V128(b))
      check(laneF32(out, 0) == 3.5f,                          s"add(1, 2.5) = ${laneF32(out, 0)}")
      check(jl.Float.isNaN(laneF32(out, 1)),                  s"add(NaN, 1) = ${laneF32(out, 1)}")
      check(laneF32(out, 2) == Float.PositiveInfinity,        s"add(+Inf, 1) = ${laneF32(out, 2)}")
      check(laneF32(out, 3) == Float.NegativeInfinity,        s"add(1, -Inf) = ${laneF32(out, 3)}")
    }

    test("f32x4.sub: Inf - Inf = NaN, signed zeros") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a   = fromF32(5.0f, Float.PositiveInfinity, 0.0f,  -0.0f)
      val b   = fromF32(2.0f, Float.PositiveInfinity, 0.0f,   0.0f)
      val out = callV128(inst, "sub_f32x4", V128(a), V128(b))
      check(laneF32(out, 0) == 3.0f,                          s"sub(5,2) = ${laneF32(out, 0)}")
      check(jl.Float.isNaN(laneF32(out, 1)),                  s"sub(+Inf, +Inf) = ${laneF32(out, 1)}")
      // 0 - 0 = +0 (IEEE default rounding); -0 - 0 = -0.
      check(laneBitsF32(out, 2) == 0,                         s"sub(0, 0) bits = ${laneBitsF32(out, 2).toHexString}")
      check(laneBitsF32(out, 3) == 0x80000000,                s"sub(-0, 0) bits = ${laneBitsF32(out, 3).toHexString}")
    }

    test("f32x4.mul: finite, zero × Inf = NaN, sign of zero") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a   = fromF32(2.0f, 0.0f,                       -3.0f,  -0.0f)
      val b   = fromF32(3.5f, Float.PositiveInfinity,      0.0f,   0.0f)
      val out = callV128(inst, "mul_f32x4", V128(a), V128(b))
      check(laneF32(out, 0) == 7.0f,                          s"mul(2, 3.5) = ${laneF32(out, 0)}")
      check(jl.Float.isNaN(laneF32(out, 1)),                  s"mul(0, +Inf) = ${laneF32(out, 1)}")
      // -3 × 0 = -0; -0 × 0 = -0 (sign XOR).
      check(laneBitsF32(out, 2) == 0x80000000,                s"mul(-3, 0) sign bit, bits = ${laneBitsF32(out, 2).toHexString}")
      check(laneBitsF32(out, 3) == 0x80000000,                s"mul(-0, 0) sign bit, bits = ${laneBitsF32(out, 3).toHexString}")
    }

    test("f32x4.div: finite, 1/0 = +Inf, 0/0 = NaN, -1/0 = -Inf") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a   = fromF32(10.0f, 1.0f, 0.0f, -1.0f)
      val b   = fromF32( 4.0f, 0.0f, 0.0f,  0.0f)
      val out = callV128(inst, "div_f32x4", V128(a), V128(b))
      check(laneF32(out, 0) == 2.5f,                          s"div(10, 4) = ${laneF32(out, 0)}")
      check(laneF32(out, 1) == Float.PositiveInfinity,        s"div(1, 0) = ${laneF32(out, 1)}")
      check(jl.Float.isNaN(laneF32(out, 2)),                  s"div(0, 0) = ${laneF32(out, 2)}")
      check(laneF32(out, 3) == Float.NegativeInfinity,        s"div(-1, 0) = ${laneF32(out, 3)}")
    }

    // === f32x4 binary — min/max with IEEE NaN handling ======================

    test("f32x4.min: IEEE min; NaN → NaN; min(-0, +0) = -0") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a   = fromF32(2.0f, Float.NaN, -0.0f,  0.0f)
      val b   = fromF32(5.0f, 1.0f,       0.0f, -0.0f)
      val out = callV128(inst, "min_f32x4", V128(a), V128(b))
      check(laneF32(out, 0) == 2.0f,                          s"min(2, 5) = ${laneF32(out, 0)}")
      check(jl.Float.isNaN(laneF32(out, 1)),                  s"min(NaN, 1) = ${laneF32(out, 1)}")
      check(laneBitsF32(out, 2) == 0x80000000,                s"min(-0, +0) = -0, bits = ${laneBitsF32(out, 2).toHexString}")
      check(laneBitsF32(out, 3) == 0x80000000,                s"min(+0, -0) = -0, bits = ${laneBitsF32(out, 3).toHexString}")
    }

    test("f32x4.max: IEEE max; NaN → NaN; max(-0, +0) = +0") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a   = fromF32(2.0f, 1.0f,       -0.0f,  0.0f)
      val b   = fromF32(5.0f, Float.NaN,    0.0f, -0.0f)
      val out = callV128(inst, "max_f32x4", V128(a), V128(b))
      check(laneF32(out, 0) == 5.0f,                          s"max(2, 5) = ${laneF32(out, 0)}")
      check(jl.Float.isNaN(laneF32(out, 1)),                  s"max(1, NaN) = ${laneF32(out, 1)}")
      check(laneBitsF32(out, 2) == 0,                         s"max(-0, +0) = +0, bits = ${laneBitsF32(out, 2).toHexString}")
      check(laneBitsF32(out, 3) == 0,                         s"max(+0, -0) = +0, bits = ${laneBitsF32(out, 3).toHexString}")
    }

    test("f32x4.pmin: spec `if b<a then b else a`; NaN-involving compare → a") {
      val inst = instantiate(Fixtures.simd_float_arith)
      // lane 0: b<a (1<2)        → b = 1
      // lane 1: a is NaN, b<a is false → a (NaN)
      // lane 2: a<b (-1<1), b<a false  → a = -1
      // lane 3: a is 1, b is NaN, b<a false → a = 1 (NaN does NOT propagate from b in pmin)
      val a   = fromF32(2.0f, Float.NaN, -1.0f, 1.0f)
      val b   = fromF32(1.0f, 5.0f,       1.0f, Float.NaN)
      val out = callV128(inst, "pmin_f32x4", V128(a), V128(b))
      check(laneF32(out, 0) == 1.0f,                          s"pmin(2, 1) = ${laneF32(out, 0)}")
      check(jl.Float.isNaN(laneF32(out, 1)),                  s"pmin(NaN, 5) → a (NaN) = ${laneF32(out, 1)}")
      check(laneF32(out, 2) == -1.0f,                         s"pmin(-1, 1) = ${laneF32(out, 2)}")
      check(laneF32(out, 3) == 1.0f,                          s"pmin(1, NaN) → a = ${laneF32(out, 3)}")
    }

    test("f32x4.pmax: spec `if a<b then b else a`") {
      val inst = instantiate(Fixtures.simd_float_arith)
      // lane 0: a<b (1<2) → b = 2
      // lane 1: a<b false (NaN), pick a (NaN)
      // lane 2: a<b false (5≥3), pick a = 5
      // lane 3: a<b false (NaN-involving), pick a = 1
      val a   = fromF32(1.0f, Float.NaN, 5.0f, 1.0f)
      val b   = fromF32(2.0f, 5.0f,      3.0f, Float.NaN)
      val out = callV128(inst, "pmax_f32x4", V128(a), V128(b))
      check(laneF32(out, 0) == 2.0f,                          s"pmax(1, 2) = ${laneF32(out, 0)}")
      check(jl.Float.isNaN(laneF32(out, 1)),                  s"pmax(NaN, 5) → a (NaN) = ${laneF32(out, 1)}")
      check(laneF32(out, 2) == 5.0f,                          s"pmax(5, 3) = ${laneF32(out, 2)}")
      check(laneF32(out, 3) == 1.0f,                          s"pmax(1, NaN) → a = ${laneF32(out, 3)}")
    }

    // === f64x2 unary — rounding =============================================

    test("f64x2.ceil / floor / trunc / nearest at f64 precision") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a    = fromF64(1.4, -1.4)
      val ceil = callV128(inst, "ceil_f64x2", V128(a))
      check(laneF64(ceil, 0) == 2.0 && laneF64(ceil, 1) == -1.0, s"ceil = ${laneF64(ceil, 0)},${laneF64(ceil, 1)}")
      val floor = callV128(inst, "floor_f64x2", V128(a))
      check(laneF64(floor, 0) == 1.0 && laneF64(floor, 1) == -2.0, s"floor = ${laneF64(floor, 0)},${laneF64(floor, 1)}")
      val trunc = callV128(inst, "trunc_f64x2", V128(fromF64(1.7, -1.7)))
      check(laneF64(trunc, 0) == 1.0 && laneF64(trunc, 1) == -1.0, s"trunc = ${laneF64(trunc, 0)},${laneF64(trunc, 1)}")
      val near  = callV128(inst, "nearest_f64x2", V128(fromF64(2.5, 3.5)))
      check(laneF64(near, 0) == 2.0 && laneF64(near, 1) == 4.0,    s"nearest half-to-even = ${laneF64(near, 0)},${laneF64(near, 1)}")
    }

    // === f64x2 unary — abs/neg/sqrt =========================================

    test("f64x2.abs / f64x2.neg are bit-level (preserve NaN payloads)") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val nanBits = 0x7ff8123456789abcL                                                 // quiet NaN with custom payload
      val a       = fromI64(jl.Double.doubleToRawLongBits(-2.5), nanBits)
      val abs     = callV128(inst, "abs_f64x2", V128(a))
      check(laneF64(abs, 0) == 2.5,                                  s"abs(-2.5) = ${laneF64(abs, 0)}")
      check(laneBitsF64(abs, 1) == (nanBits & 0x7fffffffffffffffL),  s"abs(NaN) preserves payload, got ${laneBitsF64(abs, 1).toHexString}")
      val neg     = callV128(inst, "neg_f64x2", V128(a))
      check(laneF64(neg, 0) == 2.5,                                  s"neg(-2.5) = ${laneF64(neg, 0)}")
      check(laneBitsF64(neg, 1) == (nanBits ^ 0x8000000000000000L),  s"neg(NaN) flips sign only, got ${laneBitsF64(neg, 1).toHexString}")
    }

    test("f64x2.sqrt: sqrt(2.0) and sqrt(-0.0) = -0.0") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a    = fromF64(2.0, -0.0)
      val out  = callV128(inst, "sqrt_f64x2", V128(a))
      check(jl.Math.abs(laneF64(out, 0) - jl.Math.sqrt(2.0)) < 1e-12, s"sqrt(2) = ${laneF64(out, 0)}")
      check(laneBitsF64(out, 1) == 0x8000000000000000L,               s"sqrt(-0.0) sign preserved, bits = ${laneBitsF64(out, 1).toHexString}")
    }

    // === f64x2 binary — arithmetic ==========================================

    test("f64x2.add / sub / mul / div across two lanes") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a    = fromF64( 1.5,  10.0)
      val b    = fromF64( 2.5,   4.0)
      val add  = callV128(inst, "add_f64x2", V128(a), V128(b))
      check(laneF64(add, 0) == 4.0 && laneF64(add, 1) == 14.0,        s"add = ${laneF64(add, 0)},${laneF64(add, 1)}")
      val sub  = callV128(inst, "sub_f64x2", V128(a), V128(b))
      check(laneF64(sub, 0) == -1.0 && laneF64(sub, 1) == 6.0,        s"sub = ${laneF64(sub, 0)},${laneF64(sub, 1)}")
      val mul  = callV128(inst, "mul_f64x2", V128(a), V128(b))
      check(laneF64(mul, 0) == 3.75 && laneF64(mul, 1) == 40.0,       s"mul = ${laneF64(mul, 0)},${laneF64(mul, 1)}")
      val div  = callV128(inst, "div_f64x2", V128(a), V128(b))
      check(laneF64(div, 0) == 0.6 && laneF64(div, 1) == 2.5,         s"div = ${laneF64(div, 0)},${laneF64(div, 1)}")
    }

    test("f64x2.add: NaN + finite = NaN; Inf + finite = Inf") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a    = fromF64(Double.NaN,           Double.PositiveInfinity)
      val b    = fromF64(1.0,                  1.0)
      val out  = callV128(inst, "add_f64x2", V128(a), V128(b))
      check(jl.Double.isNaN(laneF64(out, 0)),                         s"add(NaN, 1) = ${laneF64(out, 0)}")
      check(laneF64(out, 1) == Double.PositiveInfinity,               s"add(+Inf, 1) = ${laneF64(out, 1)}")
    }

    test("f64x2.div: 1/0 = +Inf, 0/0 = NaN") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a    = fromF64(1.0, 0.0)
      val b    = fromF64(0.0, 0.0)
      val out  = callV128(inst, "div_f64x2", V128(a), V128(b))
      check(laneF64(out, 0) == Double.PositiveInfinity,               s"div(1, 0) = ${laneF64(out, 0)}")
      check(jl.Double.isNaN(laneF64(out, 1)),                         s"div(0, 0) = ${laneF64(out, 1)}")
    }

    // === f64x2 binary — min/max + pmin/pmax =================================

    test("f64x2.min / max: IEEE behaviour + signed-zero ordering") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a    = fromF64(2.0,  -0.0)
      val b    = fromF64(5.0,   0.0)
      val mn   = callV128(inst, "min_f64x2", V128(a), V128(b))
      check(laneF64(mn, 0) == 2.0,                                    s"min(2, 5) = ${laneF64(mn, 0)}")
      check(laneBitsF64(mn, 1) == 0x8000000000000000L,                s"min(-0, +0) = -0, bits = ${laneBitsF64(mn, 1).toHexString}")
      val mx   = callV128(inst, "max_f64x2", V128(a), V128(b))
      check(laneF64(mx, 0) == 5.0,                                    s"max(2, 5) = ${laneF64(mx, 0)}")
      check(laneBitsF64(mx, 1) == 0,                                  s"max(-0, +0) = +0, bits = ${laneBitsF64(mx, 1).toHexString}")
    }

    test("f64x2.min / max: NaN → NaN propagation") {
      val inst = instantiate(Fixtures.simd_float_arith)
      val a    = fromF64(Double.NaN,  1.0)
      val b    = fromF64(1.0,         Double.NaN)
      val mn   = callV128(inst, "min_f64x2", V128(a), V128(b))
      check(jl.Double.isNaN(laneF64(mn, 0)) && jl.Double.isNaN(laneF64(mn, 1)),
            s"min NaN both lanes: ${laneF64(mn, 0)},${laneF64(mn, 1)}")
      val mx   = callV128(inst, "max_f64x2", V128(a), V128(b))
      check(jl.Double.isNaN(laneF64(mx, 0)) && jl.Double.isNaN(laneF64(mx, 1)),
            s"max NaN both lanes: ${laneF64(mx, 0)},${laneF64(mx, 1)}")
    }

    test("f64x2.pmin / pmax: spec form, NaN-involving compare picks a") {
      val inst = instantiate(Fixtures.simd_float_arith)
      // lane 0: pmin(2, 1) → b<a true → 1; pmax(2, 1) → a<b false → a = 2.
      // lane 1: pmin(NaN, 5) → b<a false (NaN) → a (NaN); pmax(NaN, 5) likewise.
      val a    = fromF64(2.0,  Double.NaN)
      val b    = fromF64(1.0,  5.0)
      val pmn  = callV128(inst, "pmin_f64x2", V128(a), V128(b))
      check(laneF64(pmn, 0) == 1.0,                                   s"pmin(2,1) = ${laneF64(pmn, 0)}")
      check(jl.Double.isNaN(laneF64(pmn, 1)),                         s"pmin(NaN,5) = ${laneF64(pmn, 1)}")
      val pmx  = callV128(inst, "pmax_f64x2", V128(a), V128(b))
      check(laneF64(pmx, 0) == 2.0,                                   s"pmax(2,1) = ${laneF64(pmx, 0)}")
      check(jl.Double.isNaN(laneF64(pmx, 1)),                         s"pmax(NaN,5) = ${laneF64(pmx, 1)}")
    }
