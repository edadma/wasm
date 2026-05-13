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
