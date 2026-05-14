package io.github.edadma.wasm

import TestSupport.*

/** Phase 8.E.A — SIMD foundations + `v128.const` tests.
  *
  * The "minimum viable SIMD" surface: `Value.V128` and
  * `ValueType.V128Type` are first-class citizens of the type system
  * (params, results, locals, globals, blocktypes). The only opcode
  * implemented in this chunk is `v128.const` (`0xFD 0x0C` + 16 raw
  * bytes). Subsequent chunks (B..I) layer the lane-aware load/store,
  * splat/extract/replace, arithmetic, comparison, conversion, and
  * special ops on top of this base.
  *
  * Tests assert raw byte equality on returned `V128` values — chunk C
  * will bring `extract_lane` for the typed-readout shorthand.
  */
object SimdConstTests:

  /** Helper: compare a V128's bits against an expected byte array. */
  private def bytesEq(actual: Array[Byte], expected: Array[Byte]): Boolean =
    if actual.length != expected.length then false
    else
      var i = 0
      var ok = true
      while ok && i < actual.length do
        if actual(i) != expected(i) then ok = false
        i += 1
      ok

  /** Convenience for building 16-byte literals. */
  private def b16(values: Int*): Array[Byte] =
    require(values.length == 16, s"b16 needs 16 values, got ${values.length}")
    values.iterator.map(_.toByte).toArray

  /** Invoke a no-arg function returning v128; pull the bits out. */
  private def callV128(inst: ModuleInstance, name: String, args: Value*): Array[Byte] =
    val results = runRight(inst.invoke(name, args))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case V128(bs) => bs
      case other    => throw new AssertionError(s"$name returned $other, expected V128")

  def run(): Unit =

    test("v128.const i32x4 1 2 3 4: little-endian 16-byte payload") {
      val inst = instantiate(Fixtures.simd_const)
      // i32 LE: 1 → 01 00 00 00, 2 → 02 00 00 00, ...
      val expected = b16(
        0x01, 0x00, 0x00, 0x00,
        0x02, 0x00, 0x00, 0x00,
        0x03, 0x00, 0x00, 0x00,
        0x04, 0x00, 0x00, 0x00,
      )
      val actual = callV128(inst, "const_i32x4")
      check(bytesEq(actual, expected), s"i32x4 const bytes wrong: got ${actual.mkString(",")}")
    }

    test("v128.const i16x8 reads the same 16 bytes back") {
      val inst = instantiate(Fixtures.simd_const)
      // i16 LE: 0x0102 → 02 01, 0x0304 → 04 03, ...
      val expected = b16(
        0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07,
        0x0a, 0x09, 0x0c, 0x0b, 0x0e, 0x0d, 0x10, 0x0f,
      )
      val actual = callV128(inst, "const_i16x8")
      check(bytesEq(actual, expected), s"i16x8 const bytes wrong: got ${actual.mkString(",")}")
    }

    test("v128.const i8x16 and i16x8 of the same bytes round-trip identically") {
      // Confirms the lane annotation is wat-side metadata only — the
      // binary form is opaque 16 bytes.
      val inst = instantiate(Fixtures.simd_const)
      val a = callV128(inst, "const_i16x8")
      val b = callV128(inst, "const_i8x16_same")
      check(bytesEq(a, b), "two annotations of the same 16-byte payload should round-trip identical")
    }

    test("v128 parameter + local round-trip: identity_v128 returns its argument unchanged") {
      val inst = instantiate(Fixtures.simd_const)
      val input = b16(
        0xff, 0xfe, 0xfd, 0xfc, 0xfb, 0xfa, 0xf9, 0xf8,
        0xf7, 0xf6, 0xf5, 0xf4, 0xf3, 0xf2, 0xf1, 0xf0,
      )
      val results = runRight(inst.invoke("identity_v128", Seq(V128(input))))
      results.head match
        case V128(out) => check(bytesEq(out, input), s"identity round-trip mismatch: ${out.mkString(",")}")
        case other     => check(false, s"identity_v128: $other")
    }

    test("v128-result block: (block (result v128) (v128.const ...)) propagates through") {
      val inst = instantiate(Fixtures.simd_const)
      // i32x4 0xdeadbeef 0 0 0 → bytes: ef be ad de 00 00 00 00 00 00 00 00 00 00 00 00
      val expected = b16(
        0xef, 0xbe, 0xad, 0xde, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      )
      val actual = callV128(inst, "block_v128")
      check(bytesEq(actual, expected), s"v128 block result mismatch: ${actual.mkString(",")}")
    }

    test("v128 local zero-init: declared-but-never-written reads back as 16 zero bytes") {
      val inst = instantiate(Fixtures.simd_const)
      val zeros = b16(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val actual = callV128(inst, "zero_init_local")
      check(bytesEq(actual, zeros), s"v128 zero-init wrong: ${actual.mkString(",")}")
    }
