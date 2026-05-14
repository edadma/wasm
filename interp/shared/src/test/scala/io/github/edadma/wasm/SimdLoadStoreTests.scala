package io.github.edadma.wasm

import TestSupport.*

/** Phase 8.E.B — SIMD loads + stores.
  *
  * Layers the lane-aware memory ops on top of Chunk A's V128 plumbing:
  *
  *   - `v128.load` / `v128.store` — full 16-byte little-endian load/store.
  *   - `v128.load{8,16,32,64}_splat` — read N bytes, broadcast across all
  *     16/N lanes of the result.
  *   - `v128.load32_zero` / `v128.load64_zero` — read N bytes into lane 0,
  *     zero the remaining 16-N bytes.
  *   - `v128.load{8x8,16x4,32x2}_{s,u}` — read 8 bytes as N source lanes,
  *     widen each into a 2N-byte destination lane (signed or zero extension).
  *
  * Memory is written via the exported `mem` (poked from the host through
  * `ModuleInstance.exportedMemory`) and loaded back via the SIMD load.
  * Stores are verified by reading the bytes back through the same memory
  * handle.
  *
  * Two extra coverage points:
  *   - `offset=N` in a memarg is honoured (matches Phase 8.D scalar paths).
  *   - An out-of-bounds load traps with `MemoryOutOfBounds`.
  */
