package io.github.edadma.wasm

import TestSupport.*

/** End-to-end tests for Phase 8.D: multi-memory.
  *
  * The fixture declares two memories (mem0 + mem1), seeds each with a
  * distinct active data segment, and exposes one helper per memory op
  * with the memidx threaded in. The tests confirm:
  *
  *   - Active data segments land in the correct memory.
  *   - Loads/stores route to the right memory based on the memarg's
  *     bit-6-flagged memidx.
  *   - memory.size + memory.grow operate per-memory.
  *   - memory.fill targets only the named memory; the other is
  *     untouched.
  *   - memory.copy works within a single memory and across memories
  *     (no aliasing concern when the backing arrays differ).
  *   - memory.init can target either memory from one passive data
  *     segment.
  *   - The public ModuleInstance.memories accessor exposes both
  *     memories by index, and exportedMemory("name") routes by name.
  */
object MultiMemoryTests:

  def run(): Unit =

    // === active data segments target the right memory ==================

    test("active data segments seed mem0 + mem1 with distinct payloads") {
      val inst = instantiate(Fixtures.multi_memory)
      check(callI32(inst, "load8_u_m0", 0) == 'A'.toInt, "mem0[0] should be 'A'")
      check(callI32(inst, "load8_u_m0", 7) == 'A'.toInt, "mem0[7] should be 'A'")
      check(callI32(inst, "load8_u_m1", 0) == 'B'.toInt, "mem1[0] should be 'B'")
      check(callI32(inst, "load8_u_m1", 7) == 'B'.toInt, "mem1[7] should be 'B'")
    }

    test("public ModuleInstance.memories exposes both memories") {
      val inst = instantiate(Fixtures.multi_memory)
      check(inst.memories.length == 2, s"expected 2 memories, got ${inst.memories.length}")
      // Each memory is one page; data segments wrote 8 bytes at offset 0.
      check(inst.memories(0).data(0) == 'A'.toByte, "memories(0).data[0]")
      check(inst.memories(1).data(0) == 'B'.toByte, "memories(1).data[0]")
    }

    test("ModuleInstance.exportedMemory(name) routes by name") {
      val inst = instantiate(Fixtures.multi_memory)
      val mem0 = inst.exportedMemory("mem0")
      val mem1 = inst.exportedMemory("mem1")
      check(mem0.isRight, s"mem0: $mem0")
      check(mem1.isRight, s"mem1: $mem1")
      check(mem0.toOption.get.data(0) == 'A'.toByte, "mem0[0]")
      check(mem1.toOption.get.data(0) == 'B'.toByte, "mem1[0]")
      // Missing name returns ExportNotFound.
      inst.exportedMemory("nope") match
        case Left(WasmError.ExportNotFound(_)) => ()
        case other => check(false, s"expected ExportNotFound, got $other")
    }

    // === loads / stores route by memidx =================================

    test("store8 + load8 round-trip on mem0 only — mem1 unchanged") {
      val inst = instantiate(Fixtures.multi_memory)
      inst.invoke("store8_m0", Seq(I32(10), I32(0xCC))) match
        case Right(Seq()) => ()
        case other        => check(false, s"store8_m0: $other")
      check(callI32(inst, "load8_u_m0", 10) == 0xCC, "mem0[10] = 0xCC")
      check(callI32(inst, "load8_u_m1", 10) ==  0,    "mem1[10] still 0 (untouched)")
    }

    test("store8 on mem1 doesn't touch mem0") {
      val inst = instantiate(Fixtures.multi_memory)
      inst.invoke("store8_m1", Seq(I32(20), I32(0x77))) match
        case Right(Seq()) => ()
        case other        => check(false, s"store8_m1: $other")
      check(callI32(inst, "load8_u_m1", 20) == 0x77, "mem1[20] = 0x77")
      check(callI32(inst, "load8_u_m0", 20) ==  0,    "mem0[20] still 0")
    }

    // === memory.size / memory.grow per-memory ============================

    test("memory.size reports each memory's page count independently") {
      val inst = instantiate(Fixtures.multi_memory)
      check(callI32(inst, "size_m0") == 1, "mem0 size 1")
      check(callI32(inst, "size_m1") == 1, "mem1 size 1")
    }

    test("memory.grow on mem0 doesn't change mem1's size") {
      val inst = instantiate(Fixtures.multi_memory)
      val prev = callI32(inst, "grow_m0", 2)
      check(prev == 1, s"grow_m0 returned $prev, expected 1")
      check(callI32(inst, "size_m0") == 3, "mem0 size now 3")
      check(callI32(inst, "size_m1") == 1, "mem1 size unchanged at 1")
    }

    // === memory.fill per-memory =========================================

    test("memory.fill on mem0 leaves mem1 untouched") {
      val inst = instantiate(Fixtures.multi_memory)
      inst.invoke("fill_m0", Seq(I32(100), I32(0x33), I32(4))) match
        case Right(Seq()) => ()
        case other        => check(false, s"fill_m0: $other")
      // mem0[100..103] is 0x33; mem1[100] is still 0.
      check(callI32(inst, "load8_u_m0", 100) == 0x33, "mem0[100] = 0x33")
      check(callI32(inst, "load8_u_m0", 103) == 0x33, "mem0[103] = 0x33")
      check(callI32(inst, "load8_u_m1", 100) ==    0, "mem1[100] untouched")
    }

    // === memory.copy: same-memory + across memories =====================

    test("memory.copy within mem0 — overlapping forward") {
      val inst = instantiate(Fixtures.multi_memory)
      // Seed mem0[200..203] = 1,2,3,4.
      inst.invoke("store8_m0", Seq(I32(200), I32(1))); inst.invoke("store8_m0", Seq(I32(201), I32(2)))
      inst.invoke("store8_m0", Seq(I32(202), I32(3))); inst.invoke("store8_m0", Seq(I32(203), I32(4)))
      // Copy 200..203 → 202..205 (overlapping forward).
      inst.invoke("copy_within_m0", Seq(I32(202), I32(200), I32(4))) match
        case Right(Seq()) => ()
        case other        => check(false, s"copy_within_m0: $other")
      check(callI32(inst, "load8_u_m0", 202) == 1, "after copy: mem0[202] = 1")
      check(callI32(inst, "load8_u_m0", 205) == 4, "after copy: mem0[205] = 4")
    }

    test("memory.copy mem0 → mem1") {
      val inst = instantiate(Fixtures.multi_memory)
      // Seed mem0[50..53] = 'X','Y','Z','W'.
      inst.invoke("store8_m0", Seq(I32(50), I32('X'.toInt)))
      inst.invoke("store8_m0", Seq(I32(51), I32('Y'.toInt)))
      inst.invoke("store8_m0", Seq(I32(52), I32('Z'.toInt)))
      inst.invoke("store8_m0", Seq(I32(53), I32('W'.toInt)))
      // Copy 4 bytes from mem0[50..] to mem1[100..].
      inst.invoke("copy_m0_to_m1", Seq(I32(100), I32(50), I32(4))) match
        case Right(Seq()) => ()
        case other        => check(false, s"copy_m0_to_m1: $other")
      check(callI32(inst, "load8_u_m1", 100) == 'X'.toInt, "mem1[100] = 'X'")
      check(callI32(inst, "load8_u_m1", 103) == 'W'.toInt, "mem1[103] = 'W'")
      // mem0 source unchanged.
      check(callI32(inst, "load8_u_m0", 50) == 'X'.toInt, "mem0[50] still 'X'")
    }

    test("memory.copy mem1 → mem0 mirrors the other direction") {
      val inst = instantiate(Fixtures.multi_memory)
      // Initial: mem1[0..7] = "BBBBBBBB" from active data segment.
      // Copy 4 bytes from mem1[0..] to mem0[300..].
      inst.invoke("copy_m1_to_m0", Seq(I32(300), I32(0), I32(4))) match
        case Right(Seq()) => ()
        case other        => check(false, s"copy_m1_to_m0: $other")
      check(callI32(inst, "load8_u_m0", 300) == 'B'.toInt, "mem0[300] = 'B'")
      check(callI32(inst, "load8_u_m0", 303) == 'B'.toInt, "mem0[303] = 'B'")
    }

    // === memory.init per-memory from a single passive segment ============

    test("memory.init copies a passive segment into either memory") {
      val inst = instantiate(Fixtures.multi_memory)
      // Passive segment is "CDEFGHIJ" (8 bytes). Copy first 3 into mem0[400].
      inst.invoke("init_m0", Seq(I32(400), I32(0), I32(3))) match
        case Right(Seq()) => ()
        case other        => check(false, s"init_m0: $other")
      check(callI32(inst, "load8_u_m0", 400) == 'C'.toInt, "mem0[400] = 'C'")
      check(callI32(inst, "load8_u_m0", 402) == 'E'.toInt, "mem0[402] = 'E'")
      // Same segment, into mem1 at a different offset.
      inst.invoke("init_m1", Seq(I32(500), I32(3), I32(2))) match
        case Right(Seq()) => ()
        case other        => check(false, s"init_m1: $other")
      check(callI32(inst, "load8_u_m1", 500) == 'F'.toInt, "mem1[500] = 'F'")
      check(callI32(inst, "load8_u_m1", 501) == 'G'.toInt, "mem1[501] = 'G'")
    }

    // === traps ==========================================================

    test("memory.fill OOB on mem1 traps without touching mem0") {
      val inst = instantiate(Fixtures.multi_memory)
      // mem1 is 1 page = 65536 bytes; dst=65535 + n=2 overruns by 1.
      expectError(inst, "fill_m1", Seq(I32(65535), I32(0xAA), I32(2))) {
        case WasmError.MemoryOutOfBounds => true
      }
    }

end MultiMemoryTests
