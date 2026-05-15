package io.github.edadma.wasm

import TestSupport.*
import TestSupport.simd.{bytesEq, callV128, fromI8, fromI16, fromI32, fromI64, fromF32, fromF64}

/** Phase 8.E.H — SIMD narrow / extend / extadd_pairwise / extmul +
  * float-int trunc_sat / convert + f32 ↔ f64 demote / promote.
  *
  * 42 ops total. Sub-opcode ranges:
  *
  *   - narrow (0x65/0x66/0x85/0x86) — 4 ops, v128×v128 → v128.
  *     Pack two i16x8 or i32x4 source vectors into one shorter-lane
  *     output, clamping each lane to the destination's signed or
  *     unsigned range.
  *   - extend (= widen) (0x87..0x8A, 0xA7..0xAA, 0xC7..0xCA) — 12 ops.
  *     Read low/high half of the source, sign- or zero-extend each
  *     lane into the wider lane width.
  *   - extadd_pairwise (0x7C..0x7F) — 4 ops. Pair adjacent narrower
  *     lanes, extend each, sum into one wider output lane.
  *   - extmul (0x9C..0x9F, 0xBC..0xBF, 0xDC..0xDF) — 12 ops. Like
  *     extend + multiply, fused at the wider width.
  *   - demote/promote (0x5E/0x5F) — 2 ops. f32x4 ↔ f64x2 with the
  *     `_zero` / `_low` half-fill semantics (only 2 lanes carry data).
  *   - trunc_sat / convert (0xF8..0xFF) — 8 ops. Float ↔ int with
  *     NaN→0 + range saturation; the f64x2 forms have the `_zero` /
  *     `_low` half-fill suffix.
  *
  * Edge cases the tests pin:
  *
  *   - Saturating narrow_s clamps Short(200) → 127 and Short(-200) → -128;
  *     narrow_u clamps Short(-1) → 0 (negative becomes 0 unsigned) and
  *     Short(300) → 255.
  *   - extend_s of 0xFF byte → -1 i16; extend_u of 0xFF → 255.
  *   - extend_high reads lanes N/2..N-1; extend_low reads 0..N/2-1.
  *   - extadd_pairwise: signed (-1, -1) bytes pair to i16 -2; unsigned
  *     (0xFF, 0xFF) bytes pair to i16 510.
  *   - extmul i8 -128 × -128 = 16384 (signed; fits exactly in i16).
  *   - trunc_sat NaN → 0 per lane; +Inf → INT_MAX; -Inf → INT_MIN.
  *   - convert_i32x4_u: -1 (signed Int) → 4294967295.0f (UInt32 max).
  *   - demote 1.5 → 1.5f, promote 1.5f → 1.5; both 2-lane operations.
  */
