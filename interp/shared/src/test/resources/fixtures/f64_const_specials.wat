;; f64.const SLEB-free immediate decoder pinned against IEEE-754 specials:
;; pi, e, +inf, -inf, NaN, positive zero, negative zero, smallest normal
;; (2^-1022) and smallest denormal (2^-1074). These exercise the 8-byte
;; raw little-endian encoding end-to-end.
(module
  (func (export "f64_pi")     (result f64) f64.const 3.141592653589793)
  (func (export "f64_e")      (result f64) f64.const 2.718281828459045)
  (func (export "f64_neg_pi") (result f64) f64.const -3.141592653589793)

  ;; Smallest positive normal f64: 2^-1022.
  (func (export "f64_min_normal") (result f64) f64.const 0x1p-1022)

  ;; Smallest positive denormal f64: 2^-1074.
  (func (export "f64_min_denormal") (result f64) f64.const 0x1p-1074)

  (func (export "f64_pos_inf") (result f64) f64.const inf)
  (func (export "f64_neg_inf") (result f64) f64.const -inf)

  (func (export "f64_pos_zero") (result f64) f64.const 0.0)
  (func (export "f64_neg_zero") (result f64) f64.const -0.0)

  ;; Canonical NaN (positive sign, payload all zeros except quiet bit).
  (func (export "f64_nan") (result f64) f64.const nan))
