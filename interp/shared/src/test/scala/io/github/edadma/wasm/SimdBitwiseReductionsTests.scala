package io.github.edadma.wasm

import TestSupport.*
import TestSupport.simd.{bytesEq, callV128, fromI8, fromI16, fromI32, fromI64}

/** Phase 8.E.G.1 — SIMD bitwise + reductions.
  *
  * 15 ops in total:
  *
  *   - **Bitwise** (6 ops, shape-agnostic — all 16 bytes treated as raw):
  *     `v128.not`, `v128.and`, `v128.andnot`, `v128.or`, `v128.xor`,
  *     `v128.bitselect`. `bitselect` is the only SIMD ternary op:
  *     `(a AND c) OR (b AND NOT c)`, where `c` is the selector mask.
  *
  *   - **Reductions** (9 ops, v128 → i32):
  *     `v128.any_true` (any bit set anywhere → 1); shape-aware
  *     `i8x16/i16x8/i32x4/i64x2.all_true` (every lane non-zero → 1);
  *     shape-aware `i8x16/i16x8/i32x4/i64x2.bitmask` (top bit of each
  *     lane → bit position lane_index of the i32 result).
  *
  * Edge cases the tests pin:
  *
  *   - `bitselect` with all-1s mask returns `a`; all-0s mask returns `b`;
  *     per-byte mixed mask interleaves correctly.
  *   - `any_true` distinguishes only "any byte non-zero" — it does NOT
  *     respect lane shape (a single set bit anywhere → 1).
  *   - `all_true` is *shape-aware*: `[0xFF, 0xFF, 0x00, 0x01, ...]` is
  *     `i8x16.all_true = 0` (third byte is zero) but
  *     `i16x8.all_true = 1` (every 16-bit lane is non-zero, because the
  *     fourth byte in the lane has value 0x01).
  *   - `bitmask` indexes by lane number: lane 0 → bit 0 (LSB).
  *   - Alternating-MSB i8x16 yields `0b0101010101010101 = 0x5555`.
  */
