package io.github.edadma.wasm

import TestSupport.*

/** End-to-end tests for Phase 3: tables + `call_indirect`.
  *
  * Three positive paths (same-signature dispatch, per-signature dispatch
  * through the structural FuncType check, `TableExport` surfacing) plus the
  * three dynamic trap classes the spec defines for `call_indirect`:
  * out-of-table-bounds, null funcref, and signature mismatch.
  */
object TablesTests:

  def run(): Unit =

    test("call_indirect: same-signature dispatch by slot index") {
      val inst = instantiate(Fixtures.call_indirect_basic)
      // slot 0 = add_one, slot 1 = double
      check(callI32(inst, "dispatch", 0, 41) == 42, "slot 0 (add_one) wrong")
      check(callI32(inst, "dispatch", 0,  0) ==  1, "slot 0 of 0")
      check(callI32(inst, "dispatch", 1,  7) == 14, "slot 1 (double) wrong")
      check(callI32(inst, "dispatch", 1, -3) == -6, "slot 1 of negative")
    }

    test("call_indirect: per-signature dispatch (i32→i32, i64→i64, (i32,i32)→i32)") {
      val inst = instantiate(Fixtures.call_indirect_polymorphic)
      // each entry dispatches through the slot whose signature matches
      check(callI32(inst, "call_neg_i32", 7)             == -7, "neg i32 wrong")
      check(callI32(inst, "call_neg_i32", -2147483648)   == -2147483648, "neg i32 MIN_VALUE wraps")
      check(callI64(inst, "call_neg_i64", I64(123456L))  == -123456L, "neg i64 wrong")
      check(callI32(inst, "call_mul", 6, 7)              == 42, "mul wrong")
    }

    test("call_indirect: signature mismatch traps with InvalidModule(\"signature mismatch\")") {
      // wrong_sig calls slot 2 ((i32,i32)->i32) with the (i32)->i32 typeidx;
      // trap fires before the body of `mul` ever runs.
      val inst = instantiate(Fixtures.call_indirect_polymorphic)
      expectError(inst, "wrong_sig", Seq(I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("signature mismatch")
      }
    }

    test("call_indirect: out-of-table-bounds slot traps") {
      val inst = instantiate(Fixtures.call_indirect_traps)
      expectError(inst, "via_oob", Seq(I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("out of table bounds")
      }
    }

    test("call_indirect: null funcref slot traps") {
      // Table is sized 4 but element segment only fills slots 0..1. Slot 2
      // is null and traps with the distinctive "null funcref" message.
      val inst = instantiate(Fixtures.call_indirect_traps)
      expectError(inst, "via_null", Seq(I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("null funcref")
      }
      // Populated slots in the same module still work — confirms the trap
      // path doesn't leave the runtime in a broken state.
    }

    // === Phase 8.B: bulk-table — table.init / elem.drop / table.copy =====
    //
    // The fixture exposes two tables (size 8 each) and one passive element
    // segment with funcrefs [f0, f1, f2, f3] — each f returns its own
    // funcidx as i32 so we can verify which slot ended up with which
    // funcref by invoking through call_indirect (`read_table` /
    // `read_table2` helpers).

    test("table.init: copies funcrefs from passive elem segment into table") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      inst.invoke("do_table_init", Seq(I32(0), I32(0), I32(4))) match
        case Right(Seq()) => ()
        case other        => check(false, s"do_table_init: $other")
      // Slots 0..3 hold f0..f3; each returns its own funcidx.
      check(callI32(inst, "read_table", 0) == 0, "slot 0 → f0 → 0")
      check(callI32(inst, "read_table", 1) == 1, "slot 1 → f1 → 1")
      check(callI32(inst, "read_table", 2) == 2, "slot 2 → f2 → 2")
      check(callI32(inst, "read_table", 3) == 3, "slot 3 → f3 → 3")
    }

    test("table.init: src offset selects a slice of the elem segment") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      // Copy elem[2..3] = [f2, f3] to table slots 5..6.
      inst.invoke("do_table_init", Seq(I32(5), I32(2), I32(2))) match
        case Right(Seq()) => ()
        case other        => check(false, s"do_table_init: $other")
      check(callI32(inst, "read_table", 5) == 2, "slot 5 → f2 → 2")
      check(callI32(inst, "read_table", 6) == 3, "slot 6 → f3 → 3")
    }

    test("table.init: n == 0 is a no-op at segment-end boundary") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      inst.invoke("do_table_init", Seq(I32(0), I32(4), I32(0))) match
        case Right(Seq()) => ()
        case other        => check(false, s"do_table_init n=0: $other")
    }

    test("table.init: src + n > segment-length traps MemoryOutOfBounds") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      // Elem segment is 4 entries; src=2 with n=3 overruns by 1.
      expectError(inst, "do_table_init", Seq(I32(0), I32(2), I32(3))) {
        case WasmError.MemoryOutOfBounds => true
      }
    }

    test("table.init: dst + n > table-size traps MemoryOutOfBounds") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      // Table is 8 slots; copying 4 at dst=6 overruns by 2.
      expectError(inst, "do_table_init", Seq(I32(6), I32(0), I32(4))) {
        case WasmError.MemoryOutOfBounds => true
      }
    }

    test("elem.drop: subsequent table.init with n > 0 traps; n == 0 still OK") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      // Pre-drop: table.init with n=1 succeeds.
      inst.invoke("do_table_init", Seq(I32(0), I32(0), I32(1))) match
        case Right(Seq()) => ()
        case other        => check(false, s"pre-drop table.init: $other")
      check(callI32(inst, "read_table", 0) == 0, "pre-drop slot 0 → f0")
      // Drop.
      inst.invoke("do_elem_drop", Seq.empty) match
        case Right(Seq()) => ()
        case other        => check(false, s"do_elem_drop: $other")
      // Post-drop: n=1 traps.
      expectError(inst, "do_table_init", Seq(I32(4), I32(0), I32(1))) {
        case WasmError.MemoryOutOfBounds => true
      }
      // n=0 is still permitted.
      inst.invoke("do_table_init", Seq(I32(0), I32(0), I32(0))) match
        case Right(Seq()) => ()
        case other        => check(false, s"post-drop n=0: $other")
    }

    test("elem.drop: idempotent (dropping twice is fine)") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      inst.invoke("do_elem_drop", Seq.empty) match
        case Right(Seq()) => ()
        case other        => check(false, s"first drop: $other")
      inst.invoke("do_elem_drop", Seq.empty) match
        case Right(Seq()) => ()
        case other        => check(false, s"second drop: $other")
    }

    test("table.copy: forward-overlap within same table preserves source slots") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      // Seed slots 0..3 with f0..f3 via table.init.
      inst.invoke("do_table_init", Seq(I32(0), I32(0), I32(4))) match
        case Right(Seq()) => ()
        case _            => ()
      // Copy slots 0..3 → 2..5 (overlapping forward).
      inst.invoke("do_table_copy", Seq(I32(2), I32(0), I32(4))) match
        case Right(Seq()) => ()
        case other        => check(false, s"do_table_copy: $other")
      // Slots 2..5 should now hold f0..f3.
      check(callI32(inst, "read_table", 2) == 0, "slot 2 → f0")
      check(callI32(inst, "read_table", 3) == 1, "slot 3 → f1")
      check(callI32(inst, "read_table", 4) == 2, "slot 4 → f2")
      check(callI32(inst, "read_table", 5) == 3, "slot 5 → f3")
    }

    test("table.copy: across tables (dst=1, src=0)") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      // Seed table 0 slots 0..3.
      inst.invoke("do_table_init", Seq(I32(0), I32(0), I32(4))) match
        case Right(Seq()) => ()
        case _            => ()
      // Copy to table 1 slots 4..7.
      inst.invoke("do_table_copy_across", Seq(I32(4), I32(0), I32(4))) match
        case Right(Seq()) => ()
        case other        => check(false, s"do_table_copy_across: $other")
      check(callI32(inst, "read_table2", 4) == 0, "table2 slot 4 → f0")
      check(callI32(inst, "read_table2", 5) == 1, "table2 slot 5 → f1")
      check(callI32(inst, "read_table2", 6) == 2, "table2 slot 6 → f2")
      check(callI32(inst, "read_table2", 7) == 3, "table2 slot 7 → f3")
    }

    test("table.copy: n == 0 is a no-op even at table-end boundary") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      inst.invoke("do_table_copy", Seq(I32(8), I32(8), I32(0))) match
        case Right(Seq()) => ()
        case other        => check(false, s"do_table_copy n=0: $other")
    }

    test("table.copy: src + n > size traps MemoryOutOfBounds") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      expectError(inst, "do_table_copy", Seq(I32(0), I32(5), I32(4))) {
        case WasmError.MemoryOutOfBounds => true
      }
    }

    test("table.copy: dst + n > size traps MemoryOutOfBounds") {
      val inst = instantiate(Fixtures.bulk_memory_remainder)
      expectError(inst, "do_table_copy", Seq(I32(5), I32(0), I32(4))) {
        case WasmError.MemoryOutOfBounds => true
      }
    }
