;; IEEE-754 edge cases at f64 width: divide-by-zero (Inf or NaN, never trap),
;; min/max with NaN (NaN propagates), min/max with signed zeros (sign
;; matters), Inf arithmetic, abs/neg on NaN, copysign sign-bit transfer,
;; sqrt of a negative.
(module
  ;; 1/0 returns +inf, 0/0 returns NaN, (-1)/0 returns -inf — none trap.
  (func (export "div_pos_zero") (param f64) (result f64) local.get 0 f64.const 0.0 f64.div)
  (func (export "div_neg_zero") (param f64) (result f64) local.get 0 f64.const -0.0 f64.div)

  ;; Inf arithmetic
  (func (export "inf_plus_neg_inf") (result f64) f64.const inf f64.const -inf f64.add)
  (func (export "inf_times_zero")   (result f64) f64.const inf f64.const 0.0 f64.mul)

  ;; min/max behavior — driven from Scala
  (func (export "f64_min") (param f64 f64) (result f64) local.get 0 local.get 1 f64.min)
  (func (export "f64_max") (param f64 f64) (result f64) local.get 0 local.get 1 f64.max)

  ;; copysign exposed for sign-bit transfer tests
  (func (export "f64_copysign") (param f64 f64) (result f64) local.get 0 local.get 1 f64.copysign)

  ;; sqrt of -1 should be NaN, not a trap.
  (func (export "sqrt_neg_one") (result f64) f64.const -1.0 f64.sqrt))
