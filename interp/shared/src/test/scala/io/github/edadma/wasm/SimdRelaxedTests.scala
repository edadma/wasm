package io.github.edadma.wasm

import TestSupport.*
import TestSupport.simd.{bytesEq, callV128, fromF32, fromF64, fromI8, fromI16, fromI32, fromI64}

/** Relaxed SIMD proposal — 20 sub-opcodes (0x100..0x113) under the
  * existing `0xFD` SIMD prefix. The proposal allows multiple valid
  * implementations per op; this interpreter pins one deterministic choice
  * each, documented in `simd_dispatch.scala / stepFdRelaxed`. The tests
  * here are pinning tests — they encode the choice we made so a future
  * change has to update the test consciously.
  *
  * Hand-built fixtures (no shared Fixtures.scala entry) — each function
  * takes one or more v128 params, runs the relaxed-SIMD op, returns v128.
  */
object SimdRelaxedTests:

  // === Module-building helpers ============================================

  private def sec(id: Int, content: Array[Byte]): Array[Byte] =
    b(id, content.length) ++ content

  /** Build a single-export wasm module: function `f` with N v128 params,
    * one v128 result; body = exactly the byte sequence in `opBytes` (which
    * should pop the params, run the op, leave the result on top, end). */
  private def buildModule(paramCount: Int, opBytes: Array[Byte]): Array[Byte] =
    val paramBytes = Array.fill(paramCount)(0x7b.toByte)  // v128 valtype = 0x7B
    val ftBytes    = b(0x60, paramCount) ++ paramBytes ++ b(0x01, 0x7b)
    val typeSec    = sec(0x01, b(0x01) ++ ftBytes)        // 1 functype
    val funcSec    = sec(0x03, b(0x01, 0x00))              // 1 func, typeidx 0
    val expSec     = sec(0x07, b(0x01, 0x01, 'f'.toInt, 0x00, 0x00))
    // Body: zero locals, then opBytes (must include the final 0x0b end).
    val body       = b(0x00) ++ opBytes
    val codeSec    = sec(0x0a, b(0x01) ++ b(body.length) ++ body)
    Header ++ typeSec ++ funcSec ++ expSec ++ codeSec

  /** LEB128 encoding of an unsigned int that fits in two bytes (covers
    * the 0x100..0x113 sub-opcode range). Sub-opcode N encodes as
    * `(N & 0x7F) | 0x80, N >> 7`. */
  private def fdSubLeb(sub: Int): Array[Byte] =
    b(0xfd, (sub & 0x7f) | 0x80, sub >> 7)

  /** A single `local.get N` opcode. */
  private def localGet(n: Int): Array[Byte] = b(0x20, n)

  // === Test runner =========================================================

  def run(): Unit =
    swizzleAndTrunc()
    madd()
    laneselect()
    minMax()
    q15mulrAndDots()

  // === relaxed_swizzle + relaxed_trunc_* ==================================

  private def swizzleAndTrunc(): Unit =

    test("i8x16.relaxed_swizzle: in-range indices permute; ≥16 → 0 (pinned to non-relaxed semantics)") {
      val opBytes = localGet(0) ++ localGet(1) ++ fdSubLeb(0x100) ++ b(0x0b)
      val inst    = instantiate(buildModule(2, opBytes))
      val src     = fromI8(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, -1, -2, -3, -4, -5, -6)
      val idx     = fromI8(0,   1,  2,  3,  4,  5,  6,  7,  8,   9, 10, 11, 12, 13, 14, 99)
      val out     = callV128(inst, "f", V128(src), V128(idx))
      val want    = fromI8(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, -1, -2, -3, -4, -5,  0)
      check(bytesEq(out, want), s"relaxed_swizzle: ${out.mkString(",")}")
    }

    test("i32x4.relaxed_trunc_f32x4_s: NaN → 0, overflow → INT_MIN/INT_MAX (pinned to trunc_sat semantics)") {
      val opBytes = localGet(0) ++ fdSubLeb(0x101) ++ b(0x0b)
      val inst    = instantiate(buildModule(1, opBytes))
      val a       = fromF32(Float.NaN, 3.7f, 1e20f, -1e20f)
      val out     = callV128(inst, "f", V128(a))
      val want    = fromI32(0, 3, Int.MaxValue, Int.MinValue)
      check(bytesEq(out, want), s"relaxed_trunc_s: ${out.mkString(",")}")
    }

    test("i32x4.relaxed_trunc_f32x4_u: NaN/negative → 0, overflow → 0xFFFFFFFF") {
      val opBytes = localGet(0) ++ fdSubLeb(0x102) ++ b(0x0b)
      val inst    = instantiate(buildModule(1, opBytes))
      val a       = fromF32(Float.NaN, -2.5f, 1e20f, 7.0f)
      val out     = callV128(inst, "f", V128(a))
      val want    = fromI32(0, 0, -1, 7)             // -1 = 0xFFFFFFFF as signed
      check(bytesEq(out, want), s"relaxed_trunc_u: ${out.mkString(",")}")
    }

    test("i32x4.relaxed_trunc_f64x2_s_zero: lanes 2/3 zeroed") {
      val opBytes = localGet(0) ++ fdSubLeb(0x103) ++ b(0x0b)
      val inst    = instantiate(buildModule(1, opBytes))
      val a       = fromF64(Double.NaN, 5.9)
      val out     = callV128(inst, "f", V128(a))
      val want    = fromI32(0, 5, 0, 0)
      check(bytesEq(out, want), s"relaxed_trunc_f64_s_zero: ${out.mkString(",")}")
    }

  // === relaxed_madd / relaxed_nmadd =======================================

  private def madd(): Unit =

    test("f32x4.relaxed_madd: per-lane (a*b)+c (unfused — interpreter doesn't promise FMA)") {
      val opBytes = localGet(0) ++ localGet(1) ++ localGet(2) ++ fdSubLeb(0x105) ++ b(0x0b)
      val inst    = instantiate(buildModule(3, opBytes))
      val a       = fromF32(2.0f, 3.0f, 4.0f, 5.0f)
      val bv      = fromF32(1.0f, 2.0f, 3.0f, 4.0f)
      val cv      = fromF32(0.5f, 0.5f, 0.5f, 0.5f)
      val out     = callV128(inst, "f", V128(a), V128(bv), V128(cv))
      val want    = fromF32(2.5f, 6.5f, 12.5f, 20.5f)
      check(bytesEq(out, want), s"relaxed_madd: ${out.mkString(",")}")
    }

    test("f32x4.relaxed_nmadd: per-lane (-(a*b))+c") {
      val opBytes = localGet(0) ++ localGet(1) ++ localGet(2) ++ fdSubLeb(0x106) ++ b(0x0b)
      val inst    = instantiate(buildModule(3, opBytes))
      val a       = fromF32(2.0f, 3.0f, 4.0f, 5.0f)
      val bv      = fromF32(1.0f, 2.0f, 3.0f, 4.0f)
      val cv      = fromF32(100.0f, 100.0f, 100.0f, 100.0f)
      val out     = callV128(inst, "f", V128(a), V128(bv), V128(cv))
      val want    = fromF32(98.0f, 94.0f, 88.0f, 80.0f)
      check(bytesEq(out, want), s"relaxed_nmadd: ${out.mkString(",")}")
    }

    test("f64x2.relaxed_madd: 2-lane double-precision (a*b)+c") {
      val opBytes = localGet(0) ++ localGet(1) ++ localGet(2) ++ fdSubLeb(0x107) ++ b(0x0b)
      val inst    = instantiate(buildModule(3, opBytes))
      val a       = fromF64(2.0, 3.0)
      val bv      = fromF64(4.0, 5.0)
      val cv      = fromF64(1.0, 1.0)
      val out     = callV128(inst, "f", V128(a), V128(bv), V128(cv))
      val want    = fromF64(9.0, 16.0)
      check(bytesEq(out, want), s"relaxed_madd f64: ${out.mkString(",")}")
    }

  // === relaxed_laneselect =================================================

  private def laneselect(): Unit =

    test("i8x16.relaxed_laneselect: high bit of each byte picks a vs b") {
      // 16 mask bytes — alternating: bit 7 set picks a, clear picks b.
      val opBytes = localGet(0) ++ localGet(1) ++ localGet(2) ++ fdSubLeb(0x109) ++ b(0x0b)
      val inst    = instantiate(buildModule(3, opBytes))
      val a       = fromI8(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16)
      val bv      = fromI8(-1,-2,-3,-4,-5,-6,-7,-8,-9,-10,-11,-12,-13,-14,-15,-16)
      // mask: lanes 0,2,4,...,14 have bit 7 set (select a); odd lanes clear (select b).
      val mask    = fromI8(-128,  0, -128,  0, -128, 0, -128, 0, -128, 0, -128, 0, -128, 0, -128, 0)
      val out     = callV128(inst, "f", V128(a), V128(bv), V128(mask))
      val want    = fromI8(1,-2,3,-4,5,-6,7,-8,9,-10,11,-12,13,-14,15,-16)
      check(bytesEq(out, want), s"i8x16 laneselect: ${out.mkString(",")}")
    }

    test("i32x4.relaxed_laneselect: high bit of each lane's MSB picks a vs b") {
      val opBytes = localGet(0) ++ localGet(1) ++ localGet(2) ++ fdSubLeb(0x10b) ++ b(0x0b)
      val inst    = instantiate(buildModule(3, opBytes))
      val a       = fromI32(100, 200, 300, 400)
      val bv      = fromI32(-100, -200, -300, -400)
      // lane mask high bit: set for lanes 0/2, clear for lanes 1/3.
      val mask    = fromI32(0x80000000, 0x00000000, 0xFFFFFFFF, 0x7FFFFFFF)
      val out     = callV128(inst, "f", V128(a), V128(bv), V128(mask))
      val want    = fromI32(100, -200, 300, -400)
      check(bytesEq(out, want), s"i32x4 laneselect: ${out.mkString(",")}")
    }

    test("i64x2.relaxed_laneselect: high bit of each i64 picks a vs b") {
      val opBytes = localGet(0) ++ localGet(1) ++ localGet(2) ++ fdSubLeb(0x10c) ++ b(0x0b)
      val inst    = instantiate(buildModule(3, opBytes))
      val a       = fromI64(123L, 456L)
      val bv      = fromI64(-123L, -456L)
      val mask    = fromI64(0x8000000000000000L, 0L)
      val out     = callV128(inst, "f", V128(a), V128(bv), V128(mask))
      val want    = fromI64(123L, -456L)
      check(bytesEq(out, want), s"i64x2 laneselect: ${out.mkString(",")}")
    }

  // === relaxed_min / relaxed_max ==========================================

  private def minMax(): Unit =

    test("f32x4.relaxed_min: per-lane Math.min; -0.0 vs 0.0 handling is impl-defined (NaN propagates here)") {
      val opBytes = localGet(0) ++ localGet(1) ++ fdSubLeb(0x10d) ++ b(0x0b)
      val inst    = instantiate(buildModule(2, opBytes))
      val a       = fromF32(1.0f, 5.0f, Float.NaN, 2.5f)
      val bv      = fromF32(2.0f, 3.0f, 7.0f,      Float.NaN)
      val out     = callV128(inst, "f", V128(a), V128(bv))
      // Math.min returns NaN if either is NaN, lesser otherwise.
      val outF = (0 until 4).map(i =>
        java.lang.Float.intBitsToFloat(
          (out(i*4) & 0xff) | ((out(i*4+1) & 0xff) << 8) |
          ((out(i*4+2) & 0xff) << 16) | ((out(i*4+3) & 0xff) << 24)
        )
      )
      check(outF(0) == 1.0f, s"lane 0: ${outF(0)}")
      check(outF(1) == 3.0f, s"lane 1: ${outF(1)}")
      check(java.lang.Float.isNaN(outF(2)), s"lane 2 should be NaN, got ${outF(2)}")
      check(java.lang.Float.isNaN(outF(3)), s"lane 3 should be NaN, got ${outF(3)}")
    }

    test("f64x2.relaxed_max: per-lane Math.max; NaN propagates") {
      val opBytes = localGet(0) ++ localGet(1) ++ fdSubLeb(0x110) ++ b(0x0b)
      val inst    = instantiate(buildModule(2, opBytes))
      val a       = fromF64(1.0, Double.NaN)
      val bv      = fromF64(2.0, 5.0)
      val out     = callV128(inst, "f", V128(a), V128(bv))
      val outD = (0 until 2).map(i =>
        java.lang.Double.longBitsToDouble(
          (0 until 8).map(k => (out(i*8+k) & 0xffL) << (k*8)).reduce(_ | _)
        )
      )
      check(outD(0) == 2.0, s"lane 0: ${outD(0)}")
      check(java.lang.Double.isNaN(outD(1)), s"lane 1 should be NaN, got ${outD(1)}")
    }

  // === relaxed_q15mulr_s + relaxed dots ===================================

  private def q15mulrAndDots(): Unit =

    test("i16x8.relaxed_q15mulr_s: (a*b + 0x4000) >> 15 with saturation; 0x7FFF * 0x7FFF saturates to 0x7FFF") {
      val opBytes = localGet(0) ++ localGet(1) ++ fdSubLeb(0x111) ++ b(0x0b)
      val inst    = instantiate(buildModule(2, opBytes))
      val a       = fromI16(0x4000, 0x7FFF, -0x8000, -0x8000, 0, 1, 100, -100)
      val bv      = fromI16(0x4000, 0x7FFF, -0x8000,  0x7FFF, 0, 1, 200,  200)
      val out     = callV128(inst, "f", V128(a), V128(bv))
      // Per-lane (a*b + 0x4000) >> 15, then saturate to i16. The `>>` is
      // ARITHMETIC right shift — for negative dividends it floors toward
      // -∞, so the third lane's product divides to -32767, not -32766.
      // 0x4000 * 0x4000 = 0x10000000; + 0x4000 → 0x10004000; >>15 → 0x2000.
      // 0x7FFF * 0x7FFF = 0x3FFF0001; + 0x4000 → 0x3FFF4001; >>15 → 0x7FFE.
      // -0x8000 * -0x8000 = 0x40000000; + 0x4000 → 0x40004000; >>15 → 0x8000 → saturates to 0x7FFF.
      // -0x8000 * 0x7FFF = -0x3FFF8000; + 0x4000 → -0x3FFF4000; >>15 → -0x7FFF (floor).
      val want    = fromI16(0x2000, 0x7FFE, 0x7FFF, -0x7FFF, 0, 0, 1, -1)
      check(bytesEq(out, want), s"q15mulr: ${out.toSeq.map(b => f"$b%d").mkString(",")}")
    }

    test("i16x8.relaxed_dot_i8x16_i7x16_s: pair-sum of (sig×unsig) byte products per i16 lane") {
      val opBytes = localGet(0) ++ localGet(1) ++ fdSubLeb(0x112) ++ b(0x0b)
      val inst    = instantiate(buildModule(2, opBytes))
      // lane i = signed(a[2i]) * unsigned(b[2i]) + signed(a[2i+1]) * unsigned(b[2i+1]).
      // a = -1,2, -3,4, ..., b = 10,20, 30,40, ...
      val a       = fromI8(-1, 2, -3,  4, -5,  6, -7,  8, -9, 10, -11, 12, -13, 14, -15, 16)
      val bv      = fromI8(10,20, 30, 40, 50, 60, 70, 80,  1,  2,   3,  4,   5,  6,   7,  8)
      val out     = callV128(inst, "f", V128(a), V128(bv))
      // lane 0: -1*10 + 2*20 = 30
      // lane 1: -3*30 + 4*40 = 70
      // lane 2: -5*50 + 6*60 = 110
      // lane 3: -7*70 + 8*80 = 150
      // lane 4: -9*1  + 10*2 = 11
      // lane 5: -11*3 + 12*4 = 15
      // lane 6: -13*5 + 14*6 = 19
      // lane 7: -15*7 + 16*8 = 23
      val want    = fromI16(30, 70, 110, 150, 11, 15, 19, 23)
      check(bytesEq(out, want), s"dot_i7x16_s: ${out.mkString(",")}")
    }

    test("i32x4.relaxed_dot_i8x16_i7x16_add_s: 4-byte (sig×unsig) sums per i32 lane + accumulator c") {
      val opBytes = localGet(0) ++ localGet(1) ++ localGet(2) ++ fdSubLeb(0x113) ++ b(0x0b)
      val inst    = instantiate(buildModule(3, opBytes))
      val a       = fromI8(1, 2, 3, 4,  -1, -2, -3, -4,  10, 20, 30, 40,  0, 0, 0, 0)
      val bv      = fromI8(5, 6, 7, 8,   5,  6,  7,  8,   1,  2,  3,  4,  1, 1, 1, 1)
      val cv      = fromI32(100, 0, 1000, 7)
      // lane 0: 1*5 + 2*6 + 3*7 + 4*8 = 5+12+21+32 = 70; + 100 = 170
      // lane 1: -1*5 + -2*6 + -3*7 + -4*8 = -70; + 0 = -70
      // lane 2: 10*1 + 20*2 + 30*3 + 40*4 = 10+40+90+160 = 300; + 1000 = 1300
      // lane 3: 0; + 7 = 7
      val out     = callV128(inst, "f", V128(a), V128(bv), V128(cv))
      val want    = fromI32(170, -70, 1300, 7)
      check(bytesEq(out, want), s"dot_add: ${out.mkString(",")}")
    }
