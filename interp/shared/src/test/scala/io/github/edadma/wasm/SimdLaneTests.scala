package io.github.edadma.wasm

import TestSupport.*

/** Phase 8.E.C — SIMD lane access.
  *
  * Layers the lane-shape surface on top of Chunks A + B: every opcode
  * here either *interprets* the 16-byte payload in terms of a specific
  * lane shape (extract/replace/splat) or shuffles bytes across two
  * v128s (shuffle/swizzle).
  *
  *   - `*.splat` (6 ops, subs 0x0F..0x14) — broadcast a scalar.
  *   - `*.extract_lane` (8 ops, subs 0x15/0x16/0x18/0x19/0x1B/0x1D/0x1F/0x21).
  *     i8x16/i16x8 each split into `_s/_u`; the wider shapes have a
  *     single (signed-irrelevant) form.
  *   - `*.replace_lane` (6 ops, subs 0x17/0x1A/0x1C/0x1E/0x20/0x22).
  *   - `i8x16.shuffle` (sub 0x0D) — 16-byte laneidx immediate, picks
  *     bytes from two v128 sources.
  *   - `i8x16.swizzle` (sub 0x0E) — dynamic shuffle using a v128 of
  *     indices; out-of-range entries yield zero.
  *
  * The fixture exposes one export per `(op, lane)` pair the test cares
  * about; the test passes scalars + v128s in directly and reads byte
  * payloads back via `V128.bits`.
  */
