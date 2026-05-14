;; Phase 8.C: reference types — ref.null / ref.is_null / ref.func + the
;; ref-typed table accessors table.get / table.set / table.size / table.grow
;; / table.fill, plus externref tables and externref function params.
;;
;; Layout:
;;   - One funcref  table size 8 — primary, tableidx 0.
;;   - One externref table size 4 — tableidx 1.
;;   - Four identity-like funcref helpers f0..f3 that each return their own
;;     funcidx as an i32 (lets tests verify which funcref a slot holds by
;;     dispatching through call_indirect and reading the int).
;;   - One DECLARATIVE element segment pre-declaring f0..f3 so `ref.func`
;;     can name them.
;;
;; Every new 8.C opcode has at least one export driving it; the helpers
;; (read_funcref / read_extern_via_table) let tests inspect post-state.
(module
  ;; --- helper funcs --------------------------------------------------
  (func $f0 (result i32) (i32.const 0))
  (func $f1 (result i32) (i32.const 1))
  (func $f2 (result i32) (i32.const 2))
  (func $f3 (result i32) (i32.const 3))

  ;; --- tables -------------------------------------------------------
  (table (export "tfunc")   8 funcref)
  (table (export "textern") 4 externref)

  ;; Declarative segment — required so ref.func $fN validates against
  ;; the "declared funcs" set.
  (elem declare funcref (ref.func $f0) (ref.func $f1) (ref.func $f2) (ref.func $f3))

  (type $i32fn (func (result i32)))

  ;; --- ref.null / ref.is_null --------------------------------------
  ;; Returns 1 iff the funcref-typed null produced by `ref.null func` is
  ;; recognized as null by `ref.is_null`.
  (func (export "null_func_is_null") (result i32)
    (ref.is_null (ref.null func)))

  ;; Same for externref.
  (func (export "null_extern_is_null") (result i32)
    (ref.is_null (ref.null extern)))

  ;; Pass an externref in; report whether it's null.
  (func (export "is_null_param") (param $r externref) (result i32)
    (ref.is_null (local.get $r)))

  ;; --- ref.func -----------------------------------------------------
  ;; ref.func picks up f1, stores into funcref table slot 0, then calls
  ;; through it to verify the funcref round-trips correctly.
  (func (export "ref_func_via_table") (result i32)
    (table.set 0 (i32.const 0) (ref.func $f1))
    (call_indirect 0 (type $i32fn) (i32.const 0)))

  ;; --- table.set / table.get / call_indirect through a funcref table -
  ;; Set slot $i with ref.func $fN selected by a constant funcidx.
  ;; (Sysl-style minimal API surface — full host-side ref.func builders
  ;; would be more flexible.)
  (func (export "set_funcref_f0") (param $i i32)
    (table.set 0 (local.get $i) (ref.func $f0)))
  (func (export "set_funcref_f3") (param $i i32)
    (table.set 0 (local.get $i) (ref.func $f3)))
  (func (export "set_funcref_null") (param $i i32)
    (table.set 0 (local.get $i) (ref.null func)))

  ;; Read tfunc(slot) and report whether it's null (1 if null, 0 if not).
  (func (export "funcref_slot_is_null") (param $i i32) (result i32)
    (ref.is_null (table.get 0 (local.get $i))))

  ;; Dispatch through tfunc(slot); the helper returns its own funcidx
  ;; as an i32 so the caller can confirm the right ref landed there.
  (func (export "read_funcref") (param $i i32) (result i32)
    (call_indirect 0 (type $i32fn) (local.get $i)))

  ;; --- externref table accessors ------------------------------------
  ;; The host puts/gets externrefs through these. Pass-through is enough
  ;; to verify that an externref survives table.set + table.get unchanged.
  (func (export "extern_set") (param $i i32) (param $r externref)
    (table.set 1 (local.get $i) (local.get $r)))

  (func (export "extern_get") (param $i i32) (result externref)
    (table.get 1 (local.get $i)))

  ;; --- table.size / table.grow / table.fill -------------------------
  (func (export "tfunc_size")   (result i32) (table.size 0))
  (func (export "textern_size") (result i32) (table.size 1))

  ;; Grow tfunc by $delta slots, filling new slots with a null funcref.
  ;; Returns the previous size on success, -1 on cap overflow.
  (func (export "tfunc_grow_null") (param $delta i32) (result i32)
    (table.grow 0 (ref.null func) (local.get $delta)))

  ;; Grow textern by $delta slots, filling new slots with an externref
  ;; supplied by the caller. Returns previous size or -1.
  (func (export "textern_grow") (param $r externref) (param $delta i32) (result i32)
    (table.grow 1 (local.get $r) (local.get $delta)))

  ;; Fill tfunc range [dst, dst+n) with f0 funcrefs.
  (func (export "tfunc_fill_f0") (param $dst i32) (param $n i32)
    (table.fill 0 (local.get $dst) (ref.func $f0) (local.get $n)))

  ;; Fill textern range [dst, dst+n) with an externref the host supplies.
  (func (export "textern_fill") (param $dst i32) (param $r externref) (param $n i32)
    (table.fill 1 (local.get $dst) (local.get $r) (local.get $n))))
