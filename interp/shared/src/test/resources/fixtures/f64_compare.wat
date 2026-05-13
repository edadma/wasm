;; All six f64 ordered comparisons (0x61–0x66). Each returns i32 1/0.
(module
  (func (export "f64_eq") (param f64 f64) (result i32) local.get 0 local.get 1 f64.eq)
  (func (export "f64_ne") (param f64 f64) (result i32) local.get 0 local.get 1 f64.ne)
  (func (export "f64_lt") (param f64 f64) (result i32) local.get 0 local.get 1 f64.lt)
  (func (export "f64_gt") (param f64 f64) (result i32) local.get 0 local.get 1 f64.gt)
  (func (export "f64_le") (param f64 f64) (result i32) local.get 0 local.get 1 f64.le)
  (func (export "f64_ge") (param f64 f64) (result i32) local.get 0 local.get 1 f64.ge))