object SimdNarrowWidenConvTests:

  def run(): Unit =

    // ----------------------------------------------------------------------
    // narrow — 4 ops
    // ----------------------------------------------------------------------

    test("i8x16.narrow_i16x8_s: signed-clamp Short → Byte") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      // a's i16 lanes: representative over-/under-flow + in-range values
      val a    = fromI16(200, -200, 127, -128, 0, 1, -1, 32767)
      val b    = fromI16(-32768, 100, -100, 50, -50, 5, -5, 7)
      val out  = callV128(inst, "i8x16_narrow_i16x8_s", V128(a), V128(b))
      // Expected: bytes 0..7 from a's i16 lanes clamped to [-128,127];
      // bytes 8..15 same shape from b.
      val want = fromI8(127, -128, 127, -128, 0, 1, -1, 127,
                        -128, 100, -100, 50, -50, 5, -5, 7)
      check(bytesEq(out, want), "i8x16.narrow_i16x8_s mask mismatch")
    }

    test("i8x16.narrow_i16x8_u: unsigned-clamp Short → Byte (negative → 0)") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI16(300, -1, 255, 0, 256, 128, 127, -5)
      val b    = fromI16(1000, 200, -200, 100, 99, 50, 1, 0)
      val out  = callV128(inst, "i8x16_narrow_i16x8_u", V128(a), V128(b))
      // narrow_u: Short value clamped to [0, 255]. Negative → 0.
      val want = fromI8(-1 /* 255 */, 0, -1 /* 255 */, 0, -1 /* 255 */, -128 /* 128 */, 127, 0,
                        -1 /* 255 */, -56 /* 200 */, 0, 100, 99, 50, 1, 0)
      check(bytesEq(out, want), "i8x16.narrow_i16x8_u mask mismatch")
    }

    test("i16x8.narrow_i32x4_s / _u — Int → Short range saturation") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI32(70000, -70000, 32767, -32768)
      val b    = fromI32(100, -100, 0, Int.MaxValue)
      // narrow_s: clamp to [-32768, 32767]
      val outS = callV128(inst, "i16x8_narrow_i32x4_s", V128(a), V128(b))
      val wantS = fromI16(32767, -32768, 32767, -32768, 100, -100, 0, 32767)
      check(bytesEq(outS, wantS), "i16x8.narrow_i32x4_s mismatch")

      // narrow_u: clamp to [0, 65535]
      val outU = callV128(inst, "i16x8_narrow_i32x4_u", V128(a), V128(b))
      // 70000 → 65535; -70000 → 0; 32767 → 32767; -32768 → 0;
      // 100 → 100; -100 → 0; 0 → 0; Int.MaxValue → 65535
      val wantU = fromI16(-1 /* 65535 */, 0, 32767, 0,
                          100, 0, 0, -1 /* 65535 */)
      check(bytesEq(outU, wantU), "i16x8.narrow_i32x4_u mismatch")
    }

    // ----------------------------------------------------------------------
    // extend (widen) — 12 ops
    // ----------------------------------------------------------------------

    test("i16x8.extend_low / high_i8x16_s: sign-extend 8-byte halves to i16") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI8(-1, -2, 127, -128, 0, 1, 2, 3, /* high */ 100, -100, 50, -50, 0, 0, 0, 0)
      val outL = callV128(inst, "i16x8_extend_low_i8x16_s", V128(a))
      val outH = callV128(inst, "i16x8_extend_high_i8x16_s", V128(a))
      // low: bytes 0..7 → i16 lanes 0..7 (signed)
      check(bytesEq(outL, fromI16(-1, -2, 127, -128, 0, 1, 2, 3)), "extend_low_s mismatch")
      // high: bytes 8..15 → i16 lanes 0..7 (signed)
      check(bytesEq(outH, fromI16(100, -100, 50, -50, 0, 0, 0, 0)), "extend_high_s mismatch")
    }

    test("i16x8.extend_low / high_i8x16_u: zero-extend 8-byte halves to i16") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI8(-1, -2, 127, -128, 0, 1, 2, 3, /* high */ -1, 0, -128, 5, 0, 0, 0, 0)
      val outL = callV128(inst, "i16x8_extend_low_i8x16_u", V128(a))
      val outH = callV128(inst, "i16x8_extend_high_i8x16_u", V128(a))
      // low_u: 0xFF/0xFE/127/0x80/0/1/2/3 → 255/254/127/128/0/1/2/3
      check(bytesEq(outL, fromI16(255, 254, 127, 128, 0, 1, 2, 3)), "extend_low_u mismatch")
      // high_u: 0xFF/0/0x80/5/0/0/0/0 → 255/0/128/5/0/0/0/0
      check(bytesEq(outH, fromI16(255, 0, 128, 5, 0, 0, 0, 0)), "extend_high_u mismatch")
    }

    test("i32x4.extend_low / high_i16x8_s / _u") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      // Lanes 0..3 (low half): -1, -32768, 100, 32767
      // Lanes 4..7 (high half): -2, 5, 0xFFFF (signed: -1), 32766
      val a    = fromI16(-1, -32768, 100, 32767, -2, 5, -1, 32766)
      check(bytesEq(callV128(inst, "i32x4_extend_low_i16x8_s",  V128(a)), fromI32(-1, -32768, 100, 32767)), "extend_low_s")
      check(bytesEq(callV128(inst, "i32x4_extend_high_i16x8_s", V128(a)), fromI32(-2, 5, -1, 32766)),       "extend_high_s")
      // _u: 0xFFFF → 65535, 0x8000 → 32768
      check(bytesEq(callV128(inst, "i32x4_extend_low_i16x8_u",  V128(a)), fromI32(65535, 32768, 100, 32767)),  "extend_low_u")
      check(bytesEq(callV128(inst, "i32x4_extend_high_i16x8_u", V128(a)), fromI32(65534, 5, 65535, 32766)),    "extend_high_u")
    }

    test("i64x2.extend_low / high_i32x4_s / _u") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI32(-1, Int.MinValue, 100, Int.MaxValue)
      check(bytesEq(callV128(inst, "i64x2_extend_low_i32x4_s",  V128(a)), fromI64(-1L, Int.MinValue.toLong)),  "extend_low_s64")
      check(bytesEq(callV128(inst, "i64x2_extend_high_i32x4_s", V128(a)), fromI64(100L, Int.MaxValue.toLong)), "extend_high_s64")
      // _u zero-extends: -1 → 0xFFFFFFFF = 4294967295L; Int.MinValue → 0x80000000 = 2147483648L
      check(bytesEq(callV128(inst, "i64x2_extend_low_i32x4_u",  V128(a)), fromI64(0xFFFFFFFFL, 0x80000000L)),  "extend_low_u64")
      check(bytesEq(callV128(inst, "i64x2_extend_high_i32x4_u", V128(a)), fromI64(100L, Int.MaxValue.toLong)), "extend_high_u64")
    }

    // ----------------------------------------------------------------------
    // extadd_pairwise — 4 ops
    // ----------------------------------------------------------------------

    test("i16x8.extadd_pairwise_i8x16_s / _u: pairwise sum-with-extension") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      // 16 bytes pair (0,1) (2,3) (4,5) ... into 8 i16 lanes.
      val a    = fromI8(-1, -1, 100, 27, 127, 127, -128, -128, 1, 2, 3, 4, 5, 6, 7, 8)
      // signed pairs: (-1)+(-1) = -2; 100+27 = 127; 127+127 = 254; (-128)+(-128) = -256; 1+2=3; 3+4=7; 5+6=11; 7+8=15
      check(bytesEq(callV128(inst, "i16x8_extadd_pairwise_i8x16_s", V128(a)),
                    fromI16(-2, 127, 254, -256, 3, 7, 11, 15)), "extadd_pairwise_s8")
      // unsigned pairs: 0xFF+0xFF = 510; 100+27 = 127; 127+127 = 254; 0x80+0x80 = 256; 1+2=3; 3+4=7; 5+6=11; 7+8=15
      check(bytesEq(callV128(inst, "i16x8_extadd_pairwise_i8x16_u", V128(a)),
                    fromI16(510, 127, 254, 256, 3, 7, 11, 15)), "extadd_pairwise_u8")
    }

    test("i32x4.extadd_pairwise_i16x8_s / _u") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI16(-1, -1, 32767, 32767, -32768, -32768, 1, 2)
      // signed: -2; 65534; -65536; 3
      check(bytesEq(callV128(inst, "i32x4_extadd_pairwise_i16x8_s", V128(a)),
                    fromI32(-2, 65534, -65536, 3)), "extadd_pairwise_s16")
      // unsigned: 0xFFFF+0xFFFF = 131070; 32767+32767 = 65534; 0x8000+0x8000 = 65536; 3
      check(bytesEq(callV128(inst, "i32x4_extadd_pairwise_i16x8_u", V128(a)),
                    fromI32(131070, 65534, 65536, 3)), "extadd_pairwise_u16")
    }

    // ----------------------------------------------------------------------
    // extmul — 12 ops
    // ----------------------------------------------------------------------

    test("i16x8.extmul_low / high_i8x16_s: -128 * -128 = 16384 (fits in i16)") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI8(-128, 100, -1, 7, 0, 0, 0, 0,  /* high */ 50, -50, 127, -128, 0, 0, 0, 0)
      val b    = fromI8(-128, 2,   -1, 5, 0, 0, 0, 0,  /* high */ 2,  3,   2,    -1,  0, 0, 0, 0)
      // low signed: -128*-128=16384; 100*2=200; -1*-1=1; 7*5=35; 0×0 for lanes 4..7
      check(bytesEq(callV128(inst, "i16x8_extmul_low_i8x16_s",  V128(a), V128(b)),
                    fromI16(16384, 200, 1, 35, 0, 0, 0, 0)), "extmul_low_s8")
      // high signed: 50*2=100; -50*3=-150; 127*2=254; -128*-1=128
      check(bytesEq(callV128(inst, "i16x8_extmul_high_i8x16_s", V128(a), V128(b)),
                    fromI16(100, -150, 254, 128, 0, 0, 0, 0)), "extmul_high_s8")
    }

    test("i16x8.extmul_low / high_i8x16_u: 200 * 200 = 40000 (unsigned, exact)") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI8(-56 /* 200 */, -1, 0, 5, 0, 0, 0, 0,  /* high */ -2, 100, 0, 0, 0, 0, 0, 0)
      val b    = fromI8(-56 /* 200 */, -1, 7, 7, 0, 0, 0, 0,  /* high */ -1, 50, 0, 0, 0, 0, 0, 0)
      // low unsigned: 200*200=40000; 255*255=65025; 0*7=0; 5*7=35
      check(bytesEq(callV128(inst, "i16x8_extmul_low_i8x16_u",  V128(a), V128(b)),
                    fromI16(40000, -511 /* 65025 */, 0, 35, 0, 0, 0, 0)), "extmul_low_u8")
      // high unsigned: 254*255=64770; 100*50=5000
      check(bytesEq(callV128(inst, "i16x8_extmul_high_i8x16_u", V128(a), V128(b)),
                    fromI16(-766 /* 64770 */, 5000, 0, 0, 0, 0, 0, 0)), "extmul_high_u8")
    }

    test("i32x4.extmul_low / high_i16x8_s / _u") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI16(-32768, 100, -1, 7, /* high */ 32767, -50, 0, 5)
      val b    = fromI16(-32768, 2,   -1, 5, /* high */ 2,     -3,  9, 7)
      // low signed: -32768*-32768=1073741824; 100*2=200; -1*-1=1; 7*5=35
      check(bytesEq(callV128(inst, "i32x4_extmul_low_i16x8_s",  V128(a), V128(b)),
                    fromI32(1073741824, 200, 1, 35)), "extmul_low_s16")
      // high signed: 32767*2=65534; -50*-3=150; 0*9=0; 5*7=35
      check(bytesEq(callV128(inst, "i32x4_extmul_high_i16x8_s", V128(a), V128(b)),
                    fromI32(65534, 150, 0, 35)), "extmul_high_s16")
      // low unsigned: 0x8000*0x8000=1073741824 (positive Int — exact); 100*2=200; 0xFFFF*0xFFFF=4294836225; 35
      check(bytesEq(callV128(inst, "i32x4_extmul_low_i16x8_u",  V128(a), V128(b)),
                    fromI32(1073741824, 200, -131071 /* 4294836225 */, 35)), "extmul_low_u16")
      // high unsigned: 32767*2=65534; 0xFFCE*0xFFFD = 65486*65533 = 4292481638
      check(bytesEq(callV128(inst, "i32x4_extmul_high_i16x8_u", V128(a), V128(b)),
                    fromI32(65534, (65486L * 65533L).toInt, 0, 35)), "extmul_high_u16")
    }

    test("i64x2.extmul_low / high_i32x4_s / _u: full i64 product is exact") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI32(100000, -100000, /* high */ 50000, Int.MaxValue)
      val b    = fromI32(100000, 100000,  /* high */ 50000, 2)
      // low signed: 10^10; -10^10
      check(bytesEq(callV128(inst, "i64x2_extmul_low_i32x4_s",  V128(a), V128(b)),
                    fromI64(10000000000L, -10000000000L)), "extmul_low_s32")
      // high signed: 2.5*10^9; Int.MaxValue * 2 = 4294967294
      check(bytesEq(callV128(inst, "i64x2_extmul_high_i32x4_s", V128(a), V128(b)),
                    fromI64(2500000000L, Int.MaxValue.toLong * 2L)), "extmul_high_s32")
      // low unsigned: 100000 * 100000 = 10^10 (positive); (-100000 unsigned = 4294867296) * 100000 = 429486729600000
      val unsignedNeg100000 = (-100000).toLong & 0xffffffffL                     // 4294867296L
      check(bytesEq(callV128(inst, "i64x2_extmul_low_i32x4_u",  V128(a), V128(b)),
                    fromI64(10000000000L, unsignedNeg100000 * 100000L)), "extmul_low_u32")
      // high unsigned: 50000 * 50000 = 2.5e9; Int.MaxValue (unsigned = 2147483647) * 2 = 4294967294
      check(bytesEq(callV128(inst, "i64x2_extmul_high_i32x4_u", V128(a), V128(b)),
                    fromI64(2500000000L, Int.MaxValue.toLong * 2L)), "extmul_high_u32")
    }

    // ----------------------------------------------------------------------
    // demote / promote — 2 ops
    // ----------------------------------------------------------------------

    test("f32x4.demote_f64x2_zero / f64x2.promote_low_f32x4 round-trip") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      // demote: 2 f64 → f32 lanes 0..1; lanes 2 + 3 zero-filled
      val a    = fromF64(1.5, -2.5)
      val d    = callV128(inst, "f32x4_demote_f64x2_zero", V128(a))
      check(bytesEq(d, fromF32(1.5f, -2.5f, 0.0f, 0.0f)), "demote should pin lanes 2+3 to 0")

      // promote: f32 lanes 0..1 of source → f64 lanes 0..1
      val src  = fromF32(1.5f, -2.5f, 99.0f, 100.0f)        // upper lanes ignored
      val p    = callV128(inst, "f64x2_promote_low_f32x4", V128(src))
      check(bytesEq(p, fromF64(1.5, -2.5)), "promote should read low half only")
    }

    // ----------------------------------------------------------------------
    // trunc_sat — 4 ops
    // ----------------------------------------------------------------------

    test("i32x4.trunc_sat_f32x4_s: NaN → 0; overflow → INT_MIN / INT_MAX") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromF32(Float.NaN, 1e10f, -1e10f, 3.7f)
      val out  = callV128(inst, "i32x4_trunc_sat_f32x4_s", V128(a))
      check(bytesEq(out, fromI32(0, Int.MaxValue, Int.MinValue, 3)), "trunc_sat_f32x4_s")
    }

    test("i32x4.trunc_sat_f32x4_u: NaN/negative → 0; overflow → 0xFFFFFFFF") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromF32(Float.NaN, 1e10f, -1e10f, 4294967295.0f)
      val out  = callV128(inst, "i32x4_trunc_sat_f32x4_u", V128(a))
      // UInt32 max as signed Int is -1.
      check(bytesEq(out, fromI32(0, -1, 0, -1)), "trunc_sat_f32x4_u")
    }

    test("i32x4.trunc_sat_f64x2_s_zero / _u_zero: 2-lane → 4-lane with zeros") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromF64(Double.NaN, 1e15)        // overflow into i32 range
      // _s: NaN → 0; 1e15 → INT_MAX; lanes 2 + 3 zero-filled
      check(bytesEq(callV128(inst, "i32x4_trunc_sat_f64x2_s_zero", V128(a)),
                    fromI32(0, Int.MaxValue, 0, 0)), "trunc_sat_f64x2_s_zero")
      // _u: NaN → 0; 1e15 → 0xFFFFFFFF
      check(bytesEq(callV128(inst, "i32x4_trunc_sat_f64x2_u_zero", V128(a)),
                    fromI32(0, -1, 0, 0)), "trunc_sat_f64x2_u_zero")
    }

    // ----------------------------------------------------------------------
    // convert — 4 ops
    // ----------------------------------------------------------------------

    test("f32x4.convert_i32x4_s / _u: -1 signed = -1.0f vs unsigned = 4294967295.0f") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI32(-1, 0, 1, Int.MinValue)
      check(bytesEq(callV128(inst, "f32x4_convert_i32x4_s", V128(a)),
                    fromF32(-1.0f, 0.0f, 1.0f, Int.MinValue.toFloat)), "convert_i32x4_s")
      // _u: -1 as UInt32 is 4294967295 → 4294967295.0f (rounds to the nearest f32);
      // Int.MinValue as UInt32 = 2147483648 → 2147483648.0f
      check(bytesEq(callV128(inst, "f32x4_convert_i32x4_u", V128(a)),
                    fromF32(4294967295.0f, 0.0f, 1.0f, 2147483648.0f)), "convert_i32x4_u")
    }

    test("f64x2.convert_low_i32x4_s / _u: reads only lanes 0..1") {
      val inst = instantiate(Fixtures.simd_narrow_widen_conv)
      val a    = fromI32(-1, Int.MaxValue, 9999, 9999)            // upper two ignored
      check(bytesEq(callV128(inst, "f64x2_convert_low_i32x4_s", V128(a)),
                    fromF64(-1.0, Int.MaxValue.toDouble)), "convert_low_i32x4_s")
      check(bytesEq(callV128(inst, "f64x2_convert_low_i32x4_u", V128(a)),
                    fromF64(4294967295.0, Int.MaxValue.toDouble)), "convert_low_i32x4_u")
    }
