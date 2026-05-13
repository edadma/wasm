;; f64 arithmetic primitives. The Scala side picks specific input pairs
;; (e.g. classic IEEE-754 corners) and asserts exact bit-pattern results.
(module
  (func (export "f64_add") (param f64 f64) (result f64) local.get 0 local.get 1 f64.add)
  (func (export "f64_sub") (param f64 f64) (result f64) local.get 0 local.get 1 f64.sub)
  (func (export "f64_mul") (param f64 f64) (result f64) local.get 0 local.get 1 f64.mul)
  (func (export "f64_div") (param f64 f64) (result f64) local.get 0 local.get 1 f64.div)

  (func (export "f64_min") (param f64 f64) (result f64) local.get 0 local.get 1 f64.min)
  (func (export "f64_max") (param f64 f64) (result f64) local.get 0 local.get 1 f64.max)
  (func (export "f64_copysign") (param f64 f64) (result f64) local.get 0 local.get 1 f64.copysign))
