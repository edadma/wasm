;; f32.const SLEB-free immediate decoder pinned against IEEE-754 specials:
;; pi, e, +inf, -inf, NaN, positive zero, negative zero.
(module
  (func (export "f32_pi")     (result f32) f32.const 3.14159265)
  (func (export "f32_e")      (result f32) f32.const 2.71828183)
  (func (export "f32_neg_pi") (result f32) f32.const -3.14159265)

  ;; Smallest positive normal f32: 2^-126.
  (func (export "f32_min_normal") (result f32) f32.const 0x1p-126)

  ;; Smallest positive denormal f32: 2^-149.
  (func (export "f32_min_denormal") (result f32) f32.const 0x1p-149)

  (func (export "f32_pos_inf") (result f32) f32.const inf)
  (func (export "f32_neg_inf") (result f32) f32.const -inf)

  (func (export "f32_pos_zero") (result f32) f32.const 0.0)
  (func (export "f32_neg_zero") (result f32) f32.const -0.0)

  ;; Canonical NaN (positive sign, payload all zeros except quiet bit).
  (func (export "f32_nan") (result f32) f32.const nan))
