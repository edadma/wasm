package io.github.edadma.wasm

import TestSupport.*
import TestSupport.simd.{bytesEq, callV128, fromI8, fromI16, fromI32, fromI64, fromF32, fromF64}

/** Phase 8.E.G.2 — SIMD comparisons.
  *
  * 48 ops in total — every compare returns a v128 mask, with each output
  * lane all-1s (`0xFF…`) on true and 0 on false, same width as the input
  * shape. Sub-opcode ranges:
  *
  *   - i8x16 0x23..0x2C  (10 ops: eq, ne, + lt/gt/le/ge × _s/_u)
  *   - i16x8 0x2D..0x36  (same 10)
  *   - i32x4 0x37..0x40  (same 10)
  *   - i64x2 0xD6..0xDB  (6 ops: eq, ne, + lt_s/gt_s/le_s/ge_s — no _u per spec)
  *   - f32x4 0x41..0x46  (6 ops: eq, ne, + lt/gt/le/ge)
  *   - f64x2 0x47..0x4C  (same 6)
  *
  * Edge cases the tests pin:
  *
  *   - Signed vs unsigned: `i*.lt_s(-1, 0)` is true (negative < 0); the
  *     same bit pattern is `0xFF…` unsigned and lt_u against 0 is false.
  *   - i64x2 has only signed forms — there are no `_u` opcodes; this is
  *     in-spec, not a gap.
  *   - IEEE-754 NaN: `eq/lt/gt/le/ge` return false when any operand is
  *     NaN; `ne` returns true. Scala's `==`/`<` already match this on
  *     all three backends.
  *   - IEEE-754 signed zero: `-0.0 == +0.0` is true; `-0.0 < +0.0` is
  *     false (the standard says they compare equal, not ordered).
  *   - i64x2 boundaries: `lt_s(Long.MinValue, Long.MaxValue)` is true
  *     (-2^63 is smaller than every other Long); reversed is false.
  */
