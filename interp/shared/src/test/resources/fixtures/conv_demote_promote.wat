;; f32 ⇄ f64: demote rounds (Double has 11-bit exponent + 52-bit mantissa;
;; Float has 8 + 23), promote is exact. Both preserve sign and the NaN/Inf
;; nature of their operand. Neither traps.
(module
  (func (export "demote")  (param f64) (result f32) local.get 0 f32.demote_f64)
  (func (export "promote") (param f32) (result f64) local.get 0 f64.promote_f32))
