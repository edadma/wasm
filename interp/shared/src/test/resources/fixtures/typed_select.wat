;; Phase 8.C follow-up: typed `select t*` (opcode 0x1C).
;;
;; The untyped `select` (0x1B) is restricted to numeric scalars; reftype
;; operands MUST use the typed form. Encoding: 0x1C + u32 count + count
;; valtype bytes (count is always 1 per the current spec).
;;
;; Exports drive each valtype: i32 / i64 / f32 / f64 plus funcref and
;; externref. The reftype exports verify identity round-trips — the
;; funcref one stores the chosen funcref into a table and dispatches
;; through it so the test can read which one survived; the externref
;; one returns the chosen reference directly so the host can compare
;; identities.
(module
  (type $i32fn (func (result i32)))

  ;; Two distinguishable funcrefs — return distinct i32 markers.
  (func $f10 (result i32) (i32.const 10))
  (func $f20 (result i32) (i32.const 20))

  (table (export "tfunc") 4 funcref)

  ;; ref.func validates against the declared-funcidx set.
  (elem declare funcref (ref.func $f10) (ref.func $f20))

  ;; --- numeric typed selects ---------------------------------------
  (func (export "sel_i32") (param $a i32) (param $b i32) (param $cond i32) (result i32)
    (select (result i32) (local.get $a) (local.get $b) (local.get $cond)))

  (func (export "sel_i64") (param $a i64) (param $b i64) (param $cond i32) (result i64)
    (select (result i64) (local.get $a) (local.get $b) (local.get $cond)))

  (func (export "sel_f32") (param $a f32) (param $b f32) (param $cond i32) (result f32)
    (select (result f32) (local.get $a) (local.get $b) (local.get $cond)))

  (func (export "sel_f64") (param $a f64) (param $b f64) (param $cond i32) (result f64)
    (select (result f64) (local.get $a) (local.get $b) (local.get $cond)))

  ;; --- typed select on funcref ------------------------------------
  ;; cond != 0 → f10 (returns 10); cond == 0 → f20 (returns 20).
  (func (export "sel_funcref_call") (param $cond i32) (result i32)
    (table.set 0 (i32.const 0)
      (select (result funcref)
        (ref.func $f10) (ref.func $f20) (local.get $cond)))
    (call_indirect 0 (type $i32fn) (i32.const 0)))

  ;; --- typed select on externref ----------------------------------
  ;; Host passes two externrefs; result is the chosen one. Host-side
  ;; identity equality verifies the right reference came back.
  (func (export "sel_externref") (param $a externref) (param $b externref) (param $cond i32) (result externref)
    (select (result externref) (local.get $a) (local.get $b) (local.get $cond)))

  ;; --- typed select where one operand is ref.null ------------------
  ;; The validator must accept ref.null as the matched valtype.
  (func (export "sel_funcref_with_null") (param $cond i32) (result i32)
    (ref.is_null
      (select (result funcref)
        (ref.func $f10) (ref.null func) (local.get $cond)))))
