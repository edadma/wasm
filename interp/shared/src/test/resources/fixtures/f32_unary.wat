;; f32 unary ops: abs, neg, ceil, floor, trunc, nearest, sqrt.
;; Each takes one f32 param, returns one f32.
(module
  (func (export "f32_abs")     (param f32) (result f32) local.get 0 f32.abs)
  (func (export "f32_neg")     (param f32) (result f32) local.get 0 f32.neg)
  (func (export "f32_ceil")    (param f32) (result f32) local.get 0 f32.ceil)
  (func (export "f32_floor")   (param f32) (result f32) local.get 0 f32.floor)
  (func (export "f32_trunc")   (param f32) (result f32) local.get 0 f32.trunc)
  (func (export "f32_nearest") (param f32) (result f32) local.get 0 f32.nearest)
  (func (export "f32_sqrt")    (param f32) (result f32) local.get 0 f32.sqrt))
