package io.github.edadma.wasm

import TestSupport.*
import TestSupport.simd.{bytesEq, callV128, fromI8, fromI16, fromI32, fromI64}

/** Phase 8.E.E — SIMD shifts + min/max.
  *
  * Per-shape coverage:
  *
  *   - i8x16 / i16x8 / i32x4: `shl`, `shr_s`, `shr_u`, plus `min_s`,
  *     `min_u`, `max_s`, `max_u` (4 ops × 3 shapes for min/max = 12).
  *   - i64x2: `shl`, `shr_s`, `shr_u` only — the spec excludes
  *     i64x2 min/max.
  *
  * Edge-case coverage:
  *   - Shift count `mod lane_width` — `i8x16.shl(_, 8)` is the identity,
  *     `i16x8.shl(_, 16)` is the identity, etc.
  *   - `shr_s` sign-extends before shifting: -1 byte stays -1 after
  *     any signed right shift.
  *   - `shr_u` zero-extends: byte 0xFF logical-shifted right by 1 is
  *     0x7F, not -1.
  *   - Signed vs unsigned ordering for min/max: -1 (signed) is the
  *     minimum but 0xFF...FF (unsigned) is the maximum; tests verify
  *     both interpretations on the same input bytes.
  */
object SimdShiftMinMaxTests:

  def run(): Unit =

    // === i8x16 shifts ===================================================

    test("i8x16.shl shifts left per lane; count taken mod 8") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI8(0x01, 0x02, 0x40, 0x7f, -1, 0x10, 0x55, -128, 0, 0, 0, 0, 0, 0, 0, 0)
      // shl by 1: each lane shifted left 1 bit, low bit truncated.
      val out1 = callV128(inst, "shl_i8x16", V128(a), I32(1))
      val want1 = fromI8(0x02, 0x04, -128.toByte & 0xff, -2 & 0xff,        // 0x80, 0xFE
                         -2 & 0xff, 0x20, -86 & 0xff, 0,
                         0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out1, want1), s"i8x16.shl by 1: ${out1.mkString(",")}")

      // shl by 8 == shl by 0 (mod 8), so identity.
      val out8 = callV128(inst, "shl_i8x16", V128(a), I32(8))
      check(bytesEq(out8, a), s"i8x16.shl by 8 (identity): ${out8.mkString(",")}")
    }

    test("i8x16.shr_s does arithmetic right shift per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      // -1 (0xFF) >> any = -1; -128 (0x80) >> 1 = -64 (0xC0); 0x40 >> 1 = 0x20.
      val a    = fromI8(-1, -128, 0x40, 0x7f, 0x01, -2, 0x10, 0x08, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "shr_s_i8x16", V128(a), I32(1))
      val want = fromI8(-1, -64, 0x20, 0x3f, 0x00, -1, 0x08, 0x04, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.shr_s by 1: ${out.mkString(",")}")
    }

    test("i8x16.shr_u does logical right shift per lane (zero-extends)") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      // 0xFF >>> 1 = 0x7F (NOT -1); 0x80 >>> 1 = 0x40; 0x40 >>> 1 = 0x20.
      val a    = fromI8(-1, -128, 0x40, 0x7f, 0x01, -2, 0x10, 0x08, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "shr_u_i8x16", V128(a), I32(1))
      val want = fromI8(0x7f, 0x40, 0x20, 0x3f, 0x00, 0x7f, 0x08, 0x04, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.shr_u by 1: ${out.mkString(",")}")
    }

    // === i8x16 min/max ==================================================

    test("i8x16.min_s picks the signed minimum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI8( 10, -10,  100,  -1, -128,  127, 50, -50, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8(  5,   5, -100,   1,  127, -128, 60, -60, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "min_s_i8x16", V128(a), V128(b))
      val want = fromI8(  5, -10, -100,  -1, -128, -128, 50, -60, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.min_s: ${out.mkString(",")}")
    }

    test("i8x16.min_u picks the unsigned minimum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      // -1 unsigned = 255 (max byte); 0x80 unsigned = 128.
      val a    = fromI8(-1, 0x10, 0x80,  0,  20, -2,  50, 100, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8( 0, 0x20,    1, -1,  10, -1, 200,   1, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "min_u_i8x16", V128(a), V128(b))
      // lane 0: 255 vs 0 → 0; lane 1: 16 vs 32 → 16; lane 2: 128 vs 1 → 1;
      // lane 3: 0 vs 255 → 0; lane 4: 20 vs 10 → 10; lane 5: 254 vs 255 → 254;
      // lane 6: 50 vs 200 → 50; lane 7: 100 vs 1 → 1.
      val want = fromI8( 0, 0x10,    1,  0,  10, -2,  50,   1, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.min_u: ${out.mkString(",")}")
    }

    test("i8x16.max_s picks the signed maximum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI8( 10, -10,  100,  -1, -128,  127, 50, -50, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8(  5,   5, -100,   1,  127, -128, 60, -60, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "max_s_i8x16", V128(a), V128(b))
      val want = fromI8( 10,   5,  100,   1,  127,  127, 60, -50, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.max_s: ${out.mkString(",")}")
    }

    test("i8x16.max_u picks the unsigned maximum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI8(-1, 0x10, 0x80,  0,  20, -2,  50, 100, 0, 0, 0, 0, 0, 0, 0, 0)
      val b    = fromI8( 0, 0x20,    1, -1,  10, -1, 200,   1, 0, 0, 0, 0, 0, 0, 0, 0)
      val out  = callV128(inst, "max_u_i8x16", V128(a), V128(b))
      // lane 0: 255 vs 0 → 255; lane 3: 0 vs 255 → 255; lane 5: 254 vs 255 → 255.
      val want = fromI8(-1, 0x20, 0x80, -1,  20, -1, 200.toByte, 100, 0, 0, 0, 0, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i8x16.max_u: ${out.mkString(",")}")
    }

    // === i16x8 shifts ===================================================

    test("i16x8.shl shifts left per lane; count taken mod 16") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI16(0x0001, 0x4000, 0x7fff, -1, 0x0080, 0x0040, 0x0020, 0)
      val out1 = callV128(inst, "shl_i16x8", V128(a), I32(1))
      // 0x4000 << 1 = 0x8000 (signed -32768); 0x7fff << 1 = 0xFFFE (-2);
      // -1 << 1 = 0xFFFE (-2); 0x0080 << 1 = 0x0100; etc.
      val want1 = fromI16(0x0002, 0x8000, 0xfffe, 0xfffe, 0x0100, 0x0080, 0x0040, 0)
      check(bytesEq(out1, want1), s"i16x8.shl by 1: ${out1.mkString(",")}")

      // shl by 16 == identity.
      val out16 = callV128(inst, "shl_i16x8", V128(a), I32(16))
      check(bytesEq(out16, a), s"i16x8.shl by 16 (identity): ${out16.mkString(",")}")
    }

    test("i16x8.shr_s does arithmetic right shift per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      // -1 (0xFFFF) >> 4 = -1; 0x8000 >> 1 = 0xC000 (sign-extended).
      val a    = fromI16(-1, 0x8000, 0x4000, 0x0010, 0, 0, 0, 0)
      val out  = callV128(inst, "shr_s_i16x8", V128(a), I32(1))
      val want = fromI16(-1, 0xc000, 0x2000, 0x0008, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i16x8.shr_s by 1: ${out.mkString(",")}")
    }

    test("i16x8.shr_u does logical right shift per lane (zero-extends)") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      // 0xFFFF >>> 1 = 0x7FFF; 0x8000 >>> 1 = 0x4000.
      val a    = fromI16(-1, 0x8000, 0x4000, 0x0010, 0, 0, 0, 0)
      val out  = callV128(inst, "shr_u_i16x8", V128(a), I32(1))
      val want = fromI16(0x7fff, 0x4000, 0x2000, 0x0008, 0, 0, 0, 0)
      check(bytesEq(out, want), s"i16x8.shr_u by 1: ${out.mkString(",")}")
    }

    // === i16x8 min/max ==================================================

    test("i16x8.min_s picks the signed minimum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI16(   100,  -100,   1000,  -1, -32768,  32767,    50,  -50)
      val b    = fromI16(    50,    50,  -1000,   1,  32767, -32768,    60,  -60)
      val out  = callV128(inst, "min_s_i16x8", V128(a), V128(b))
      val want = fromI16(    50,  -100,  -1000,  -1, -32768, -32768,    50,  -60)
      check(bytesEq(out, want), s"i16x8.min_s: ${out.mkString(",")}")
    }

    test("i16x8.min_u picks the unsigned minimum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      // -1 unsigned = 65535; 0x8000 unsigned = 32768.
      val a    = fromI16(-1, 0x1000, 0x8000,  0, 100, -2,  500, 1000)
      val b    = fromI16( 0, 0x2000,      1, -1,  50, -1, 2000,    1)
      val out  = callV128(inst, "min_u_i16x8", V128(a), V128(b))
      val want = fromI16( 0, 0x1000,      1,  0,  50, -2,  500,    1)
      check(bytesEq(out, want), s"i16x8.min_u: ${out.mkString(",")}")
    }

    test("i16x8.max_s picks the signed maximum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI16(   100,  -100,   1000,  -1, -32768,  32767,    50,  -50)
      val b    = fromI16(    50,    50,  -1000,   1,  32767, -32768,    60,  -60)
      val out  = callV128(inst, "max_s_i16x8", V128(a), V128(b))
      val want = fromI16(   100,    50,   1000,   1,  32767,  32767,    60,  -50)
      check(bytesEq(out, want), s"i16x8.max_s: ${out.mkString(",")}")
    }

    test("i16x8.max_u picks the unsigned maximum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI16(-1, 0x1000, 0x8000,  0, 100, -2,  500, 1000)
      val b    = fromI16( 0, 0x2000,      1, -1,  50, -1, 2000,    1)
      val out  = callV128(inst, "max_u_i16x8", V128(a), V128(b))
      val want = fromI16(-1, 0x2000, 0x8000, -1, 100, -1, 2000, 1000)
      check(bytesEq(out, want), s"i16x8.max_u: ${out.mkString(",")}")
    }

    // === i32x4 shifts ===================================================

    test("i32x4.shl shifts left per lane; count taken mod 32") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a     = fromI32(1, 0x40000000, -1, 0x12345678)
      val out1  = callV128(inst, "shl_i32x4", V128(a), I32(1))
      val want1 = fromI32(2, 0x80000000, -2, 0x2468acf0)
      check(bytesEq(out1, want1), s"i32x4.shl by 1: ${out1.mkString(",")}")

      // shl by 32 == identity.
      val out32 = callV128(inst, "shl_i32x4", V128(a), I32(32))
      check(bytesEq(out32, a), s"i32x4.shl by 32 (identity): ${out32.mkString(",")}")
    }

    test("i32x4.shr_s does arithmetic right shift per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI32(-1, 0x80000000, 0x40000000, 16)
      val out  = callV128(inst, "shr_s_i32x4", V128(a), I32(1))
      val want = fromI32(-1, 0xc0000000, 0x20000000, 8)
      check(bytesEq(out, want), s"i32x4.shr_s by 1: ${out.mkString(",")}")
    }

    test("i32x4.shr_u does logical right shift per lane (zero-extends)") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI32(-1, 0x80000000, 0x40000000, 16)
      val out  = callV128(inst, "shr_u_i32x4", V128(a), I32(1))
      val want = fromI32(0x7fffffff, 0x40000000, 0x20000000, 8)
      check(bytesEq(out, want), s"i32x4.shr_u by 1: ${out.mkString(",")}")
    }

    // === i32x4 min/max ==================================================

    test("i32x4.min_s picks the signed minimum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI32(   1000,  -1000, Int.MaxValue, Int.MinValue)
      val b    = fromI32(    500,    500, Int.MinValue, Int.MaxValue)
      val out  = callV128(inst, "min_s_i32x4", V128(a), V128(b))
      val want = fromI32(    500,  -1000, Int.MinValue, Int.MinValue)
      check(bytesEq(out, want), s"i32x4.min_s: ${out.mkString(",")}")
    }

    test("i32x4.min_u picks the unsigned minimum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      // -1 unsigned = 0xFFFFFFFF (max); Int.MinValue unsigned = 0x80000000.
      val a    = fromI32(-1,            5, Int.MinValue, 0)
      val b    = fromI32( 0,           -1,           -1, 7)
      val out  = callV128(inst, "min_u_i32x4", V128(a), V128(b))
      val want = fromI32( 0,            5, Int.MinValue, 0)
      check(bytesEq(out, want), s"i32x4.min_u: ${out.mkString(",")}")
    }

    test("i32x4.max_s picks the signed maximum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI32(   1000,  -1000, Int.MaxValue, Int.MinValue)
      val b    = fromI32(    500,    500, Int.MinValue, Int.MaxValue)
      val out  = callV128(inst, "max_s_i32x4", V128(a), V128(b))
      val want = fromI32(   1000,    500, Int.MaxValue, Int.MaxValue)
      check(bytesEq(out, want), s"i32x4.max_s: ${out.mkString(",")}")
    }

    test("i32x4.max_u picks the unsigned maximum per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI32(-1,            5, Int.MinValue, 0)
      val b    = fromI32( 0,           -1,           -1, 7)
      val out  = callV128(inst, "max_u_i32x4", V128(a), V128(b))
      val want = fromI32(-1,           -1,           -1, 7)
      check(bytesEq(out, want), s"i32x4.max_u: ${out.mkString(",")}")
    }

    // === i64x2 shifts (no min/max) ======================================

    test("i64x2.shl shifts left per lane; count taken mod 64") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a     = fromI64(1L, 0x4000000000000000L)
      val out1  = callV128(inst, "shl_i64x2", V128(a), I32(1))
      val want1 = fromI64(2L, 0x8000000000000000L)
      check(bytesEq(out1, want1), s"i64x2.shl by 1: ${out1.mkString(",")}")

      // shl by 64 == identity.
      val out64 = callV128(inst, "shl_i64x2", V128(a), I32(64))
      check(bytesEq(out64, a), s"i64x2.shl by 64 (identity): ${out64.mkString(",")}")
    }

    test("i64x2.shr_s does arithmetic right shift per lane") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI64(-1L, Long.MinValue)
      val out  = callV128(inst, "shr_s_i64x2", V128(a), I32(1))
      // -1 >> 1 = -1; MinValue (0x8000...0000) >> 1 = 0xC000...0000 = -2^62.
      val want = fromI64(-1L, 0xc000000000000000L)
      check(bytesEq(out, want), s"i64x2.shr_s by 1: ${out.mkString(",")}")
    }

    test("i64x2.shr_u does logical right shift per lane (zero-extends)") {
      val inst = instantiate(Fixtures.simd_shift_minmax)
      val a    = fromI64(-1L, Long.MinValue)
      val out  = callV128(inst, "shr_u_i64x2", V128(a), I32(1))
      // -1 >>> 1 = Long.MaxValue (0x7FFF...FFFF);
      // MinValue (0x8000...0000) >>> 1 = 0x4000...0000.
      val want = fromI64(Long.MaxValue, 0x4000000000000000L)
      check(bytesEq(out, want), s"i64x2.shr_u by 1: ${out.mkString(",")}")
    }
