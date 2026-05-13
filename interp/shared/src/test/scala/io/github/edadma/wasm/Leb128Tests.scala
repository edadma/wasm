package io.github.edadma.wasm

import TestSupport.*

/** Direct unit tests for the LEB128 encoder/decoder.
  *
  * Exercises every reader (`readU32`, `readS32`, `readS64`) on canonical
  * values, sign-extension edges, oversized inputs, truncated continuations,
  * and explicit-offset reads. Independent of the WASM binary format — these
  * tests live here so a regression in LEB128 surfaces with a focused
  * failure rather than dozens of section-parse cascades.
  */
object Leb128Tests:

  def run(): Unit =

    def assertU32(bs: Array[Byte], v: Int, p: Int): Unit =
      Leb128.readU32(bs, 0) match
        case Right((vv, pp)) =>
          check(vv == v, s"value: expected $v, got $vv")
          check(pp == p, s"newPos: expected $p, got $pp")
        case Left(e) => check(false, s"expected Right, got Left($e)")

    def assertU32Fails(bs: Array[Byte]): Unit =
      Leb128.readU32(bs, 0) match
        case Right(v) => check(false, s"expected Left, got Right($v)")
        case Left(_)  => ()

    def assertS32(bs: Array[Byte], v: Int, p: Int): Unit =
      Leb128.readS32(bs, 0) match
        case Right((vv, pp)) =>
          check(vv == v, s"value: expected $v, got $vv")
          check(pp == p, s"newPos: expected $p, got $pp")
        case Left(e) => check(false, s"expected Right, got Left($e)")

    def assertS32Fails(bs: Array[Byte]): Unit =
      Leb128.readS32(bs, 0) match
        case Right(v) => check(false, s"expected Left, got Right($v)")
        case Left(_)  => ()

    test("Leb128.readU32: 0 from a single 0x00 byte") {
      assertU32(b(0x00), 0, 1)
    }
    test("Leb128.readU32: 127 from a single 0x7F byte (largest single-byte value)") {
      assertU32(b(0x7f), 127, 1)
    }
    test("Leb128.readU32: 128 from two-byte encoding 0x80 0x01") {
      assertU32(b(0x80, 0x01), 128, 2)
    }
    test("Leb128.readU32: 624485 from canonical three-byte example") {
      assertU32(b(0xe5, 0x8e, 0x26), 624485, 3)
    }
    test("Leb128.readU32: padded zero continuation (non-canonical zero)") {
      assertU32(b(0x80, 0x00), 0, 2)
    }
    test("Leb128.readU32: empty input fails with InvalidModule") {
      assertU32Fails(b())
    }
    test("Leb128.readU32: continuation bit set but no follow-on byte fails") {
      assertU32Fails(b(0x80))
    }
    test("Leb128.readU32: six-byte (oversized) input fails") {
      assertU32Fails(b(0x80, 0x80, 0x80, 0x80, 0x80, 0x80))
    }

    test("Leb128.readS32: 0 from a single 0x00 byte") {
      assertS32(b(0x00), 0, 1)
    }
    test("Leb128.readS32: -1 (sign bit set in single byte)") {
      assertS32(b(0x7f), -1, 1)
    }
    test("Leb128.readS32: -2 from 0x7e") {
      assertS32(b(0x7e), -2, 1)
    }
    test("Leb128.readS32: -64 from 0x40 (smallest single-byte negative)") {
      assertS32(b(0x40), -64, 1)
    }
    test("Leb128.readS32: positive 64 needs two bytes") {
      assertS32(b(0xc0, 0x00), 64, 2)
    }
    test("Leb128.readS32: -123456 from a multi-byte negative") {
      assertS32(b(0xc0, 0xbb, 0x78), -123456, 3)
    }
    test("Leb128.readS32: Int.MaxValue (full five-byte encoding)") {
      assertS32(b(0xff, 0xff, 0xff, 0xff, 0x07), Int.MaxValue, 5)
    }
    test("Leb128.readS32: Int.MinValue (full five-byte encoding)") {
      assertS32(b(0x80, 0x80, 0x80, 0x80, 0x78), Int.MinValue, 5)
    }
    test("Leb128.readS32: empty input fails with InvalidModule") {
      assertS32Fails(b())
    }
    test("Leb128.readS32: continuation bit set but no follow-on byte fails") {
      assertS32Fails(b(0x80))
    }
    test("Leb128.readS32: six-byte (oversized) input fails") {
      assertS32Fails(b(0x80, 0x80, 0x80, 0x80, 0x80, 0x80))
    }
    test("Leb128.readU32: non-zero startPos reads from the given offset") {
      val padded = b(0xff, 0xff, 0x80, 0x01)
      Leb128.readU32(padded, 2) match
        case Right((v, p)) =>
          check(v == 128, s"value at offset 2: expected 128, got $v")
          check(p == 4,   s"newPos: expected 4, got $p")
        case Left(e) => check(false, s"expected Right, got Left($e)")
    }

    // --- readS64 ------------------------------------------------------------

    def assertS64(bs: Array[Byte], v: Long, p: Int): Unit =
      Leb128.readS64(bs, 0) match
        case Right((vv, pp)) =>
          check(vv == v, s"value: expected $v, got $vv")
          check(pp == p, s"newPos: expected $p, got $pp")
        case Left(e) => check(false, s"expected Right, got Left($e)")

    def assertS64Fails(bs: Array[Byte]): Unit =
      Leb128.readS64(bs, 0) match
        case Right(v) => check(false, s"expected Left, got Right($v)")
        case Left(_)  => ()

    test("Leb128.readS64: 0 from a single 0x00 byte") {
      assertS64(b(0x00), 0L, 1)
    }
    test("Leb128.readS64: -1 (single 0x7F byte, sign bit set)") {
      assertS64(b(0x7f), -1L, 1)
    }
    test("Leb128.readS64: positive 64 (two-byte canonical encoding)") {
      assertS64(b(0xc0, 0x00), 64L, 2)
    }
    test("Leb128.readS64: -123456 (multi-byte negative)") {
      assertS64(b(0xc0, 0xbb, 0x78), -123456L, 3)
    }
    test("Leb128.readS64: 4294967295 (out of i32 range — five-byte encoding)") {
      // Same byte string would read as -1 under readS32 (no room to widen),
      // but as +0xFFFFFFFF in S64 since the sign bit isn't set.
      assertS64(b(0xff, 0xff, 0xff, 0xff, 0x0f), 4294967295L, 5)
    }
    test("Leb128.readS64: Long.MaxValue (full ten-byte encoding)") {
      assertS64(b(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00), Long.MaxValue, 10)
    }
    test("Leb128.readS64: Long.MinValue (full ten-byte encoding, sign bit in final byte)") {
      assertS64(b(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x7f), Long.MinValue, 10)
    }
    test("Leb128.readS64: empty input fails with InvalidModule") {
      assertS64Fails(b())
    }
    test("Leb128.readS64: continuation bit set but no follow-on byte fails") {
      assertS64Fails(b(0x80))
    }
    test("Leb128.readS64: eleven-byte (oversized) input fails") {
      assertS64Fails(b(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80))
    }
