package io.github.edadma.wasm

import TestSupport.*

/** End-to-end tests for linear memory.
  *
  * Covers the pre-Phase-4 surface (i32/byte round-trips, OOB traps, active
  * data segments, declared-max acceptance) and the Phase 4 additions
  * (i32.load16_s/u, i32.store16, memory.size, memory.grow plus the
  * reserved-byte regression checks).
  */
object MemoryTests:

  def run(): Unit =
    baseline()
    phase4()

  // === Pre-Phase-4 baseline ===============================================

  private def baseline(): Unit =

    test("memory: i32 + byte store/load round-trip [load, store, store8, load8_u]") {
      val inst = instantiate(Fixtures.memory)
      check(callI32(inst, "i32_roundtrip",  0, 12345) == 12345, "addr 0 wrong")
      check(callI32(inst, "i32_roundtrip", 64,   -42) ==   -42, "negative round-trip wrong")
      check(callI32(inst, "byte_roundtrip", 0, 0xab)  ==  0xab, "unsigned byte wrong")
      check(callI32(inst, "byte_roundtrip", 7, 0x1ff) ==  0xff, "high bits dropped on store8")
    }

    test("trap: memory load out of bounds") {
      val inst = instantiate(Fixtures.memory_oob)
      expectError(inst, "load_oob", Seq(I32(65535))) { case WasmError.MemoryOutOfBounds => true }
      check(callI32(inst, "load_oob", 0) == 0, "in-bounds load returns 0 from zeroed memory")
      check(callI32(inst, "load_oob", 65536 - 4) == 0, "load at last valid 4-byte boundary")
    }

    test("trap: memory store out of bounds") {
      val inst = instantiate(Fixtures.store_oob)
      expectError(inst, "store_oob", Seq(I32(65534), I32(0xdead))) {
        case WasmError.MemoryOutOfBounds => true
      }
      inst.invoke("store_oob", Seq(I32(0), I32(0))) match
        case Right(Seq()) => ()
        case other        => check(false, s"in-bounds store should succeed, got $other")
    }

    test("data section: active segment writes bytes into memory at offset") {
      val inst = instantiate(Fixtures.data_segment)
      // "AB" at offset 0 → i32.load reads [0x41, 0x42, 0x00, 0x00] little-endian
      check(callI32(inst, "read") == 0x4241, "data segment not applied")
    }

    test("data section: out-of-bounds segment fails instantiation with MemoryOutOfBounds") {
      Runtime.instantiate(Fixtures.data_oob, Seq(EnvModule.default)) match
        case Left(WasmError.MemoryOutOfBounds) => ()
        case other => check(false, s"expected MemoryOutOfBounds, got $other")
    }

    test("memory limits: parser accepts the explicit `max` form") {
      val inst = instantiate(Fixtures.memory_max)
      check(callI32(inst, "size_at_zero", 0) == 0, "in-bounds load on memory-with-max")
    }

  // === Phase 4 ============================================================
  // Three new memory-width opcodes (i32.load16_s/u, i32.store16) plus the
  // pair that turn linear memory dynamic (memory.size, memory.grow).

  private def phase4(): Unit =

    test("memory.size: returns the initial declared page count") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      check(callI32(inst, "get_size") == 1, "fresh memory should report 1 page")
    }

    test("memory.grow: success path returns previous page count and updates size") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      // Grow by 2 (1 → 3) — well under the declared max of 4.
      check(callI32(inst, "grow_by", 2) == 1, "grow_by(2) should return prev=1")
      check(callI32(inst, "get_size")   == 3, "after grow size should be 3")
    }

    test("memory.grow: failure (exceeds declared max) returns -1, size unchanged") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      // max=4. Grow by 2 lands at 3 (ok), then grow by 99 would exceed 4.
      check(callI32(inst, "grow_by", 2)  ==  1, "first grow ok")
      check(callI32(inst, "grow_by", 99) == -1, "huge grow should fail with -1")
      check(callI32(inst, "get_size")    ==  3, "failed grow must NOT change size")
    }

    test("memory.grow: persists bytes written before the grow") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      // Write 0xAB at address 0, then grow by 1, then read it back.
      check(callI32(inst, "store_then_grow", 0, 0xab, 1) == 0xab,
        "byte written before grow should survive the reallocation")
    }

    test("memory.grow: zero-delta is a no-op that still returns the current size") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      check(callI32(inst, "grow_by", 0) == 1, "grow_by(0) returns prev page count")
      check(callI32(inst, "get_size")   == 1, "grow_by(0) does not change size")
    }

    test("memory.grow: negative delta returns -1, size unchanged") {
      val inst = instantiate(Fixtures.memory_grow_basic)
      // i32 -1 read by memory.grow as a delta — implementation rejects rather
      // than wrapping to a huge unsigned size.
      check(callI32(inst, "grow_by", -1) == -1, "negative delta should fail")
      check(callI32(inst, "get_size")    ==  1, "size unchanged after failed grow")
    }

    test("memory.grow: unbounded (no declared max) succeeds within the host cap") {
      val inst = instantiate(Fixtures.memory_grow_unbounded)
      check(callI32(inst, "size_initial")    == 1, "fresh memory: 1 page")
      check(callI32(inst, "grow_and_resize") == 5, "grow by 4 -> total 5 pages")
    }

    test("i32.load16_s: sign-extends a 0x8000 half-word to -32768") {
      val inst = instantiate(Fixtures.memory_load16)
      // Bytes at offset 0: 0x00 0x80 -> u16 0x8000 -> signed -32768.
      check(callI32(inst, "load_signed", 0) == -32768, "signed 0x8000 should sign-extend to -32768")
      // Bytes at offset 2: 0xff 0x7f -> u16 0x7fff -> signed +32767.
      check(callI32(inst, "load_signed", 2) ==  32767, "signed 0x7fff should be +32767")
    }

    test("i32.load16_u: zero-extends a 0x8000 half-word to +32768") {
      val inst = instantiate(Fixtures.memory_load16)
      check(callI32(inst, "load_unsigned", 0) == 32768, "unsigned 0x8000 should be 32768, not -32768")
      check(callI32(inst, "load_unsigned", 2) == 32767, "unsigned 0x7fff matches signed at +32767")
    }

    test("i32.store16: writes only the low 16 bits of the source i32") {
      val inst = instantiate(Fixtures.memory_load16)
      // High bits 0x12340000 should be dropped on store; only 0xbeef survives.
      check(callI32(inst, "store16_then_read", 8, 0x1234beef) == 0xbeef,
        "high 16 bits of source must be truncated")
      // The two-byte store mustn't touch byte 10 (still 0 — fresh memory there).
      // We don't have a getter for byte 10 in the fixture, but the readback at
      // addr 8 covering bytes 8..9 exhaustively pins the truncation.
    }

    test("memory.size with non-zero reserved byte traps with InvalidModule") {
      // Patch the byte immediately after the first 0x3F (memory.size) from
      // 0x00 to 0x01. The interpreter rejects it at dispatch time — the
      // pre-scan doesn't validate content, only width.
      val src = Fixtures.memory_grow_basic
      val idx = src.indexOf(0x3f.toByte)
      check(idx >= 0, "memory.size opcode (0x3F) not found in fixture")
      val bad  = patchByte(src, idx + 1, 0x01)
      val inst = instantiate(bad)
      expectError(inst, "get_size", Seq.empty) {
        case WasmError.InvalidModule(m) => m.contains("memory.size") && m.contains("reserved")
      }
    }

    test("memory.grow with non-zero reserved byte traps with InvalidModule") {
      val src = Fixtures.memory_grow_basic
      val idx = src.indexOf(0x40.toByte)
      check(idx >= 0, "memory.grow opcode (0x40) not found in fixture")
      val bad  = patchByte(src, idx + 1, 0x02)
      val inst = instantiate(bad)
      expectError(inst, "grow_by", Seq(I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("memory.grow") && m.contains("reserved")
      }
    }