object SimdBitwiseReductionsTests:

  def run(): Unit =

    // === bitwise ============================================================

    test("v128.not inverts every bit") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(0x00.toByte, 0xff.toByte, 0xaa.toByte, 0x55.toByte,
                        0x12.toByte, 0xed.toByte, 0x7f.toByte, 0x80.toByte,
                        0x01.toByte, 0xfe.toByte, 0x10.toByte, 0xef.toByte,
                        0x33.toByte, 0xcc.toByte, 0x42.toByte, 0xbd.toByte)
      val out  = callV128(inst, "not_v128", V128(a))
      val want = fromI8(0xff.toByte, 0x00.toByte, 0x55.toByte, 0xaa.toByte,
                        0xed.toByte, 0x12.toByte, 0x80.toByte, 0x7f.toByte,
                        0xfe.toByte, 0x01.toByte, 0xef.toByte, 0x10.toByte,
                        0xcc.toByte, 0x33.toByte, 0xbd.toByte, 0x42.toByte)
      check(bytesEq(out, want), "v128.not")
    }

    test("v128.and masks bytes per lane") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(Seq.fill(16)(0xff.toByte)*)
      val b    = fromI8(0x12.toByte, 0x34.toByte, 0x56.toByte, 0x78.toByte,
                        0x9a.toByte, 0xbc.toByte, 0xde.toByte, 0xf0.toByte,
                        0x00.toByte, 0xff.toByte, 0xaa.toByte, 0x55.toByte,
                        0x0f.toByte, 0xf0.toByte, 0x33.toByte, 0xcc.toByte)
      val out  = callV128(inst, "and_v128", V128(a), V128(b))
      check(bytesEq(out, b), "all-1s AND b = b")
    }

    test("v128.andnot is a AND (NOT b) — asymmetric") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(0xff.toByte, 0xff.toByte, 0x00.toByte, 0xaa.toByte,
                        0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte,
                        0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte,
                        0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte)
      val b    = fromI8(0x0f.toByte, 0xf0.toByte, 0xff.toByte, 0x55.toByte,
                        0x00.toByte, 0xff.toByte, 0x80.toByte, 0x01.toByte,
                        0xaa.toByte, 0x55.toByte, 0x12.toByte, 0xed.toByte,
                        0xc0.toByte, 0x03.toByte, 0x7e.toByte, 0xff.toByte)
      val out  = callV128(inst, "andnot_v128", V128(a), V128(b))
      // a=0xff & ~b: each byte should be (~b) & 0xff
      var ok = true
      var i  = 0
      while i < 16 do
        val expected = ((~b(i)) & 0xff & (a(i) & 0xff)).toByte
        if out(i) != expected then ok = false
        i += 1
      check(ok, "andnot per-byte mismatch")
    }

    test("v128.or sets the union of bits") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(0xaa.toByte, 0x00.toByte, 0xff.toByte, 0x0f.toByte,
                        0x12.toByte, 0x00.toByte, 0xff.toByte, 0x55.toByte,
                        0x33.toByte, 0xcc.toByte, 0x80.toByte, 0x01.toByte,
                        0xfe.toByte, 0x7f.toByte, 0x42.toByte, 0xbd.toByte)
      val b    = fromI8(0x55.toByte, 0xff.toByte, 0x00.toByte, 0xf0.toByte,
                        0xed.toByte, 0xff.toByte, 0x00.toByte, 0xaa.toByte,
                        0xcc.toByte, 0x33.toByte, 0x01.toByte, 0x80.toByte,
                        0x01.toByte, 0x80.toByte, 0xbd.toByte, 0x42.toByte)
      val out  = callV128(inst, "or_v128", V128(a), V128(b))
      var ok   = true
      var i    = 0
      while i < 16 do
        if out(i) != ((a(i) | b(i)) & 0xff).toByte then ok = false
        i += 1
      check(ok, "or per-byte mismatch")
    }

    test("v128.xor flips bits set in b") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte,
                        0x00.toByte, 0x00.toByte, 0x00.toByte, 0x00.toByte,
                        0xaa.toByte, 0xaa.toByte, 0xaa.toByte, 0xaa.toByte,
                        0x55.toByte, 0x55.toByte, 0x55.toByte, 0x55.toByte)
      val b    = fromI8(0x0f.toByte, 0xf0.toByte, 0xaa.toByte, 0x55.toByte,
                        0xff.toByte, 0x80.toByte, 0x01.toByte, 0x00.toByte,
                        0xff.toByte, 0x55.toByte, 0xaa.toByte, 0x00.toByte,
                        0xff.toByte, 0xaa.toByte, 0x55.toByte, 0x00.toByte)
      val out  = callV128(inst, "xor_v128", V128(a), V128(b))
      // a XOR a = 0; a XOR 0 = a — pin both via picks.
      check((out(7) & 0xff) == 0x00, s"0x00 XOR 0x00 = 0x00 lane 7 = ${(out(7) & 0xff).toHexString}")
      check((out(11) & 0xff) == 0xaa, s"0xaa XOR 0x00 = 0xaa lane 11 = ${(out(11) & 0xff).toHexString}")
      check((out(8) & 0xff) == 0x55, s"0xaa XOR 0xff = 0x55 lane 8 = ${(out(8) & 0xff).toHexString}")
    }

    test("v128.bitselect: mask=all-1s returns a") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(Seq.tabulate(16)(i => (i * 17 + 3).toByte)*)
      val b    = fromI8(Seq.tabulate(16)(i => (i * 7 + 11).toByte)*)
      val c    = fromI8(Seq.fill(16)(0xff.toByte)*)        // selector mask = all 1s
      val out  = callV128(inst, "bitselect_v128", V128(a), V128(b), V128(c))
      check(bytesEq(out, a), "bitselect with all-1s mask should return a")
    }

    test("v128.bitselect: mask=all-0s returns b") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(Seq.tabulate(16)(i => (i * 17 + 3).toByte)*)
      val b    = fromI8(Seq.tabulate(16)(i => (i * 7 + 11).toByte)*)
      val c    = fromI8(Seq.fill(16)(0x00.toByte)*)        // selector mask = all 0s
      val out  = callV128(inst, "bitselect_v128", V128(a), V128(b), V128(c))
      check(bytesEq(out, b), "bitselect with all-0s mask should return b")
    }

    test("v128.bitselect: per-byte mixed mask interleaves a and b") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(Seq.fill(16)(0xaa.toByte)*)
      val b    = fromI8(Seq.fill(16)(0x55.toByte)*)
      // alternate full-on / full-off mask bytes: lanes 0,2,4,...,14 = 0xff (pick a); lanes 1,3,...,15 = 0x00 (pick b).
      val c    = fromI8(Seq.tabulate(16)(i => if i % 2 == 0 then 0xff.toByte else 0x00.toByte)*)
      val out  = callV128(inst, "bitselect_v128", V128(a), V128(b), V128(c))
      var ok = true
      var i  = 0
      while i < 16 do
        val expected = if i % 2 == 0 then 0xaa.toByte else 0x55.toByte
        if out(i) != expected then ok = false
        i += 1
      check(ok, "bitselect per-byte mixed mask mismatch")
    }

    test("v128.bitselect: bit-level mask blends within bytes") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(Seq.fill(16)(0xff.toByte)*)
      val b    = fromI8(Seq.fill(16)(0x00.toByte)*)
      val c    = fromI8(Seq.fill(16)(0x0f.toByte)*)        // pick low 4 bits from a, high 4 bits from b
      val out  = callV128(inst, "bitselect_v128", V128(a), V128(b), V128(c))
      // (0xff AND 0x0f) OR (0x00 AND 0xf0) = 0x0f per byte.
      var ok = true
      var i  = 0
      while i < 16 do
        if (out(i) & 0xff) != 0x0f then ok = false
        i += 1
      check(ok, "bitselect bit-level blend should be 0x0f per byte")
    }

    // === reductions =========================================================

    test("v128.any_true: all-zero vector → 0") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(Seq.fill(16)(0x00.toByte)*)
      val out  = callI32V(inst, "any_true_v128", V128(a))
      check(out == 0, s"any_true(0) = $out")
    }

    test("v128.any_true: a single set bit → 1") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(Seq.tabulate(16)(i => if i == 7 then 0x01.toByte else 0x00.toByte)*)
      val out  = callI32V(inst, "any_true_v128", V128(a))
      check(out == 1, s"any_true(single bit) = $out")
    }

    test("i8x16.all_true: every byte non-zero → 1") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(Seq.tabulate(16)(i => (i + 1).toByte)*)        // 1..16, none zero
      val out  = callI32V(inst, "all_true_i8x16", V128(a))
      check(out == 1, s"all_true(1..16) = $out")
    }

    test("i8x16.all_true: one zero byte → 0") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(Seq.tabulate(16)(i => if i == 9 then 0x00.toByte else 0x42.toByte)*)
      val out  = callI32V(inst, "all_true_i8x16", V128(a))
      check(out == 0, s"all_true with zero at lane 9 = $out")
    }

    test("i16x8.all_true: shape-aware — lane with 0x00,0x01 is non-zero") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      // lane 3 has bytes (0x00, 0x01) → i16 value 0x0100 = 256 ≠ 0; whole vector should be all_true=1
      val a    = fromI16(0x0102.toShort, 0x0304.toShort, 0x0506.toShort, 0x0100.toShort,
                         0x0a0b.toShort, 0x0c0d.toShort, 0x0e0f.toShort, 0x1011.toShort)
      val out  = callI32V(inst, "all_true_i16x8", V128(a))
      check(out == 1, s"all_true should be 1 (no fully-zero i16 lane) = $out")
    }

    test("i16x8.all_true: zero i16 lane → 0") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI16(1.toShort, 2.toShort, 0.toShort, 4.toShort, 5.toShort, 6.toShort, 7.toShort, 8.toShort)
      val out  = callI32V(inst, "all_true_i16x8", V128(a))
      check(out == 0, s"all_true with zero i16 at lane 2 = $out")
    }

    test("i32x4.all_true: zero i32 lane → 0") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI32(1, 2, 0, 4)
      val out  = callI32V(inst, "all_true_i32x4", V128(a))
      check(out == 0, s"all_true with zero i32 at lane 2 = $out")
    }

    test("i64x2.all_true: both lanes non-zero → 1") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI64(0x100000000L, 0xff00000000L)        // both lanes use high bytes — must read 8 bytes
      val out  = callI32V(inst, "all_true_i64x2", V128(a))
      check(out == 1, s"all_true(two non-zero i64s) = $out")
    }

    test("i64x2.all_true: one zero lane → 0") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI64(0L, 0xdeadbeefL)
      val out  = callI32V(inst, "all_true_i64x2", V128(a))
      check(out == 0, s"all_true with zero i64 lane 0 = $out")
    }

    test("i8x16.bitmask: alternating MSB → 0x5555") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(Seq.tabulate(16)(i => if i % 2 == 0 then 0x80.toByte else 0x7f.toByte)*)
      val out  = callI32V(inst, "bitmask_i8x16", V128(a))
      check(out == 0x5555, s"bitmask = 0x${out.toHexString} (want 0x5555)")
    }

    test("i8x16.bitmask: all MSBs set → 0xFFFF") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI8(Seq.fill(16)(0x80.toByte)*)
      val out  = callI32V(inst, "bitmask_i8x16", V128(a))
      check(out == 0xffff, s"bitmask = 0x${out.toHexString} (want 0xffff)")
    }

    test("i16x8.bitmask: high byte's MSB defines each lane's bit") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      // i16 negative values have MSB set: -1, +1, -2, +2, -3, +3, -4, +4 → lanes 0,2,4,6 set → 0b01010101 = 0x55
      val a    = fromI16((-1).toShort, 1.toShort, (-2).toShort, 2.toShort,
                         (-3).toShort, 3.toShort, (-4).toShort, 4.toShort)
      val out  = callI32V(inst, "bitmask_i16x8", V128(a))
      check(out == 0x55, s"bitmask = 0x${out.toHexString} (want 0x55)")
    }

    test("i32x4.bitmask: signed-negative lanes set their bits") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI32(-1, 0, -2, 100)                // lanes 0 and 2 negative → bits 0 and 2 set → 0b0101 = 5
      val out  = callI32V(inst, "bitmask_i32x4", V128(a))
      check(out == 5, s"bitmask = $out (want 5)")
    }

    test("i64x2.bitmask: both lanes negative → 0b11 = 3") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI64(-1L, Long.MinValue)
      val out  = callI32V(inst, "bitmask_i64x2", V128(a))
      check(out == 3, s"bitmask = $out (want 3)")
    }

    test("i64x2.bitmask: positive lanes don't set any bit") {
      val inst = instantiate(Fixtures.simd_bitwise_reductions)
      val a    = fromI64(0x7fffffffffffffffL, 1L)
      val out  = callI32V(inst, "bitmask_i64x2", V128(a))
      check(out == 0, s"bitmask = $out (want 0)")
    }