object SimdLaneTests:

  /** Standalone byte-array comparison (kept symmetric with the other
    * SIMD test suites, whose `bytesEq` is private). */
  private def bytesEq(actual: Array[Byte], expected: Array[Byte]): Boolean =
    if actual.length != expected.length then false
    else
      var i  = 0
      var ok = true
      while ok && i < actual.length do
        if actual(i) != expected(i) then ok = false
        i += 1
      ok

  private def b16(values: Int*): Array[Byte] =
    require(values.length == 16, s"b16 needs 16 values, got ${values.length}")
    values.iterator.map(_.toByte).toArray

  /** Invoke a function returning v128; pull the bits out. */
  private def callV128(inst: ModuleInstance, name: String, args: Value*): Array[Byte] =
    val results = runRight(inst.invoke(name, args))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case V128(bs) => bs
      case other    => throw new AssertionError(s"$name returned $other, expected V128")

  def run(): Unit =

    // === splats ==========================================================

    test("i8x16.splat broadcasts the low byte of an i32 across all 16 lanes") {
      val inst = instantiate(Fixtures.simd_lane)
      // 0xCAFE_BABE truncates to byte 0xBE — that's the byte stamped out.
      val out  = callV128(inst, "splat_i8x16", I32(0xcafebabe))
      val want = Array.fill(16)(0xbe.toByte)
      check(bytesEq(out, want), s"i8x16.splat: ${out.mkString(",")}")
    }

    test("i16x8.splat broadcasts the low 16 bits LE across 8 lanes") {
      val inst = instantiate(Fixtures.simd_lane)
      val out  = callV128(inst, "splat_i16x8", I32(0xcafe))                       // LE: FE CA
      val want = b16(
        0xfe, 0xca, 0xfe, 0xca, 0xfe, 0xca, 0xfe, 0xca,
        0xfe, 0xca, 0xfe, 0xca, 0xfe, 0xca, 0xfe, 0xca,
      )
      check(bytesEq(out, want), s"i16x8.splat: ${out.mkString(",")}")
    }

    test("i32x4.splat broadcasts a 4-byte LE pattern across 4 lanes") {
      val inst = instantiate(Fixtures.simd_lane)
      val out  = callV128(inst, "splat_i32x4", I32(0xdeadbeef))                   // LE: EF BE AD DE
      val want = b16(
        0xef, 0xbe, 0xad, 0xde, 0xef, 0xbe, 0xad, 0xde,
        0xef, 0xbe, 0xad, 0xde, 0xef, 0xbe, 0xad, 0xde,
      )
      check(bytesEq(out, want), s"i32x4.splat: ${out.mkString(",")}")
    }

    test("i64x2.splat broadcasts an 8-byte LE pattern across 2 lanes") {
      val inst = instantiate(Fixtures.simd_lane)
      val out  = callV128(inst, "splat_i64x2", I64(0x0102030405060708L))          // LE: 08 07 06 05 04 03 02 01
      val want = b16(
        0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,
        0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,
      )
      check(bytesEq(out, want), s"i64x2.splat: ${out.mkString(",")}")
    }

    test("f32x4.splat broadcasts the raw IEEE-754 bit pattern across 4 lanes") {
      val inst = instantiate(Fixtures.simd_lane)
      // 1.5f → 0x3FC00000 → LE: 00 00 C0 3F.
      val out  = callV128(inst, "splat_f32x4", F32(1.5f))
      val want = b16(
        0x00, 0x00, 0xc0, 0x3f, 0x00, 0x00, 0xc0, 0x3f,
        0x00, 0x00, 0xc0, 0x3f, 0x00, 0x00, 0xc0, 0x3f,
      )
      check(bytesEq(out, want), s"f32x4.splat: ${out.mkString(",")}")
    }

    test("f64x2.splat broadcasts the raw IEEE-754 bit pattern across 2 lanes") {
      val inst = instantiate(Fixtures.simd_lane)
      // 1.5 → 0x3FF8000000000000 → LE: 00 00 00 00 00 00 F8 3F.
      val out  = callV128(inst, "splat_f64x2", F64(1.5))
      val want = b16(
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xf8, 0x3f,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xf8, 0x3f,
      )
      check(bytesEq(out, want), s"f64x2.splat: ${out.mkString(",")}")
    }

    // === extract_lane =====================================================
    //
    // Each block uses a single test V128 with deliberately-chosen lane
    // bytes that surface signed vs. unsigned widening at the boundary.

    test("i8x16.extract_lane_s sign-extends; _u zero-extends; lane bounds (0, 15)") {
      val inst = instantiate(Fixtures.simd_lane)
      // Lane 0 = 0x7F (max positive i8), lane 15 = 0x80 (min negative i8 = -128).
      val v = b16(
        0x7f, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70,
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x80,
      )

      check(callI32V(inst, "extract_i8x16_s_0",  V128(v)) == 0x7f,
            "extract_i8x16_s lane 0 = 127")
      check(callI32V(inst, "extract_i8x16_s_15", V128(v)) == -128,
            "extract_i8x16_s lane 15 = -128 (sign-extended)")
      check(callI32V(inst, "extract_i8x16_u_15", V128(v)) == 0x80,
            "extract_i8x16_u lane 15 = 0x80 (zero-extended)")
    }

    test("i16x8.extract_lane_s sign-extends; _u zero-extends; lane bounds (0, 7)") {
      val inst = instantiate(Fixtures.simd_lane)
      // Lane 0 LE = 0x1234, lane 7 LE = 0x8001 (= -32767 signed).
      val v = b16(
        0x34, 0x12, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x80,
      )

      check(callI32V(inst, "extract_i16x8_s_0", V128(v)) == 0x1234,
            "extract_i16x8_s lane 0 = 0x1234")
      check(callI32V(inst, "extract_i16x8_s_7", V128(v)) == -32767,
            "extract_i16x8_s lane 7 = -32767 (sign-extended)")
      check(callI32V(inst, "extract_i16x8_u_7", V128(v)) == 0x8001,
            "extract_i16x8_u lane 7 = 0x8001 (zero-extended)")
    }

    test("i32x4.extract_lane round-trips a 32-bit LE pattern at lane 0 and lane 3") {
      val inst = instantiate(Fixtures.simd_lane)
      // Lane 0 LE = 0xDEADBEEF, lane 3 LE = 0x12345678 — both negative when
      // interpreted as signed i32, exercising the high bit.
      val v = b16(
        0xef, 0xbe, 0xad, 0xde, 0x11, 0x22, 0x33, 0x44,
        0xaa, 0xbb, 0xcc, 0xdd, 0x78, 0x56, 0x34, 0x12,
      )

      check(callI32V(inst, "extract_i32x4_0", V128(v)) == 0xdeadbeef,
            s"extract_i32x4 lane 0 = ${callI32V(inst, "extract_i32x4_0", V128(v))}")
      check(callI32V(inst, "extract_i32x4_3", V128(v)) == 0x12345678,
            "extract_i32x4 lane 3 = 0x12345678")
    }

    test("i64x2.extract_lane round-trips a 64-bit LE pattern at both lanes") {
      val inst = instantiate(Fixtures.simd_lane)
      // Lane 0 LE = 0x0807060504030201, lane 1 LE = 0xFFFEFDFCFBFAF9F8.
      val v = b16(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0xf8, 0xf9, 0xfa, 0xfb, 0xfc, 0xfd, 0xfe, 0xff,
      )

      check(callI64(inst, "extract_i64x2_0", V128(v)) == 0x0807060504030201L,
            "extract_i64x2 lane 0")
      check(callI64(inst, "extract_i64x2_1", V128(v)) == 0xfffefdfcfbfaf9f8L,
            "extract_i64x2 lane 1")
    }

    test("f32x4.extract_lane recovers the float at lane 0 and lane 3") {
      val inst = instantiate(Fixtures.simd_lane)
      // Lane 0 → 1.0f (0x3F800000, LE 00 00 80 3F), lane 3 → -2.5f
      // (0xC0200000, LE 00 00 20 C0).
      val v = b16(
        0x00, 0x00, 0x80, 0x3f, 0, 0, 0, 0,
        0, 0, 0, 0, 0x00, 0x00, 0x20, 0xc0,
      )

      check(callF32(inst, "extract_f32x4_0", V128(v)) == 1.0f,
            "extract_f32x4 lane 0 = 1.0")
      check(callF32(inst, "extract_f32x4_3", V128(v)) == -2.5f,
            "extract_f32x4 lane 3 = -2.5")
    }

    test("f64x2.extract_lane recovers the double at both lanes") {
      val inst = instantiate(Fixtures.simd_lane)
      // Lane 0 → Math.PI (0x400921FB54442D18, LE: 18 2D 44 54 FB 21 09 40).
      // Lane 1 → -0.5 (0xBFE0000000000000, LE: 00 00 00 00 00 00 E0 BF).
      // Both bit patterns are exactly representable, so we get bit-exact
      // round-trip via the extract opcode.
      val v = b16(
        0x18, 0x2d, 0x44, 0x54, 0xfb, 0x21, 0x09, 0x40,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xe0, 0xbf,
      )

      check(callF64(inst, "extract_f64x2_0", V128(v)) == Math.PI,
            "extract_f64x2 lane 0 = Math.PI")
      check(callF64(inst, "extract_f64x2_1", V128(v)) == -0.5,
            "extract_f64x2 lane 1 = -0.5")
    }

    // === replace_lane =====================================================

    test("i8x16.replace_lane writes one byte at the chosen lane; others unchanged") {
      val inst = instantiate(Fixtures.simd_lane)
      val src  = b16(
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
      )
      val out  = callV128(inst, "replace_i8x16_5", V128(src), I32(0xcc))
      val want = src.clone
      want(5)  = 0xcc.toByte
      check(bytesEq(out, want), s"i8x16.replace_lane: ${out.mkString(",")}")
    }

    test("i16x8.replace_lane writes 2 bytes LE at lane 3") {
      val inst = instantiate(Fixtures.simd_lane)
      val src  = Array.fill[Byte](16)(0xaa.toByte)
      val out  = callV128(inst, "replace_i16x8_3", V128(src), I32(0x1234))
      val want = src.clone
      // Lane 3 = bytes 6..7 → LE 0x34 0x12.
      want(6) = 0x34.toByte
      want(7) = 0x12.toByte
      check(bytesEq(out, want), s"i16x8.replace_lane: ${out.mkString(",")}")
    }

    test("i32x4.replace_lane writes 4 bytes LE at lane 2") {
      val inst = instantiate(Fixtures.simd_lane)
      val src  = Array.fill[Byte](16)(0x55.toByte)
      val out  = callV128(inst, "replace_i32x4_2", V128(src), I32(0xdeadbeef))
      val want = src.clone
      // Lane 2 = bytes 8..11 → LE EF BE AD DE.
      want(8)  = 0xef.toByte
      want(9)  = 0xbe.toByte
      want(10) = 0xad.toByte
      want(11) = 0xde.toByte
      check(bytesEq(out, want), s"i32x4.replace_lane: ${out.mkString(",")}")
    }

    test("i64x2.replace_lane writes 8 bytes LE at lane 1") {
      val inst = instantiate(Fixtures.simd_lane)
      val src  = Array.fill[Byte](16)(0)
      val out  = callV128(inst, "replace_i64x2_1", V128(src), I64(0x0102030405060708L))
      val want = src.clone
      // Lane 1 = bytes 8..15 → LE 08 07 06 05 04 03 02 01.
      want(8)  = 0x08.toByte
      want(9)  = 0x07.toByte
      want(10) = 0x06.toByte
      want(11) = 0x05.toByte
      want(12) = 0x04.toByte
      want(13) = 0x03.toByte
      want(14) = 0x02.toByte
      want(15) = 0x01.toByte
      check(bytesEq(out, want), s"i64x2.replace_lane: ${out.mkString(",")}")
    }

    test("f32x4.replace_lane writes the IEEE-754 bit pattern at lane 2") {
      val inst = instantiate(Fixtures.simd_lane)
      val src  = Array.fill[Byte](16)(0)
      val out  = callV128(inst, "replace_f32x4_2", V128(src), F32(1.5f))
      val want = src.clone
      // 1.5f → 0x3FC00000 → LE 00 00 C0 3F into bytes 8..11.
      want(10) = 0xc0.toByte
      want(11) = 0x3f.toByte
      check(bytesEq(out, want), s"f32x4.replace_lane: ${out.mkString(",")}")
    }

    test("f64x2.replace_lane writes the IEEE-754 bit pattern at lane 0") {
      val inst = instantiate(Fixtures.simd_lane)
      val src  = Array.fill[Byte](16)(0xff.toByte)
      val out  = callV128(inst, "replace_f64x2_0", V128(src), F64(1.5))
      val want = src.clone
      // 1.5 → 0x3FF8000000000000 → LE 00 00 00 00 00 00 F8 3F into bytes 0..7.
      want(0) = 0x00.toByte
      want(1) = 0x00.toByte
      want(2) = 0x00.toByte
      want(3) = 0x00.toByte
      want(4) = 0x00.toByte
      want(5) = 0x00.toByte
      want(6) = 0xf8.toByte
      want(7) = 0x3f.toByte
      check(bytesEq(out, want), s"f64x2.replace_lane: ${out.mkString(",")}")
    }

    // === shuffle / swizzle ================================================

    test("i8x16.shuffle interleaves bytes from two v128 sources by immediate index") {
      val inst = instantiate(Fixtures.simd_lane)
      // a = 0x00..0x0F, b = 0x80..0x8F. Immediate is 0,16,1,17,...,7,23,
      // so the result is a[0],b[0],a[1],b[1],...,a[7],b[7] — the first
      // 8 bytes of each interleaved into a single 16-byte vector.
      val a = b16(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f)
      val b = b16(0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f)
      val out  = callV128(inst, "shuffle_interleave", V128(a), V128(b))
      val want = b16(
        0x00, 0x80, 0x01, 0x81, 0x02, 0x82, 0x03, 0x83,
        0x04, 0x84, 0x05, 0x85, 0x06, 0x86, 0x07, 0x87,
      )
      check(bytesEq(out, want), s"shuffle_interleave: ${out.mkString(",")}")
    }

    test("i8x16.shuffle with all-low indices picks from the first v128 (b is ignored)") {
      val inst = instantiate(Fixtures.simd_lane)
      // shuffle_reverse_a's immediate is 15,14,...,0 — all lanes from `a`.
      // Pass a recognisable `b` to confirm it isn't sampled.
      val a = b16(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f)
      val b = b16(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
      val out  = callV128(inst, "shuffle_reverse_a", V128(a), V128(b))
      val want = b16(0x1f, 0x1e, 0x1d, 0x1c, 0x1b, 0x1a, 0x19, 0x18, 0x17, 0x16, 0x15, 0x14, 0x13, 0x12, 0x11, 0x10)
      check(bytesEq(out, want), s"shuffle_reverse_a: ${out.mkString(",")}")
    }

    test("i8x16.swizzle picks v[s[i]] for s[i] < 16; emits 0 for s[i] >= 16") {
      val inst = instantiate(Fixtures.simd_lane)
      // v = 0xA0..0xAF, s = (4, 0, 7, 7, 200, 15, 16, 0xff, 0, 1, 2, 3, 4, 5, 6, 7).
      // Indices 200, 16, 0xff are all >= 16 → result byte is 0.
      val v = b16(0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf)
      val s = b16(4, 0, 7, 7, 200, 15, 16, 0xff, 0, 1, 2, 3, 4, 5, 6, 7)
      val out  = callV128(inst, "swizzle", V128(v), V128(s))
      val want = b16(
        0xa4, 0xa0, 0xa7, 0xa7, 0x00, 0xaf, 0x00, 0x00,
        0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
      )
      check(bytesEq(out, want), s"swizzle: ${out.mkString(",")}")
    }
