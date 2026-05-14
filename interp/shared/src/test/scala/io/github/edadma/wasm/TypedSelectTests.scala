package io.github.edadma.wasm

import TestSupport.*

/** End-to-end tests for the typed `select t*` opcode (0x1C, Phase 8.C
  * follow-up). The untyped `select` (0x1B) only accepts numeric scalars
  * after Phase 8.C; reftype operands must use the typed form. The
  * encoding is `0x1C u32:count valtype[count]` with `count == 1` per
  * the current spec.
  *
  * Exercises:
  *
  *   - typed select for each of the six valtypes: i32 / i64 / f32 / f64
  *     / funcref / externref;
  *   - both branches (`cond != 0` picks the first operand, `cond == 0`
  *     picks the second);
  *   - reftype identity round-trip (externref host AnyRef survives a
  *     typed select unchanged; funcref dispatches to the right helper
  *     through a table afterward);
  *   - mixed `ref.func` / `ref.null` operands match the same valtype.
  */
object TypedSelectTests:

  def run(): Unit =

    // === numeric typed selects ==========================================

    test("select t* i32: cond=1 picks first") {
      val inst = instantiate(Fixtures.typed_select)
      check(callI32(inst, "sel_i32", 7, 99, 1) == 7, "cond=1 → first operand")
    }

    test("select t* i32: cond=0 picks second") {
      val inst = instantiate(Fixtures.typed_select)
      check(callI32(inst, "sel_i32", 7, 99, 0) == 99, "cond=0 → second operand")
    }

    test("select t* i64: cond chooses between i64 operands") {
      val inst = instantiate(Fixtures.typed_select)
      check(callI64(inst, "sel_i64", I64(0x7777_7777_7777_7777L), I64(0x1111_1111_1111_1111L), I32(1)) == 0x7777_7777_7777_7777L,
        "cond=1 → first i64")
      check(callI64(inst, "sel_i64", I64(0x7777_7777_7777_7777L), I64(0x1111_1111_1111_1111L), I32(0)) == 0x1111_1111_1111_1111L,
        "cond=0 → second i64")
    }

    test("select t* f32: cond chooses between f32 operands") {
      val inst = instantiate(Fixtures.typed_select)
      check(callF32(inst, "sel_f32", F32(1.5f), F32(-3.25f), I32(1)) == 1.5f, "cond=1 → 1.5f")
      check(callF32(inst, "sel_f32", F32(1.5f), F32(-3.25f), I32(0)) == -3.25f, "cond=0 → -3.25f")
    }

    test("select t* f64: cond chooses between f64 operands") {
      val inst = instantiate(Fixtures.typed_select)
      check(callF64(inst, "sel_f64", F64(2.5),  F64(-4.5),   I32(1)) == 2.5,  "cond=1 → 2.5")
      check(callF64(inst, "sel_f64", F64(2.5),  F64(-4.5),   I32(0)) == -4.5, "cond=0 → -4.5")
    }

    // === typed select on funcref ========================================

    test("select t* funcref: cond=1 picks f10 (dispatched through table)") {
      val inst = instantiate(Fixtures.typed_select)
      // sel_funcref_call writes the chosen funcref into tfunc[0] then dispatches.
      // cond=1 → f10 → returns 10.
      check(callI32(inst, "sel_funcref_call", 1) == 10, "cond=1 should dispatch f10")
    }

    test("select t* funcref: cond=0 picks f20 (dispatched through table)") {
      val inst = instantiate(Fixtures.typed_select)
      check(callI32(inst, "sel_funcref_call", 0) == 20, "cond=0 should dispatch f20")
    }

    // === typed select on externref ======================================

    test("select t* externref: returns the chosen reference by identity") {
      val inst = instantiate(Fixtures.typed_select)
      val a: AnyRef = new Object
      val b: AnyRef = new Object
      // cond=1 → first.
      inst.invoke("sel_externref", Seq(RefExtern(a), RefExtern(b), I32(1))) match
        case Right(Seq(RefExtern(got))) => check(got eq a, "cond=1 should return externref a")
        case other => check(false, s"sel_externref: $other")
      // cond=0 → second.
      inst.invoke("sel_externref", Seq(RefExtern(a), RefExtern(b), I32(0))) match
        case Right(Seq(RefExtern(got))) => check(got eq b, "cond=0 should return externref b")
        case other => check(false, s"sel_externref: $other")
    }

    test("select t* externref: ref.null externref propagates through select") {
      val inst = instantiate(Fixtures.typed_select)
      val payload: AnyRef = new Object
      // cond=0 picks the null externref.
      inst.invoke("sel_externref", Seq(RefExtern(payload), RefNull(RefType.ExternRef), I32(0))) match
        case Right(Seq(RefNull(RefType.ExternRef))) => ()
        case other => check(false, s"sel_externref null: $other")
    }

    // === mixed ref.func + ref.null operands =============================

    test("select t* funcref: ref.func vs ref.null — null branch is detected") {
      val inst = instantiate(Fixtures.typed_select)
      // sel_funcref_with_null: cond != 0 → ref.func $f10 (not null → 0);
      //                       cond == 0 → ref.null func (is null → 1).
      check(callI32(inst, "sel_funcref_with_null", 1) == 0, "cond=1 → ref.func branch is not null")
      check(callI32(inst, "sel_funcref_with_null", 0) == 1, "cond=0 → ref.null branch is null")
    }
