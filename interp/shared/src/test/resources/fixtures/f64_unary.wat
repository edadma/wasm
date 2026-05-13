;; f64 unary ops: abs, neg, ceil, floor, trunc, nearest, sqrt.
;; Each takes one f64 param, returns one f64.
(module
  (func (export "f64_abs")     (param f64) (result f64) local.get 0 f64.abs)
  (func (export "f64_neg")     (param f64) (result f64) local.get 0 f64.neg)
  (func (export "f64_ceil")    (param f64) (result f64) local.get 0 f64.ceil)
  (func (export "f64_floor")   (param f64) (result f64) local.get 0 f64.floor)
  (func (export "f64_trunc")   (param f64) (result f64) local.get 0 f64.trunc)
  (func (export "f64_nearest") (param f64) (result f64) local.get 0 f64.nearest)
  (func (export "f64_sqrt")    (param f64) (result f64) local.get 0 f64.sqrt))