object SimdComparesTests:

  def run(): Unit =

    // ----------------------------------------------------------------------
    // i8x16 — 10 ops
    // ----------------------------------------------------------------------

    test("i8x16.eq lane-by-lane equality") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI8(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
      val b    = fromI8(1, 0, 3, 0, 5, 0, 7, 0, 9, 0, 11, 0, 13, 0, 15, 0)        // matches lanes 0,2,4,...,14
      val out  = callV128(inst, "i8x16_eq", V128(a), V128(b))
      val want = fromI8(-1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0)
      check(bytesEq(out, want), "i8x16.eq mask mismatch")
    }

    test("i8x16.ne is the bitwise complement of eq's mask") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI8(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
      val b    = fromI8(1, 0, 3, 0, 5, 0, 7, 0, 9, 0, 11, 0, 13, 0, 15, 0)
      val out  = callV128(inst, "i8x16_ne", V128(a), V128(b))
      val want = fromI8(0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1)
      check(bytesEq(out, want), "i8x16.ne mask mismatch")
    }

    test("i8x16.lt_s vs lt_u: -1 is signed-less, unsigned-greater than 0") {
      val inst = instantiate(Fixtures.simd_compares)
      // Lane 0: a=-1 (0xFF), b=0   — signed lt true, unsigned lt false
      // Lane 1: a= 1,        b=2   — both true
      // Lane 2: a= 5,        b=3   — both false
      // Rest padded so each lane independently exercises the right path.
      val a    = fromI8(-1, 1, 5, -128, 127, 0,  -1,  1, -1, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8( 0, 2, 3,  127, -128, 0, -1, -1,  1, 0, 0, 0, 0, 0, 0, 0)
      val s    = callV128(inst, "i8x16_lt_s", V128(a), V128(b))
      // signed: -1<0 T;  1<2 T;  5<3 F;  -128<127 T;  127<-128 F;  0<0 F;
      //         -1<-1 F; 1<-1 F; -1<1 T;  remaining lanes equal → F
      val wantS = fromI8(-1, -1, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(s, wantS), "i8x16.lt_s mask mismatch")

      val u     = callV128(inst, "i8x16_lt_u", V128(a), V128(b))
      // unsigned: 0xFF<0x00 F;  1<2 T;  5<3 F;  0x80<0x7F F (128 vs 127);
      //           0x7F<0x80 T (127 vs 128);  0<0 F;  0xFF<0xFF F;
      //           1<0xFF T (1 vs 255);  0xFF<1 F (255 vs 1);  rest F
      val wantU = fromI8(0, -1, 0, 0, -1, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(u, wantU), "i8x16.lt_u mask mismatch")
    }

    test("i8x16.gt_s and gt_u are the strict-greater complements of le") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI8(-1, 1, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8( 0, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val s    = callV128(inst, "i8x16_gt_s", V128(a), V128(b))
      // signed: -1>0 F; 1>2 F; 5>3 T
      val wantS = fromI8(0, 0, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(s, wantS), "i8x16.gt_s mask mismatch")

      val u     = callV128(inst, "i8x16_gt_u", V128(a), V128(b))
      // unsigned: 0xFF>0 T; 1>2 F; 5>3 T
      val wantU = fromI8(-1, 0, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(u, wantU), "i8x16.gt_u mask mismatch")
    }

    test("i8x16.le_s / ge_s / le_u / ge_u include the equal case") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI8(-1, 1, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8(-1, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      // le_s: -1<=-1 T; 1<=2 T; 5<=3 F; rest 0<=0 T (all the trailing lanes)
      val les   = callV128(inst, "i8x16_le_s", V128(a), V128(b))
      val wantLeS = fromI8(-1, -1, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1)
      check(bytesEq(les, wantLeS), "i8x16.le_s mask mismatch")

      // ge_s: -1>=-1 T; 1>=2 F; 5>=3 T; rest equal → T
      val ges     = callV128(inst, "i8x16_ge_s", V128(a), V128(b))
      val wantGeS = fromI8(-1, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1)
      check(bytesEq(ges, wantGeS), "i8x16.ge_s mask mismatch")

      // le_u: 0xFF<=0xFF T; 1<=2 T; 5<=3 F; rest 0<=0 T
      val leu     = callV128(inst, "i8x16_le_u", V128(a), V128(b))
      val wantLeU = fromI8(-1, -1, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1)
      check(bytesEq(leu, wantLeU), "i8x16.le_u mask mismatch")

      // ge_u: 0xFF>=0xFF T; 1>=2 F; 5>=3 T; rest 0>=0 T
      val geu     = callV128(inst, "i8x16_ge_u", V128(a), V128(b))
      val wantGeU = fromI8(-1, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1)
      check(bytesEq(geu, wantGeU), "i8x16.ge_u mask mismatch")
    }

    // ----------------------------------------------------------------------
    // i16x8 — 10 ops
    // ----------------------------------------------------------------------

    test("i16x8.eq / ne over 16-bit lanes") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI16(1, 2, 3, 4, 5, 6, 7, 8)
      val b    = fromI16(1, 0, 3, 0, 5, 0, 7, 0)
      val eq   = callV128(inst, "i16x8_eq", V128(a), V128(b))
      val ne   = callV128(inst, "i16x8_ne", V128(a), V128(b))
      val wantEq = fromI16(-1, 0, -1, 0, -1, 0, -1, 0)
      val wantNe = fromI16(0, -1, 0, -1, 0, -1, 0, -1)
      check(bytesEq(eq, wantEq), "i16x8.eq mask mismatch")
      check(bytesEq(ne, wantNe), "i16x8.ne mask mismatch")
    }

    test("i16x8.lt_s vs lt_u: -1 signed-less, unsigned-greater than 0") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI16(-1,  1, Short.MinValue, Short.MaxValue, 0, 0, 0, 0)
      val b    = fromI16( 0,  2, Short.MaxValue, Short.MinValue, 0, 0, 0, 0)
      val s    = callV128(inst, "i16x8_lt_s", V128(a), V128(b))
      // signed: -1<0 T; 1<2 T; MinValue<MaxValue T; MaxValue<MinValue F; 0<0 F×4
      val wantS = fromI16(-1, -1, -1, 0, 0, 0, 0, 0)
      check(bytesEq(s, wantS), "i16x8.lt_s mask mismatch")

      val u     = callV128(inst, "i16x8_lt_u", V128(a), V128(b))
      // unsigned: 0xFFFF<0 F; 1<2 T; 0x8000<0x7FFF F; 0x7FFF<0x8000 T; 0<0 F×4
      val wantU = fromI16(0, -1, 0, -1, 0, 0, 0, 0)
      check(bytesEq(u, wantU), "i16x8.lt_u mask mismatch")
    }

    test("i16x8.gt / le / ge variants — signed + unsigned") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI16(-1, 5, 3, 100, 0, 0, 0, 0)
      val b    = fromI16( 0, 5, 5, 100, 0, 0, 0, 0)
      // gt_s: -1>0 F; 5>5 F; 3>5 F; 100>100 F; 0>0 F×4 → all 0
      val gts   = callV128(inst, "i16x8_gt_s", V128(a), V128(b))
      check(bytesEq(gts, fromI16(0,0,0,0,0,0,0,0)), "i16x8.gt_s all-false mask")
      // gt_u: 0xFFFF>0 T; 5>5 F; 3>5 F; 100>100 F; rest F
      val gtu   = callV128(inst, "i16x8_gt_u", V128(a), V128(b))
      check(bytesEq(gtu, fromI16(-1,0,0,0,0,0,0,0)), "i16x8.gt_u expected -1 in lane 0")
      // le_s: -1<=0 T; 5<=5 T; 3<=5 T; 100<=100 T; rest T → all true
      val les   = callV128(inst, "i16x8_le_s", V128(a), V128(b))
      check(bytesEq(les, fromI16(-1,-1,-1,-1,-1,-1,-1,-1)), "i16x8.le_s all-true mask")
      // le_u: 0xFFFF<=0 F; rest T
      val leu   = callV128(inst, "i16x8_le_u", V128(a), V128(b))
      check(bytesEq(leu, fromI16(0,-1,-1,-1,-1,-1,-1,-1)), "i16x8.le_u lane 0 false")
      // ge_s: -1>=0 F; 5>=5 T; 3>=5 F; 100>=100 T; rest T
      val ges   = callV128(inst, "i16x8_ge_s", V128(a), V128(b))
      check(bytesEq(ges, fromI16(0,-1,0,-1,-1,-1,-1,-1)), "i16x8.ge_s mask mismatch")
      // ge_u: 0xFFFF>=0 T; 5>=5 T; 3>=5 F; 100>=100 T; rest T
      val geu   = callV128(inst, "i16x8_ge_u", V128(a), V128(b))
      check(bytesEq(geu, fromI16(-1,-1,0,-1,-1,-1,-1,-1)), "i16x8.ge_u mask mismatch")
    }

    // ----------------------------------------------------------------------
    // i32x4 — 10 ops
    // ----------------------------------------------------------------------

    test("i32x4.eq / ne over 32-bit lanes") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI32(0x11111111, 0x22222222, 0x33333333, 0x44444444)
      val b    = fromI32(0x11111111, 0x00000000, 0x33333333, 0x00000000)
      val eq   = callV128(inst, "i32x4_eq", V128(a), V128(b))
      val ne   = callV128(inst, "i32x4_ne", V128(a), V128(b))
      check(bytesEq(eq, fromI32(-1, 0, -1, 0)), "i32x4.eq mask")
      check(bytesEq(ne, fromI32(0, -1, 0, -1)), "i32x4.ne mask")
    }

    test("i32x4.lt_s vs lt_u: -1 signed-less, unsigned-greater than 0") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI32(-1, 1, Int.MinValue, Int.MaxValue)
      val b    = fromI32( 0, 2, Int.MaxValue, Int.MinValue)
      val s    = callV128(inst, "i32x4_lt_s", V128(a), V128(b))
      // signed: -1<0 T; 1<2 T; MinValue<MaxValue T; MaxValue<MinValue F
      check(bytesEq(s, fromI32(-1, -1, -1, 0)), "i32x4.lt_s mask")
      val u    = callV128(inst, "i32x4_lt_u", V128(a), V128(b))
      // unsigned: 0xFFFFFFFF<0 F; 1<2 T; 0x80000000<0x7FFFFFFF F; 0x7FFFFFFF<0x80000000 T
      check(bytesEq(u, fromI32(0, -1, 0, -1)), "i32x4.lt_u mask")
    }

    test("i32x4.gt_s / gt_u / le_s / le_u / ge_s / ge_u") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI32(-1, 5, 3, 100)
      val b    = fromI32( 0, 5, 5, 100)
      // gt_s: -1>0 F; 5>5 F; 3>5 F; 100>100 F → all 0
      check(bytesEq(callV128(inst, "i32x4_gt_s", V128(a), V128(b)), fromI32(0,0,0,0)),       "i32x4.gt_s")
      // gt_u: 0xFFFFFFFF>0 T; rest F
      check(bytesEq(callV128(inst, "i32x4_gt_u", V128(a), V128(b)), fromI32(-1,0,0,0)),      "i32x4.gt_u")
      // le_s: -1<=0 T; 5<=5 T; 3<=5 T; 100<=100 T → all T
      check(bytesEq(callV128(inst, "i32x4_le_s", V128(a), V128(b)), fromI32(-1,-1,-1,-1)),   "i32x4.le_s")
      // le_u: 0xFFFFFFFF<=0 F; rest T
      check(bytesEq(callV128(inst, "i32x4_le_u", V128(a), V128(b)), fromI32(0,-1,-1,-1)),    "i32x4.le_u")
      // ge_s: -1>=0 F; 5>=5 T; 3>=5 F; 100>=100 T
      check(bytesEq(callV128(inst, "i32x4_ge_s", V128(a), V128(b)), fromI32(0,-1,0,-1)),     "i32x4.ge_s")
      // ge_u: 0xFFFFFFFF>=0 T; 5>=5 T; 3>=5 F; 100>=100 T
      check(bytesEq(callV128(inst, "i32x4_ge_u", V128(a), V128(b)), fromI32(-1,-1,0,-1)),    "i32x4.ge_u")
    }

    // ----------------------------------------------------------------------
    // i64x2 — 6 ops (signed-only per spec)
    // ----------------------------------------------------------------------

    test("i64x2.eq / ne with negative + boundary lanes") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI64(Long.MinValue, -1L)
      val b    = fromI64(Long.MinValue, 0L)
      check(bytesEq(callV128(inst, "i64x2_eq", V128(a), V128(b)), fromI64(-1L, 0L)),  "i64x2.eq lane0 match")
      check(bytesEq(callV128(inst, "i64x2_ne", V128(a), V128(b)), fromI64(0L, -1L)),  "i64x2.ne lane1 mismatch")
    }

    test("i64x2.lt_s: Long.MinValue < Long.MaxValue (and reverse is false)") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI64(Long.MinValue, Long.MaxValue)
      val b    = fromI64(Long.MaxValue, Long.MinValue)
      check(bytesEq(callV128(inst, "i64x2_lt_s", V128(a), V128(b)), fromI64(-1L, 0L)),  "i64x2.lt_s")
      check(bytesEq(callV128(inst, "i64x2_gt_s", V128(a), V128(b)), fromI64(0L, -1L)),  "i64x2.gt_s")
    }

    test("i64x2.le_s / ge_s with equal lane") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromI64(-1L, 5L)
      val b    = fromI64(-1L, 5L)
      check(bytesEq(callV128(inst, "i64x2_le_s", V128(a), V128(b)), fromI64(-1L, -1L)), "i64x2.le_s all-true")
      check(bytesEq(callV128(inst, "i64x2_ge_s", V128(a), V128(b)), fromI64(-1L, -1L)), "i64x2.ge_s all-true")
    }

    // ----------------------------------------------------------------------
    // f32x4 — 6 ops
    // ----------------------------------------------------------------------

    test("f32x4 normal ordering: eq / ne / lt / gt / le / ge") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromF32(1.0f, 2.0f, 3.0f, 4.0f)
      val b    = fromF32(1.0f, 3.0f, 2.0f, 4.0f)
      // lane 0: equal → eq T, ne F, lt F, gt F, le T, ge T
      // lane 1: 2 < 3  → eq F, ne T, lt T, gt F, le T, ge F
      // lane 2: 3 > 2  → eq F, ne T, lt F, gt T, le F, ge T
      // lane 3: equal  → eq T, ne F, lt F, gt F, le T, ge T
      check(bytesEq(callV128(inst, "f32x4_eq", V128(a), V128(b)), fromI32(-1, 0, 0, -1)),   "f32x4.eq")
      check(bytesEq(callV128(inst, "f32x4_ne", V128(a), V128(b)), fromI32(0, -1, -1, 0)),   "f32x4.ne")
      check(bytesEq(callV128(inst, "f32x4_lt", V128(a), V128(b)), fromI32(0, -1, 0, 0)),    "f32x4.lt")
      check(bytesEq(callV128(inst, "f32x4_gt", V128(a), V128(b)), fromI32(0, 0, -1, 0)),    "f32x4.gt")
      check(bytesEq(callV128(inst, "f32x4_le", V128(a), V128(b)), fromI32(-1, -1, 0, -1)),  "f32x4.le")
      check(bytesEq(callV128(inst, "f32x4_ge", V128(a), V128(b)), fromI32(-1, 0, -1, -1)),  "f32x4.ge")
    }

    test("f32x4 NaN: only ne returns true on NaN operand") {
      val inst = instantiate(Fixtures.simd_compares)
      val nan  = Float.NaN
      // Same NaN in both operands across all four lanes — eq/lt/gt/le/ge all
      // false, ne all true. (IEEE-754: NaN is unordered with everything.)
      val a    = fromF32(nan, nan, 1.0f, nan)
      val b    = fromF32(nan, 1.0f, nan, nan)
      check(bytesEq(callV128(inst, "f32x4_eq", V128(a), V128(b)), fromI32(0, 0, 0, 0)),    "f32x4.eq NaN")
      check(bytesEq(callV128(inst, "f32x4_ne", V128(a), V128(b)), fromI32(-1, -1, -1, -1)),"f32x4.ne NaN")
      check(bytesEq(callV128(inst, "f32x4_lt", V128(a), V128(b)), fromI32(0, 0, 0, 0)),    "f32x4.lt NaN")
      check(bytesEq(callV128(inst, "f32x4_gt", V128(a), V128(b)), fromI32(0, 0, 0, 0)),    "f32x4.gt NaN")
      check(bytesEq(callV128(inst, "f32x4_le", V128(a), V128(b)), fromI32(0, 0, 0, 0)),    "f32x4.le NaN")
      check(bytesEq(callV128(inst, "f32x4_ge", V128(a), V128(b)), fromI32(0, 0, 0, 0)),    "f32x4.ge NaN")
    }

    test("f32x4 signed zero: -0.0 == +0.0 is true, but -0.0 < +0.0 is false") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromF32(-0.0f, -0.0f, -0.0f, -0.0f)
      val b    = fromF32( 0.0f,  0.0f,  0.0f,  0.0f)
      check(bytesEq(callV128(inst, "f32x4_eq", V128(a), V128(b)), fromI32(-1, -1, -1, -1)), "f32x4.eq -0/+0 = T")
      check(bytesEq(callV128(inst, "f32x4_lt", V128(a), V128(b)), fromI32(0, 0, 0, 0)),     "f32x4.lt -0/+0 = F")
      check(bytesEq(callV128(inst, "f32x4_le", V128(a), V128(b)), fromI32(-1, -1, -1, -1)), "f32x4.le -0/+0 = T")
    }

    // ----------------------------------------------------------------------
    // f64x2 — 6 ops
    // ----------------------------------------------------------------------

    test("f64x2 normal ordering: eq / ne / lt / gt / le / ge") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromF64(1.5, 7.0)
      val b    = fromF64(1.5, 3.0)
      // lane 0: equal  → eq T, ne F, lt F, gt F, le T, ge T
      // lane 1: 7 > 3  → eq F, ne T, lt F, gt T, le F, ge T
      check(bytesEq(callV128(inst, "f64x2_eq", V128(a), V128(b)), fromI64(-1L, 0L)),  "f64x2.eq")
      check(bytesEq(callV128(inst, "f64x2_ne", V128(a), V128(b)), fromI64(0L, -1L)),  "f64x2.ne")
      check(bytesEq(callV128(inst, "f64x2_lt", V128(a), V128(b)), fromI64(0L, 0L)),   "f64x2.lt")
      check(bytesEq(callV128(inst, "f64x2_gt", V128(a), V128(b)), fromI64(0L, -1L)),  "f64x2.gt")
      check(bytesEq(callV128(inst, "f64x2_le", V128(a), V128(b)), fromI64(-1L, 0L)),  "f64x2.le")
      check(bytesEq(callV128(inst, "f64x2_ge", V128(a), V128(b)), fromI64(-1L, -1L)), "f64x2.ge")
    }

    test("f64x2 NaN: only ne returns true on NaN operand") {
      val inst = instantiate(Fixtures.simd_compares)
      val nan  = Double.NaN
      val a    = fromF64(nan, 1.0)
      val b    = fromF64(nan, nan)
      check(bytesEq(callV128(inst, "f64x2_eq", V128(a), V128(b)), fromI64(0L, 0L)),    "f64x2.eq NaN")
      check(bytesEq(callV128(inst, "f64x2_ne", V128(a), V128(b)), fromI64(-1L, -1L)),  "f64x2.ne NaN")
      check(bytesEq(callV128(inst, "f64x2_lt", V128(a), V128(b)), fromI64(0L, 0L)),    "f64x2.lt NaN")
      check(bytesEq(callV128(inst, "f64x2_gt", V128(a), V128(b)), fromI64(0L, 0L)),    "f64x2.gt NaN")
      check(bytesEq(callV128(inst, "f64x2_le", V128(a), V128(b)), fromI64(0L, 0L)),    "f64x2.le NaN")
      check(bytesEq(callV128(inst, "f64x2_ge", V128(a), V128(b)), fromI64(0L, 0L)),    "f64x2.ge NaN")
    }

    test("f64x2 signed zero: -0.0 == +0.0, -0.0 < +0.0 is false") {
      val inst = instantiate(Fixtures.simd_compares)
      val a    = fromF64(-0.0, -0.0)
      val b    = fromF64( 0.0,  0.0)
      check(bytesEq(callV128(inst, "f64x2_eq", V128(a), V128(b)), fromI64(-1L, -1L)), "f64x2.eq -0/+0 = T")
      check(bytesEq(callV128(inst, "f64x2_lt", V128(a), V128(b)), fromI64(0L, 0L)),   "f64x2.lt -0/+0 = F")
      check(bytesEq(callV128(inst, "f64x2_le", V128(a), V128(b)), fromI64(-1L, -1L)), "f64x2.le -0/+0 = T")
    }
