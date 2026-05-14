;; Phase 8.E.D — SIMD integer arithmetic.
;;
;; One export per chunk-D op. Each function takes one or two v128 operands
;; from the caller and emits the result. Tests construct the input bytes
;; and verify byte-for-byte on the result vector.
(module
  ;; --- i8x16 ----------------------------------------------------------

  (func (export "abs_i8x16") (param v128) (result v128)
    local.get 0
    i8x16.abs)

  (func (export "neg_i8x16") (param v128) (result v128)
    local.get 0
    i8x16.neg)

  (func (export "add_i8x16") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i8x16.add)

  (func (export "add_sat_s_i8x16") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i8x16.add_sat_s)

  (func (export "add_sat_u_i8x16") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i8x16.add_sat_u)

  (func (export "sub_i8x16") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i8x16.sub)

  (func (export "sub_sat_s_i8x16") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i8x16.sub_sat_s)

  (func (export "sub_sat_u_i8x16") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i8x16.sub_sat_u)

  (func (export "avgr_u_i8x16") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i8x16.avgr_u)

  ;; --- i16x8 ----------------------------------------------------------

  (func (export "abs_i16x8") (param v128) (result v128)
    local.get 0
    i16x8.abs)

  (func (export "neg_i16x8") (param v128) (result v128)
    local.get 0
    i16x8.neg)

  (func (export "add_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.add)

  (func (export "add_sat_s_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.add_sat_s)

  (func (export "add_sat_u_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.add_sat_u)

  (func (export "sub_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.sub)

  (func (export "sub_sat_s_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.sub_sat_s)

  (func (export "sub_sat_u_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.sub_sat_u)

  (func (export "mul_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.mul)

  (func (export "avgr_u_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.avgr_u)

  ;; --- i32x4 ----------------------------------------------------------

  (func (export "abs_i32x4") (param v128) (result v128)
    local.get 0
    i32x4.abs)

  (func (export "neg_i32x4") (param v128) (result v128)
    local.get 0
    i32x4.neg)

  (func (export "add_i32x4") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i32x4.add)

  (func (export "sub_i32x4") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i32x4.sub)

  (func (export "mul_i32x4") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i32x4.mul)

  ;; --- i64x2 ----------------------------------------------------------

  (func (export "abs_i64x2") (param v128) (result v128)
    local.get 0
    i64x2.abs)

  (func (export "neg_i64x2") (param v128) (result v128)
    local.get 0
    i64x2.neg)

  (func (export "add_i64x2") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i64x2.add)

  (func (export "sub_i64x2") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i64x2.sub)

  (func (export "mul_i64x2") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i64x2.mul)
)
