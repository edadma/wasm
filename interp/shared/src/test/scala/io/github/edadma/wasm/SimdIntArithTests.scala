package io.github.edadma.wasm

import TestSupport.*

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

  private def bytesEq(actual: Array[Byte], expected: Array[Byte]): Boolean =
    if actual.length != expected.length then false
    else
      var i  = 0
      var ok = true
      while ok && i < actual.length do
        if actual(i) != expected(i) then ok = false
        i += 1
      ok

  /** Invoke a function returning v128 and pull its raw 16 bytes. */
  private def callV128(inst: ModuleInstance, name: String, args: Value*): Array[Byte] =
    val results = runRight(inst.invoke(name, args))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case V128(bs) => bs
      case other    => throw new AssertionError(s"$name returned $other, expected V128")

  // === LE byte-array builders for each lane shape =========================
  //
  // Each takes lane-count values (16 / 8 / 4 / 2) and emits the 16-byte
  // V128 payload in little-endian order. `i8` truncates each value to a
  // byte; `i16` writes two bytes per lane; etc. These mirror the
  // interpreter's lane-write semantics so test setup reads naturally.

  private def fromI8(values: Int*): Array[Byte] =
    require(values.length == 16, s"fromI8 needs 16 values, got ${values.length}")
    values.iterator.map(_.toByte).toArray

  private def fromI16(values: Int*): Array[Byte] =
    require(values.length == 8, s"fromI16 needs 8 values, got ${values.length}")
    val r = new Array[Byte](16)
    var i = 0
    while i < 8 do
      r(i * 2)     = (values(i) & 0xff).toByte
      r(i * 2 + 1) = ((values(i) >>> 8) & 0xff).toByte
      i += 1
    r

  private def fromI32(values: Int*): Array[Byte] =
    require(values.length == 4, s"fromI32 needs 4 values, got ${values.length}")
    val r = new Array[Byte](16)
    var i = 0
    while i < 4 do
      val v = values(i)
      r(i * 4)     = (v & 0xff).toByte
      r(i * 4 + 1) = ((v >>> 8)  & 0xff).toByte
      r(i * 4 + 2) = ((v >>> 16) & 0xff).toByte
      r(i * 4 + 3) = ((v >>> 24) & 0xff).toByte
      i += 1
    r

  private def fromI64(values: Long*): Array[Byte] =
    require(values.length == 2, s"fromI64 needs 2 values, got ${values.length}")
    val r = new Array[Byte](16)
    var i = 0
    while i < 2 do
      val v = values(i)
      var k = 0
      while k < 8 do
        r(i * 8 + k) = ((v >>> (k * 8)) & 0xffL).toByte
        k += 1
      i += 1
    r

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
