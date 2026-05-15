package io.github.edadma.wasm

import TestSupport.*
import TestSupport.simd.{bytesEq, callV128, fromI16, fromI32}

/** Phase 8.E.I — SIMD dot product + load_lane / store_lane.
  *
  * The last code-bearing chunk in Phase 8.E. Three concerns:
  *
  *   - `i32x4.dot_i16x8_s` — pairwise multiply + add. The pinpoints are
  *     the adjacent-pair semantics (output lane k = `a[2k]*b[2k] +
  *     a[2k+1]*b[2k+1]`), the i16 sign-extension before multiplication,
  *     and the i32 pair-sum overflow wrap (both products at -32768² each
  *     = 2^31, which wraps to Int.MinValue per the spec).
  *
  *   - `v128.load{8,16,32,64}_lane` — read N bytes from memory and place
  *     them at lane `laneidx`, leaving the other lanes untouched. The
  *     pinpoints are: other lanes really are preserved, and lane index
  *     out of range fails at validate time (not exercised here — the
  *     validator unit suite covers that path).
  *
  *   - `v128.store{8,16,32,64}_lane` — write N bytes of one lane to
  *     memory. Pinpoints: only the lane bytes (not the whole vector)
  *     land in memory; the surrounding memory bytes stay untouched.
  *
  * Plus a round-trip test (store_lane → load_lane via memory) and an
  * out-of-bounds trap.
  */
