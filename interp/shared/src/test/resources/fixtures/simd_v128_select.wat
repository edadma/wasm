;; Regression for the untyped `select` (0x1B) rejecting v128 operands,
;; surfaced by simd_select.wast in the W3C spec runner. Spec wording
;; treats v128 as a numtype for the purpose of `select`; the typed
;; `select t*` form (0x1C) was always reserved for reftype operands.
(module
  (func (export "vselect") (param $a v128) (param $b v128) (param $cond i32) (result v128)
    (select (local.get $a) (local.get $b) (local.get $cond))))
