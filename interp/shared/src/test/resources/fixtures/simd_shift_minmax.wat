;; Phase 8.E.E — SIMD shifts + min/max.
;;
;; One export per chunk-E op. Shifts take `(v128, i32)` (the i32 is a
;; regular stack operand, not an immediate). Min/max take `(v128, v128)`.
;; i64x2 has shifts but no min/max in the SIMD spec.
(module
  ;; --- i8x16 shifts ---------------------------------------------------

  (func (export "shl_i8x16") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i8x16.shl)

  (func (export "shr_s_i8x16") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i8x16.shr_s)

  (func (export "shr_u_i8x16") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i8x16.shr_u)

  ;; --- i8x16 min/max --------------------------------------------------

  (func (export "min_s_i8x16") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i8x16.min_s)

  (func (export "min_u_i8x16") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i8x16.min_u)

  (func (export "max_s_i8x16") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i8x16.max_s)

  (func (export "max_u_i8x16") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i8x16.max_u)

  ;; --- i16x8 shifts ---------------------------------------------------

  (func (export "shl_i16x8") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i16x8.shl)

  (func (export "shr_s_i16x8") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i16x8.shr_s)

  (func (export "shr_u_i16x8") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i16x8.shr_u)

  ;; --- i16x8 min/max --------------------------------------------------

  (func (export "min_s_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.min_s)

  (func (export "min_u_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.min_u)

  (func (export "max_s_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.max_s)

  (func (export "max_u_i16x8") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i16x8.max_u)

  ;; --- i32x4 shifts ---------------------------------------------------

  (func (export "shl_i32x4") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i32x4.shl)

  (func (export "shr_s_i32x4") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i32x4.shr_s)

  (func (export "shr_u_i32x4") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i32x4.shr_u)

  ;; --- i32x4 min/max --------------------------------------------------

  (func (export "min_s_i32x4") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i32x4.min_s)

  (func (export "min_u_i32x4") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i32x4.min_u)

  (func (export "max_s_i32x4") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i32x4.max_s)

  (func (export "max_u_i32x4") (param v128 v128) (result v128)
    local.get 0
    local.get 1
    i32x4.max_u)

  ;; --- i64x2 shifts (no min/max in spec) ------------------------------

  (func (export "shl_i64x2") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i64x2.shl)

  (func (export "shr_s_i64x2") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i64x2.shr_s)

  (func (export "shr_u_i64x2") (param v128 i32) (result v128)
    local.get 0
    local.get 1
    i64x2.shr_u))
