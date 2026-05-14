package io.github.edadma.wasm

import TestSupport.*

/** End-to-end tests for Phase 8.C: reference types.
  *
  * Three op groups exercised:
  *
  *   - **ref.null / ref.is_null** for both funcref and externref;
  *     ref.is_null over a host-supplied externref param.
  *   - **ref.func** + call_indirect through a table the test mutates
  *     via table.set.
  *   - **table.get / table.set / table.size / table.grow / table.fill**
  *     across both funcref + externref tables, including externref slots
  *     populated with a host AnyRef and pulled back out as the same
  *     identity. Edge cases: grow returning -1 on cap overflow, fill
  *     OOB trap, get OOB trap.
  */
object RefTypesTests:

  def run(): Unit =

    // === ref.null / ref.is_null =========================================

    test("ref.null func + ref.is_null returns 1") {
      val inst = instantiate(Fixtures.ref_types)
      check(callI32(inst, "null_func_is_null") == 1, "null funcref should be null")
    }

    test("ref.null extern + ref.is_null returns 1") {
      val inst = instantiate(Fixtures.ref_types)
      check(callI32(inst, "null_extern_is_null") == 1, "null externref should be null")
    }

    test("ref.is_null on host-supplied externref param: null → 1") {
      val inst = instantiate(Fixtures.ref_types)
      val result = inst.invoke("is_null_param", Seq(RefNull(RefType.ExternRef)))
      result match
        case Right(Seq(I32(1))) => ()
        case other              => check(false, s"expected 1, got $other")
    }

    test("ref.is_null on host-supplied externref param: non-null → 0") {
      val inst = instantiate(Fixtures.ref_types)
      val payload: AnyRef = "host-object-marker"
      val result = inst.invoke("is_null_param", Seq(RefExtern(payload)))
      result match
        case Right(Seq(I32(0))) => ()
        case other              => check(false, s"expected 0, got $other")
    }

    // === ref.func + call_indirect through a funcref slot ================

    test("ref.func + table.set + call_indirect round-trips a funcref") {
      val inst = instantiate(Fixtures.ref_types)
      // ref_func_via_table picks up f1 via ref.func, stores into slot 0,
      // then dispatches; f1 returns 1.
      check(callI32(inst, "ref_func_via_table") == 1, "ref.func+call_indirect should yield 1")
    }

    // === table.set / table.get for funcref tables =======================

    test("funcref table: set f0 then call_indirect returns 0") {
      val inst = instantiate(Fixtures.ref_types)
      inst.invoke("set_funcref_f0", Seq(I32(3))) match
        case Right(Seq()) => ()
        case other        => check(false, s"set_funcref_f0: $other")
      check(callI32(inst, "read_funcref", 3) == 0, "slot 3 → f0")
    }

    test("funcref table: set f3 then call_indirect returns 3") {
      val inst = instantiate(Fixtures.ref_types)
      inst.invoke("set_funcref_f3", Seq(I32(7))) match
        case Right(Seq()) => ()
        case other        => check(false, s"set_funcref_f3: $other")
      check(callI32(inst, "read_funcref", 7) == 3, "slot 7 → f3")
    }

    test("funcref table: set null then call_indirect traps null funcref") {
      val inst = instantiate(Fixtures.ref_types)
      // Pre-populate slot 2 with f3.
      inst.invoke("set_funcref_f3", Seq(I32(2))) match
        case Right(Seq()) => ()
        case other        => check(false, s"prep: $other")
      check(callI32(inst, "funcref_slot_is_null", 2) == 0, "post-set: not null")
      // Now overwrite with null.
      inst.invoke("set_funcref_null", Seq(I32(2))) match
        case Right(Seq()) => ()
        case other        => check(false, s"set_funcref_null: $other")
      check(callI32(inst, "funcref_slot_is_null", 2) == 1, "post-null: is null")
      expectError(inst, "read_funcref", Seq(I32(2))) {
        case WasmError.InvalidModule(m) => m.contains("null funcref")
      }
    }

    test("funcref table.get OOB traps MemoryOutOfBounds") {
      val inst = instantiate(Fixtures.ref_types)
      // tfunc starts size 8; slot 8 is one past the end.
      expectError(inst, "funcref_slot_is_null", Seq(I32(8))) {
        case WasmError.MemoryOutOfBounds => true
      }
    }

    // === externref tables ===============================================

    test("externref table: set + get round-trips a host AnyRef by identity") {
      val inst = instantiate(Fixtures.ref_types)
      val payload: AnyRef = new Object  // unique by identity
      inst.invoke("extern_set", Seq(I32(2), RefExtern(payload))) match
        case Right(Seq()) => ()
        case other        => check(false, s"extern_set: $other")
      inst.invoke("extern_get", Seq(I32(2))) match
        case Right(Seq(RefExtern(got))) =>
          check(got eq payload, "extern_get should return the same identity")
        case other => check(false, s"extern_get: $other")
    }

    test("externref table: unwritten slots read back as RefNull(ExternRef)") {
      val inst = instantiate(Fixtures.ref_types)
      inst.invoke("extern_get", Seq(I32(0))) match
        case Right(Seq(RefNull(RefType.ExternRef))) => ()
        case other => check(false, s"extern_get: $other")
    }

    test("externref table: set null then get reads null back") {
      val inst = instantiate(Fixtures.ref_types)
      val payload: AnyRef = new Object
      inst.invoke("extern_set", Seq(I32(1), RefExtern(payload))) match
        case Right(Seq()) => ()
        case _            => ()
      inst.invoke("extern_set", Seq(I32(1), RefNull(RefType.ExternRef))) match
        case Right(Seq()) => ()
        case other        => check(false, s"extern_set null: $other")
      inst.invoke("extern_get", Seq(I32(1))) match
        case Right(Seq(RefNull(RefType.ExternRef))) => ()
        case other => check(false, s"extern_get post-null: $other")
    }

    // === table.size =====================================================

    test("table.size for funcref + externref tables matches declared min") {
      val inst = instantiate(Fixtures.ref_types)
      check(callI32(inst, "tfunc_size")   == 8, "tfunc size is 8")
      check(callI32(inst, "textern_size") == 4, "textern size is 4")
    }

    // === table.grow =====================================================

    test("table.grow on funcref table: returns old size, new size visible") {
      val inst = instantiate(Fixtures.ref_types)
      check(callI32(inst, "tfunc_size") == 8, "pre-grow size 8")
      val prev = callI32(inst, "tfunc_grow_null", 4)
      check(prev == 8, s"tfunc_grow_null returned $prev (expected 8)")
      check(callI32(inst, "tfunc_size") == 12, "post-grow size 12")
    }

    test("table.grow newly-added funcref slots read back as null") {
      val inst = instantiate(Fixtures.ref_types)
      callI32(inst, "tfunc_grow_null", 2)
      // Slot 9 is one of the newly-added; it should be a null funcref.
      check(callI32(inst, "funcref_slot_is_null", 9) == 1, "new slot 9 should be null")
    }

    test("table.grow on externref table populates new slots with host externref") {
      val inst = instantiate(Fixtures.ref_types)
      val payload: AnyRef = new Object
      val prev = inst.invoke("textern_grow", Seq(RefExtern(payload), I32(3))) match
        case Right(Seq(I32(v))) => v
        case other              => check(false, s"textern_grow: $other"); 0
      check(prev == 4, s"prev size $prev (expected 4)")
      check(callI32(inst, "textern_size") == 7, "post-grow size 7")
      inst.invoke("extern_get", Seq(I32(5))) match
        case Right(Seq(RefExtern(got))) =>
          check(got eq payload, "new slot should hold the same host obj")
        case other => check(false, s"extern_get: $other")
    }

    test("table.grow with delta past max returns -1 (no resize)") {
      // Neither table declares a max; the implicit cap is Int.MaxValue. To
      // pin the negative-delta path we ask grow(-1) which is unsigned-MAX
      // — `Int.MaxValue.toLong + 1L < Int.MaxValue.toLong` is false (it
      // would equal exactly Int.MaxValue+1), so the cap-check rejects.
      // Equivalent: an extremely large positive delta.
      val inst = instantiate(Fixtures.ref_types)
      val prev = callI32(inst, "tfunc_grow_null", -1)
      check(prev == -1, s"expected -1 for cap-overflow, got $prev")
      check(callI32(inst, "tfunc_size") == 8, "size unchanged after failed grow")
    }

    // === table.fill =====================================================

    test("table.fill on funcref table writes the right ref to every slot in the range") {
      val inst = instantiate(Fixtures.ref_types)
      // Fill slots 0..3 with f0.
      inst.invoke("tfunc_fill_f0", Seq(I32(0), I32(4))) match
        case Right(Seq()) => ()
        case other        => check(false, s"tfunc_fill_f0: $other")
      check(callI32(inst, "read_funcref", 0) == 0, "slot 0 → f0")
      check(callI32(inst, "read_funcref", 1) == 0, "slot 1 → f0")
      check(callI32(inst, "read_funcref", 2) == 0, "slot 2 → f0")
      check(callI32(inst, "read_funcref", 3) == 0, "slot 3 → f0")
      // Slot 4 (outside the fill range) is still null — calling traps.
      expectError(inst, "read_funcref", Seq(I32(4))) {
        case WasmError.InvalidModule(m) => m.contains("null funcref")
      }
    }

    test("table.fill n == 0 is a no-op even at table-end boundary") {
      val inst = instantiate(Fixtures.ref_types)
      inst.invoke("tfunc_fill_f0", Seq(I32(8), I32(0))) match
        case Right(Seq()) => ()
        case other        => check(false, s"tfunc_fill_f0 n=0: $other")
    }

    test("table.fill dst + n > table size traps MemoryOutOfBounds") {
      val inst = instantiate(Fixtures.ref_types)
      // tfunc size 8; dst=6 + n=3 overruns by 1.
      expectError(inst, "tfunc_fill_f0", Seq(I32(6), I32(3))) {
        case WasmError.MemoryOutOfBounds => true
      }
    }

    test("table.fill on externref table populates with the host externref") {
      val inst = instantiate(Fixtures.ref_types)
      val payload: AnyRef = new Object
      inst.invoke("textern_fill", Seq(I32(0), RefExtern(payload), I32(4))) match
        case Right(Seq()) => ()
        case other        => check(false, s"textern_fill: $other")
      // Spot-check the boundaries.
      inst.invoke("extern_get", Seq(I32(0))) match
        case Right(Seq(RefExtern(got))) => check(got eq payload, "slot 0 identity")
        case other => check(false, s"extern_get 0: $other")
      inst.invoke("extern_get", Seq(I32(3))) match
        case Right(Seq(RefExtern(got))) => check(got eq payload, "slot 3 identity")
        case other => check(false, s"extern_get 3: $other")
    }

end RefTypesTests
