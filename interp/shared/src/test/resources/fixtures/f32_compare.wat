;; All six f32 ordered comparisons (0x5B–0x60). Each returns i32 1/0.
(module
  (func (export "f32_eq") (param f32 f32) (result i32) local.get 0 local.get 1 f32.eq)
  (func (export "f32_ne") (param f32 f32) (result i32) local.get 0 local.get 1 f32.ne)
  (func (export "f32_lt") (param f32 f32) (result i32) local.get 0 local.get 1 f32.lt)
  (func (export "f32_gt") (param f32 f32) (result i32) local.get 0 local.get 1 f32.gt)
  (func (export "f32_le") (param f32 f32) (result i32) local.get 0 local.get 1 f32.le)
  (func (export "f32_ge") (param f32 f32) (result i32) local.get 0 local.get 1 f32.ge))
