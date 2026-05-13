;; IEEE-754 edge cases: divide-by-zero (Inf or NaN, never trap), min/max with
;; NaN (NaN propagates), min/max with signed zeros (sign matters), Inf
;; arithmetic, abs/neg on NaN, copysign sign-bit transfer.
(module
  ;; 1/0 returns +inf, 0/0 returns NaN, (-1)/0 returns -inf — none trap.
  (func (export "div_pos_zero") (param f32) (result f32) local.get 0 f32.const 0.0 f32.div)
  (func (export "div_neg_zero") (param f32) (result f32) local.get 0 f32.const -0.0 f32.div)

  ;; Inf arithmetic
  (func (export "inf_plus_neg_inf") (result f32) f32.const inf f32.const -inf f32.add)
  (func (export "inf_times_zero")   (result f32) f32.const inf f32.const 0.0 f32.mul)

  ;; min/max behavior — driven from Scala
  (func (export "f32_min") (param f32 f32) (result f32) local.get 0 local.get 1 f32.min)
  (func (export "f32_max") (param f32 f32) (result f32) local.get 0 local.get 1 f32.max)

  ;; copysign exposed for sign-bit transfer tests
  (func (export "f32_copysign") (param f32 f32) (result f32) local.get 0 local.get 1 f32.copysign)

  ;; sqrt of -1 should be NaN, not a trap.
  (func (export "sqrt_neg_one") (result f32) f32.const -1.0 f32.sqrt))
