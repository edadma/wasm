;; Phase 8.E.G.1 — bitwise + reductions.
;; 15 exports — one per chunk-G.1 op. Each takes one or more v128
;; arguments and returns either v128 (bitwise) or i32 (reductions).

(module
  ;; --- bitwise --------------------------------------------------------

  (func (export "not_v128") (param v128) (result v128)
    local.get 0
    v128.not)

  (func (export "and_v128") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    v128.and)

  (func (export "andnot_v128") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    v128.andnot)

  (func (export "or_v128") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    v128.or)

  (func (export "xor_v128") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    v128.xor)

  (func (export "bitselect_v128") (param v128 v128 v128) (result v128)
    local.get 0
    local.get 1
    local.get 2
    v128.bitselect)

  ;; --- reductions -----------------------------------------------------

  (func (export "any_true_v128") (param v128) (result i32)
    local.get 0
    v128.any_true)

  (func (export "all_true_i8x16") (param v128) (result i32)
    local.get 0
    i8x16.all_true)

  (func (export "all_true_i16x8") (param v128) (result i32)
    local.get 0
    i16x8.all_true)

  (func (export "all_true_i32x4") (param v128) (result i32)
    local.get 0
    i32x4.all_true)

  (func (export "all_true_i64x2") (param v128) (result i32)
    local.get 0
    i64x2.all_true)

  (func (export "bitmask_i8x16") (param v128) (result i32)
    local.get 0
    i8x16.bitmask)

  (func (export "bitmask_i16x8") (param v128) (result i32)
    local.get 0
    i16x8.bitmask)

  (func (export "bitmask_i32x4") (param v128) (result i32)
    local.get 0
    i32x4.bitmask)

  (func (export "bitmask_i64x2") (param v128) (result i32)
    local.get 0
    i64x2.bitmask))