object SimdLoadStoreTests:

  /** Compare two byte arrays; tiny standalone copy since SimdConstTests'
    * is private. */
  private def bytesEq(actual: Array[Byte], expected: Array[Byte]): Boolean =
    if actual.length != expected.length then false
    else
      var i = 0
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

  /** Resolve the fixture's exported `mem` once; tests poke bytes into it
    * before each SIMD load. */
  private def memOf(inst: ModuleInstance): Memory =
    runRight(inst.exportedMemory("mem"))

  /** Write a sequence of bytes starting at `addr`. */
  private def writeBytes(mem: Memory, addr: Int, bytes: Array[Byte]): Unit =
    var i = 0
    while i < bytes.length do
      mem.data(addr + i) = bytes(i)
      i += 1

  def run(): Unit =

    test("v128.store + v128.load round-trip: 16 raw bytes through memory") {
      val inst    = instantiate(Fixtures.simd_load_store)
      val payload = b16(
        0xff, 0x01, 0xee, 0x02, 0xdd, 0x03, 0xcc, 0x04,
        0xbb, 0x05, 0xaa, 0x06, 0x99, 0x07, 0x88, 0x08,
      )
      val out = callV128(inst, "store_then_load", I32(0), V128(payload))
      check(bytesEq(out, payload), s"round-trip mismatch: ${out.mkString(",")}")

      // The store should have left those exact bytes in memory.
      val mem    = memOf(inst)
      val actual = mem.data.slice(0, 16)
      check(bytesEq(actual, payload), s"store didn't write bytes: ${actual.mkString(",")}")
    }

    test("v128.load reads the 16 little-endian bytes the host poked into memory") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      val expected = b16(
        0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
        0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x01,
      )
      writeBytes(mem, 32, expected)

      val out = callV128(inst, "load_at", I32(32))
      check(bytesEq(out, expected), s"load_at: ${out.mkString(",")}")
    }

    test("v128.load with offset=16 reads from addr+16") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      // Write distinct payloads at 0 and 16; load_at_off(0) should pick the second.
      val noise = b16(0xaa, 0xbb, 0xcc, 0xdd, 0xaa, 0xbb, 0xcc, 0xdd, 0xaa, 0xbb, 0xcc, 0xdd, 0xaa, 0xbb, 0xcc, 0xdd)
      val real  = b16(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff, 0x00)
      writeBytes(mem, 0, noise)
      writeBytes(mem, 16, real)

      val out = callV128(inst, "load_at_off", I32(0))
      check(bytesEq(out, real), s"load_at_off: ${out.mkString(",")}")
    }

    test("v128.load8_splat broadcasts one byte across all 16 lanes") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      mem.data(48) = 0x42.toByte

      val out = callV128(inst, "load8_splat_at", I32(48))
      val expected = Array.fill(16)(0x42.toByte)
      check(bytesEq(out, expected), s"load8_splat: ${out.mkString(",")}")
    }

    test("v128.load16_splat broadcasts a 2-byte pattern across 8 lanes") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      // LE: 0xCAFE → 0xFE, 0xCA
      mem.data(64) = 0xfe.toByte
      mem.data(65) = 0xca.toByte

      val out = callV128(inst, "load16_splat_at", I32(64))
      val expected = b16(
        0xfe, 0xca, 0xfe, 0xca, 0xfe, 0xca, 0xfe, 0xca,
        0xfe, 0xca, 0xfe, 0xca, 0xfe, 0xca, 0xfe, 0xca,
      )
      check(bytesEq(out, expected), s"load16_splat: ${out.mkString(",")}")
    }

    test("v128.load32_splat broadcasts a 4-byte pattern across 4 lanes") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      // LE bytes for 0xDEADBEEF: EF BE AD DE
      mem.data(80) = 0xef.toByte
      mem.data(81) = 0xbe.toByte
      mem.data(82) = 0xad.toByte
      mem.data(83) = 0xde.toByte

      val out = callV128(inst, "load32_splat_at", I32(80))
      val expected = b16(
        0xef, 0xbe, 0xad, 0xde, 0xef, 0xbe, 0xad, 0xde,
        0xef, 0xbe, 0xad, 0xde, 0xef, 0xbe, 0xad, 0xde,
      )
      check(bytesEq(out, expected), s"load32_splat: ${out.mkString(",")}")
    }

    test("v128.load64_splat broadcasts an 8-byte pattern across 2 lanes") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      val seed = Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
      writeBytes(mem, 96, seed)

      val out = callV128(inst, "load64_splat_at", I32(96))
      val expected = b16(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
      )
      check(bytesEq(out, expected), s"load64_splat: ${out.mkString(",")}")
    }

    test("v128.load32_zero places 4 bytes in lane 0; remaining 12 bytes are zero") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      mem.data(112) = 0x12.toByte
      mem.data(113) = 0x34.toByte
      mem.data(114) = 0x56.toByte
      mem.data(115) = 0x78.toByte

      val out = callV128(inst, "load32_zero_at", I32(112))
      val expected = b16(
        0x12, 0x34, 0x56, 0x78, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0,
      )
      check(bytesEq(out, expected), s"load32_zero: ${out.mkString(",")}")
    }

    test("v128.load64_zero places 8 bytes in lane 0; remaining 8 bytes are zero") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      val seed = Array[Byte](0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte)
      writeBytes(mem, 128, seed)

      val out = callV128(inst, "load64_zero_at", I32(128))
      val expected = b16(
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88,
        0, 0, 0, 0, 0, 0, 0, 0,
      )
      check(bytesEq(out, expected), s"load64_zero: ${out.mkString(",")}")
    }

    test("v128.load8x8_s sign-extends 8 i8 lanes into 8 i16 lanes") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      // Mix of positive and negative i8 values.
      val src = Array[Byte](0x7f, 0x80.toByte, 0xff.toByte, 0x01, 0x00, 0x7e, 0x81.toByte, 0xfe.toByte)
      writeBytes(mem, 144, src)

      val out = callV128(inst, "load8x8_s_at", I32(144))
      // Each i8 → i16 sign-extended LE:
      //   0x7f → 7F 00, 0x80 (=-128) → 80 FF, 0xff (=-1) → FF FF, 0x01 → 01 00,
      //   0x00 → 00 00, 0x7e → 7E 00, 0x81 (=-127) → 81 FF, 0xfe (=-2) → FE FF.
      val expected = b16(
        0x7f, 0x00, 0x80, 0xff,
        0xff, 0xff, 0x01, 0x00,
        0x00, 0x00, 0x7e, 0x00,
        0x81, 0xff, 0xfe, 0xff,
      )
      check(bytesEq(out, expected), s"load8x8_s: ${out.mkString(",")}")
    }

    test("v128.load8x8_u zero-extends 8 i8 lanes into 8 i16 lanes") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      val src  = Array[Byte](0x7f, 0x80.toByte, 0xff.toByte, 0x01, 0x00, 0x7e, 0x81.toByte, 0xfe.toByte)
      writeBytes(mem, 160, src)

      val out = callV128(inst, "load8x8_u_at", I32(160))
      val expected = b16(
        0x7f, 0x00, 0x80, 0x00,
        0xff, 0x00, 0x01, 0x00,
        0x00, 0x00, 0x7e, 0x00,
        0x81, 0x00, 0xfe, 0x00,
      )
      check(bytesEq(out, expected), s"load8x8_u: ${out.mkString(",")}")
    }

    test("v128.load16x4_s sign-extends 4 i16 lanes into 4 i32 lanes") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      // Four LE i16s: 0x7FFF, 0x8000 (=-32768), 0xFFFF (=-1), 0x0001.
      val src = Array[Byte](
        0xff.toByte, 0x7f,
        0x00,        0x80.toByte,
        0xff.toByte, 0xff.toByte,
        0x01,        0x00,
      )
      writeBytes(mem, 176, src)

      val out = callV128(inst, "load16x4_s_at", I32(176))
      // i16 → i32 sign-extended, LE:
      //   0x7FFF → FF 7F 00 00, 0x8000 → 00 80 FF FF,
      //   0xFFFF → FF FF FF FF, 0x0001 → 01 00 00 00.
      val expected = b16(
        0xff, 0x7f, 0x00, 0x00,
        0x00, 0x80, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff,
        0x01, 0x00, 0x00, 0x00,
      )
      check(bytesEq(out, expected), s"load16x4_s: ${out.mkString(",")}")
    }

    test("v128.load16x4_u zero-extends 4 i16 lanes into 4 i32 lanes") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      val src = Array[Byte](
        0xff.toByte, 0x7f,
        0x00,        0x80.toByte,
        0xff.toByte, 0xff.toByte,
        0x01,        0x00,
      )
      writeBytes(mem, 192, src)

      val out = callV128(inst, "load16x4_u_at", I32(192))
      val expected = b16(
        0xff, 0x7f, 0x00, 0x00,
        0x00, 0x80, 0x00, 0x00,
        0xff, 0xff, 0x00, 0x00,
        0x01, 0x00, 0x00, 0x00,
      )
      check(bytesEq(out, expected), s"load16x4_u: ${out.mkString(",")}")
    }

    test("v128.load32x2_s sign-extends 2 i32 lanes into 2 i64 lanes") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      // Two LE i32s: 0x7FFFFFFF, 0x80000001 (=-2147483647).
      val src = Array[Byte](
        0xff.toByte, 0xff.toByte, 0xff.toByte, 0x7f,
        0x01,        0x00,        0x00,        0x80.toByte,
      )
      writeBytes(mem, 208, src)

      val out = callV128(inst, "load32x2_s_at", I32(208))
      // i32 → i64 sign-extended, LE:
      //   0x7FFFFFFF → FF FF FF 7F 00 00 00 00,
      //   0x80000001 → 01 00 00 80 FF FF FF FF (sign-extended).
      val expected = b16(
        0xff, 0xff, 0xff, 0x7f, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00, 0x80, 0xff, 0xff, 0xff, 0xff,
      )
      check(bytesEq(out, expected), s"load32x2_s: ${out.mkString(",")}")
    }

    test("v128.load32x2_u zero-extends 2 i32 lanes into 2 i64 lanes") {
      val inst = instantiate(Fixtures.simd_load_store)
      val mem  = memOf(inst)
      val src = Array[Byte](
        0xff.toByte, 0xff.toByte, 0xff.toByte, 0x7f,
        0x01,        0x00,        0x00,        0x80.toByte,
      )
      writeBytes(mem, 224, src)

      val out = callV128(inst, "load32x2_u_at", I32(224))
      val expected = b16(
        0xff, 0xff, 0xff, 0x7f, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00,
      )
      check(bytesEq(out, expected), s"load32x2_u: ${out.mkString(",")}")
    }

    test("v128.load at addr+offset = 65537 traps with MemoryOutOfBounds") {
      val inst = instantiate(Fixtures.simd_load_store)
      // memory is 1 page (65536 bytes); load_oob does a plain v128.load
      // at the address we pass. 65521 + 16 = 65537 → OOB.
      expectError(inst, "load_oob", Seq(I32(65521))) {
        case WasmError.MemoryOutOfBounds => true
        case _                           => false
      }
    }