object SimdDotLaneMemTests:

  private def b16(values: Int*): Array[Byte] =
    require(values.length == 16, s"b16 needs 16 values, got ${values.length}")
    values.iterator.map(_.toByte).toArray

  private def memOf(inst: ModuleInstance): Memory =
    runRight(inst.exportedMemory("mem"))

  def run(): Unit =

    // === dot_i16x8_s ============================================================

    test("i32x4.dot_i16x8_s: adjacent-pair semantics, b = ones") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      // a = [1, 2, 3, 4, 5, 6, 7, 8]   b = [1, 1, 1, 1, 1, 1, 1, 1]
      // expected = [1+2, 3+4, 5+6, 7+8] = [3, 7, 11, 15]
      val a = fromI16(1, 2, 3, 4, 5, 6, 7, 8)
      val b = fromI16(1, 1, 1, 1, 1, 1, 1, 1)
      val out = callV128(inst, "dot_i16x8_s", V128(a), V128(b))
      val expected = fromI32(3, 7, 11, 15)
      check(bytesEq(out, expected), s"dot ones: ${out.mkString(",")}")
    }

    test("i32x4.dot_i16x8_s: negative + sign-extension produces signed product") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      // a = [-1, 1, -2, 2, -3, 3, -4, 4]  b = [1, 1, 1, 1, 1, 1, 1, 1]
      // expected = [-1+1, -2+2, -3+3, -4+4] = [0, 0, 0, 0]
      val a = fromI16(-1, 1, -2, 2, -3, 3, -4, 4)
      val b = fromI16(1, 1, 1, 1, 1, 1, 1, 1)
      val out = callV128(inst, "dot_i16x8_s", V128(a), V128(b))
      val expected = fromI32(0, 0, 0, 0)
      check(bytesEq(out, expected), s"dot neg+pos cancels: ${out.mkString(",")}")
    }

    test("i32x4.dot_i16x8_s: -32768 * -32768 + -32768 * -32768 wraps to Int.MinValue") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      // -32768 * -32768 = 1073741824 = 2^30; two of them = 2^31, which wraps
      // to Int.MinValue under two's-complement i32 arithmetic.
      val a = fromI16(-32768, -32768, 0, 0, 0, 0, 0, 0)
      val b = fromI16(-32768, -32768, 0, 0, 0, 0, 0, 0)
      val out = callV128(inst, "dot_i16x8_s", V128(a), V128(b))
      val expected = fromI32(Int.MinValue, 0, 0, 0)
      check(bytesEq(out, expected), s"dot overflow wrap: ${out.mkString(",")}")
    }

    test("i32x4.dot_i16x8_s: max-positive lane products fit exact in i32") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      // 32767 * 32767 = 1073676289; pair-sum = 2147352578 (within i32 range).
      val a = fromI16(32767, 32767, 0, 0, 0, 0, 0, 0)
      val b = fromI16(32767, 32767, 0, 0, 0, 0, 0, 0)
      val out = callV128(inst, "dot_i16x8_s", V128(a), V128(b))
      val pair = 32767 * 32767 + 32767 * 32767                                  // 2_147_352_578
      val expected = fromI32(pair, 0, 0, 0)
      check(bytesEq(out, expected), s"dot max-positive: ${out.mkString(",")}")
    }

    // === load_lane =============================================================

    test("v128.load8_lane: lane 0 replaced, other 15 lanes preserved") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      val mem  = memOf(inst)
      mem.data(0) = 0xab.toByte

      val src = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                    0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x01)
      val out = callV128(inst, "load8_lane_0", I32(0), V128(src))
      val expected = b16(0xab, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                         0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x01)
      check(bytesEq(out, expected), s"load8_lane_0: ${out.mkString(",")}")
    }

    test("v128.load8_lane: lane 15 replaced, lanes 0..14 preserved") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      val mem  = memOf(inst)
      mem.data(100) = 0xcd.toByte

      val src = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                    0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x01)
      val out = callV128(inst, "load8_lane_15", I32(100), V128(src))
      val expected = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                         0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0xcd)
      check(bytesEq(out, expected), s"load8_lane_15: ${out.mkString(",")}")
    }

    test("v128.load16_lane: 2 bytes into lane 0 (LE), surrounding lanes preserved") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      val mem  = memOf(inst)
      mem.data(200) = 0xfe.toByte
      mem.data(201) = 0xca.toByte                                                // 0xCAFE LE

      val src = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                    0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x01)
      val out = callV128(inst, "load16_lane_0", I32(200), V128(src))
      val expected = b16(0xfe, 0xca, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                         0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x01)
      check(bytesEq(out, expected), s"load16_lane_0: ${out.mkString(",")}")
    }

    test("v128.load16_lane: 2 bytes into lane 7 (last i16 lane)") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      val mem  = memOf(inst)
      mem.data(300) = 0xef.toByte
      mem.data(301) = 0xbe.toByte                                                // 0xBEEF LE

      val src = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                    0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x01)
      val out = callV128(inst, "load16_lane_7", I32(300), V128(src))
      val expected = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                         0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xef, 0xbe)
      check(bytesEq(out, expected), s"load16_lane_7: ${out.mkString(",")}")
    }

    test("v128.load32_lane: 4 bytes into lane 3 (highest i32 lane)") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      val mem  = memOf(inst)
      mem.data(400) = 0xef.toByte
      mem.data(401) = 0xbe.toByte
      mem.data(402) = 0xad.toByte
      mem.data(403) = 0xde.toByte                                                // 0xDEADBEEF LE

      val src = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                    0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x01)
      val out = callV128(inst, "load32_lane_3", I32(400), V128(src))
      val expected = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                         0x90, 0xa0, 0xb0, 0xc0, 0xef, 0xbe, 0xad, 0xde)
      check(bytesEq(out, expected), s"load32_lane_3: ${out.mkString(",")}")
    }

    test("v128.load64_lane: 8 bytes into lane 1 (high i64 half)") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      val mem  = memOf(inst)
      val seed = Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
      var i = 0
      while i < 8 do { mem.data(500 + i) = seed(i); i += 1 }

      val src = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                    0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x01)
      val out = callV128(inst, "load64_lane_1", I32(500), V128(src))
      val expected = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                         0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
      check(bytesEq(out, expected), s"load64_lane_1: ${out.mkString(",")}")
    }

    // === store_lane ============================================================

    test("v128.store8_lane: only the lane's 1 byte lands in memory; neighbours stay 0") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      val mem  = memOf(inst)
      // Pre-fill so we can prove only 1 byte changed.
      mem.data(600) = 0x77.toByte
      mem.data(602) = 0x77.toByte

      val src = b16(0xab, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                    0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x01)
      runRight(inst.invoke("store8_lane_0", Seq(I32(601), V128(src))))
      check(mem.data(600) == 0x77.toByte, s"byte 600 changed: ${mem.data(600)}")
      check(mem.data(601) == 0xab.toByte, s"byte 601: ${mem.data(601)} != 0xab")
      check(mem.data(602) == 0x77.toByte, s"byte 602 changed: ${mem.data(602)}")
    }

    test("v128.store32_lane: writes 4 bytes from lane 3, neighbouring bytes untouched") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      val mem  = memOf(inst)
      mem.data(700) = 0x77.toByte
      mem.data(705) = 0x77.toByte

      val src = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                    0x90, 0xa0, 0xb0, 0xc0, 0xef, 0xbe, 0xad, 0xde)
      runRight(inst.invoke("store32_lane_3", Seq(I32(701), V128(src))))
      check(mem.data(700) == 0x77.toByte, s"byte 700 changed: ${mem.data(700)}")
      check(mem.data(701) == 0xef.toByte, s"byte 701: ${mem.data(701)}")
      check(mem.data(702) == 0xbe.toByte, s"byte 702: ${mem.data(702)}")
      check(mem.data(703) == 0xad.toByte, s"byte 703: ${mem.data(703)}")
      check(mem.data(704) == 0xde.toByte, s"byte 704: ${mem.data(704)}")
      check(mem.data(705) == 0x77.toByte, s"byte 705 changed: ${mem.data(705)}")
    }

    test("v128.store64_lane: writes 8 bytes from lane 1") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      val mem  = memOf(inst)

      val src = b16(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
      runRight(inst.invoke("store64_lane_1", Seq(I32(800), V128(src))))
      val actual = mem.data.slice(800, 808)
      val expected = Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
      check(bytesEq(actual, expected), s"store64_lane_1: ${actual.mkString(",")}")
    }

    // === round-trip: store_lane then load_at ===================================

    test("store_lane then v128.load: round-trip preserves the lane's bytes") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      // Zero the buffer first so unrelated lanes start at 0.
      val mem  = memOf(inst)
      var i = 0; while i < 16 do { mem.data(900 + i) = 0; i += 1 }

      // Write each lane of `src` separately via store32_lane into 4 distinct
      // 4-byte slots, then load the whole 16 bytes back and check.
      val src = b16(0xde, 0xad, 0xbe, 0xef, 0xca, 0xfe, 0xba, 0xbe,
                    0xfe, 0xed, 0xfa, 0xce, 0xba, 0xad, 0xf0, 0x0d)

      runRight(inst.invoke("store32_lane_0", Seq(I32(900),  V128(src))))
      runRight(inst.invoke("store32_lane_3", Seq(I32(912),  V128(src))))

      val readBack = callV128(inst, "load_at", I32(900))
      // Lanes 0 (bytes 0..3) and 3 (bytes 12..15) of src made it into the
      // first and last 4-byte windows; the middle 8 bytes stayed zero.
      val expected = b16(0xde, 0xad, 0xbe, 0xef, 0x00, 0x00, 0x00, 0x00,
                         0x00, 0x00, 0x00, 0x00, 0xba, 0xad, 0xf0, 0x0d)
      check(bytesEq(readBack, expected), s"round-trip: ${readBack.mkString(",")}")
    }

    // === out-of-bounds trap ====================================================

    test("v128.load8_lane at addr=65536 traps with MemoryOutOfBounds") {
      val inst = instantiate(Fixtures.simd_dot_lane_mem)
      val src  = b16(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      // 1 page = 65536 bytes; load_lane at 65536 reads 1 byte → OOB.
      expectError(inst, "load8_lane_oob", Seq(I32(65536), V128(src))) {
        case WasmError.MemoryOutOfBounds => true
        case _                           => false
      }
    }
