;; Phase 8.E.F — SIMD float arithmetic.
;;
;; One export per chunk-F op. Unary `(v128) -> (v128)` covers rounding +
;; abs/neg/sqrt. Binary `(v128, v128) -> (v128)` covers add/sub/mul/div +
;; min/max/pmin/pmax. Same `local.get`/op pattern as the chunk-D/E
;; fixtures.
(module
  ;; --- f32x4 unary ----------------------------------------------------

  (func (export "ceil_f32x4")    (param v128) (result v128)
    local.get 0  f32x4.ceil)

  (func (export "floor_f32x4")   (param v128) (result v128)
    local.get 0  f32x4.floor)

  (func (export "trunc_f32x4")   (param v128) (result v128)
    local.get 0  f32x4.trunc)

  (func (export "nearest_f32x4") (param v128) (result v128)
    local.get 0  f32x4.nearest)

  (func (export "abs_f32x4")     (param v128) (result v128)
    local.get 0  f32x4.abs)

  (func (export "neg_f32x4")     (param v128) (result v128)
    local.get 0  f32x4.neg)

  (func (export "sqrt_f32x4")    (param v128) (result v128)
    local.get 0  f32x4.sqrt)

  ;; --- f32x4 binary ---------------------------------------------------

  (func (export "add_f32x4")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f32x4.add)

  (func (export "sub_f32x4")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f32x4.sub)

  (func (export "mul_f32x4")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f32x4.mul)

  (func (export "div_f32x4")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f32x4.div)

  (func (export "min_f32x4")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f32x4.min)

  (func (export "max_f32x4")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f32x4.max)

  (func (export "pmin_f32x4") (param v128 v128) (result v128)
    local.get 0  local.get 1  f32x4.pmin)

  (func (export "pmax_f32x4") (param v128 v128) (result v128)
    local.get 0  local.get 1  f32x4.pmax)

  ;; --- f64x2 unary ----------------------------------------------------

  (func (export "ceil_f64x2")    (param v128) (result v128)
    local.get 0  f64x2.ceil)

  (func (export "floor_f64x2")   (param v128) (result v128)
    local.get 0  f64x2.floor)

  (func (export "trunc_f64x2")   (param v128) (result v128)
    local.get 0  f64x2.trunc)

  (func (export "nearest_f64x2") (param v128) (result v128)
    local.get 0  f64x2.nearest)

  (func (export "abs_f64x2")     (param v128) (result v128)
    local.get 0  f64x2.abs)

  (func (export "neg_f64x2")     (param v128) (result v128)
    local.get 0  f64x2.neg)

  (func (export "sqrt_f64x2")    (param v128) (result v128)
    local.get 0  f64x2.sqrt)

  ;; --- f64x2 binary ---------------------------------------------------

  (func (export "add_f64x2")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f64x2.add)

  (func (export "sub_f64x2")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f64x2.sub)

  (func (export "mul_f64x2")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f64x2.mul)

  (func (export "div_f64x2")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f64x2.div)

  (func (export "min_f64x2")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f64x2.min)

  (func (export "max_f64x2")  (param v128 v128) (result v128)
    local.get 0  local.get 1  f64x2.max)

  (func (export "pmin_f64x2") (param v128 v128) (result v128)
    local.get 0  local.get 1  f64x2.pmin)

  (func (export "pmax_f64x2") (param v128 v128) (result v128)
    local.get 0  local.get 1  f64x2.pmax))
