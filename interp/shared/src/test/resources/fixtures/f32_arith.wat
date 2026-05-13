;; f32 arithmetic primitives. The Scala side picks specific input pairs
;; (e.g. classic IEEE-754 corners) and asserts exact bit-pattern results.
(module
  (func (export "f32_add") (param f32 f32) (result f32) local.get 0 local.get 1 f32.add)
  (func (export "f32_sub") (param f32 f32) (result f32) local.get 0 local.get 1 f32.sub)
  (func (export "f32_mul") (param f32 f32) (result f32) local.get 0 local.get 1 f32.mul)
  (func (export "f32_div") (param f32 f32) (result f32) local.get 0 local.get 1 f32.div)

  (func (export "f32_min") (param f32 f32) (result f32) local.get 0 local.get 1 f32.min)
  (func (export "f32_max") (param f32 f32) (result f32) local.get 0 local.get 1 f32.max)
  (func (export "f32_copysign") (param f32 f32) (result f32) local.get 0 local.get 1 f32.copysign))
