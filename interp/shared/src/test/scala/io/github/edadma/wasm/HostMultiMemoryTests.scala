package io.github.edadma.wasm

import TestSupport.*

/** End-to-end tests for the multi-memory HostFunc surface (Phase 8.D
  * follow-up). Adds a [[HostFuncMulti]] alongside the existing
  * [[HostFunc]] so host functions can target memories beyond memidx 0.
  *
  * The two surfaces are normalised at import-resolution time into a
  * single internal HostFuncMulti shape — single-memory hosts (the
  * plain HostFunc) are wrapped so they see only `mems.head`. This file
  * covers:
  *
  *   - a HostFuncMulti can read AND write a memory other than memidx 0;
  *   - existing single-memory HostFunc hosts continue to work unchanged
  *     against single-memory modules (regression);
  *   - a name registered in both maps resolves to the multi-memory
  *     form;
  *   - the IndexedSeq[Memory] passed in has the expected size matching
  *     the module's declared memory count.
  */
object HostMultiMemoryTests:

  def run(): Unit =

    // === multi-memory host writes both memories ==========================

    test("HostFuncMulti can write to memidx 1 (single-memory HostFunc cannot)") {
      // A HostFuncMulti that writes the marker byte to mem0[0] AND mem1[0].
      val multiHost = new HostModule:
        val name: String = "host"
        override val functionsMulti: Map[String, HostFuncMulti] = Map(
          "mark_both" -> { (mems, args) =>
            args match
              case Seq(I32(b)) =>
                check(mems.size == 2, s"expected 2 memories, got ${mems.size}")
                mems(0).data(0) = (b & 0xff).toByte
                mems(1).data(0) = (b & 0xff).toByte
                Seq.empty
              case _ => Seq.empty
          },
        )
      val inst = instantiate(Fixtures.host_multi_memory, multiHost)
      inst.invoke("mark_via_host", Seq(I32(0xAB))) match
        case Right(Seq()) => ()
        case other        => check(false, s"mark_via_host: $other")
      check(callI32(inst, "load_mem0_byte", 0) == 0xAB, "mem0[0] should be 0xAB")
      check(callI32(inst, "load_mem1_byte", 0) == 0xAB, "mem1[0] should be 0xAB")
    }

    // === single-memory regression ========================================

    test("Single-memory HostFunc continues to work unchanged against single-memory modules") {
      // Regression — invoke "hello" against the putchar fixture; the
      // collected callback must see all three bytes "Hi!" exactly as it
      // did before the HostFuncMulti normalisation landed.
      val collected = new StringBuilder
      val env       = EnvModule.withWriter(c => collected.append(c.toChar))
      val inst      = instantiate(Fixtures.putchar, env)
      runOk(inst.invoke("hello"))
      check(collected.toString == "Hi!", s"single-memory HostFunc regression — got '${collected}'")
    }

    // === both maps populated — multi wins ===============================

    test("Name in both functions + functionsMulti resolves to the multi-memory form") {
      var singleCalled = false
      var multiCalled  = false
      val host = new HostModule:
        val name: String = "host"
        override val functions: Map[String, HostFunc] = Map(
          "mark_both" -> { (_, _) =>
            singleCalled = true
            Seq.empty
          },
        )
        override val functionsMulti: Map[String, HostFuncMulti] = Map(
          "mark_both" -> { (mems, args) =>
            multiCalled = true
            args match
              case Seq(I32(b)) =>
                mems(0).data(0) = (b & 0xff).toByte
                mems(1).data(0) = (b & 0xff).toByte
                Seq.empty
              case _ => Seq.empty
          },
        )
      val inst = instantiate(Fixtures.host_multi_memory, host)
      inst.invoke("mark_via_host", Seq(I32(0x7E))) match
        case Right(Seq()) => ()
        case other        => check(false, s"mark_via_host: $other")
      check(!singleCalled, "single-memory shadow should NOT be called when multi is present")
      check(multiCalled,   "multi-memory function should be called")
      check(callI32(inst, "load_mem0_byte", 0) == 0x7E, "mem0[0] should be 0x7E (multi path)")
      check(callI32(inst, "load_mem1_byte", 0) == 0x7E, "mem1[0] should be 0x7E (multi path)")
    }
