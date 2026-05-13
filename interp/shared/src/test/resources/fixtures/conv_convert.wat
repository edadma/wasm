;; Int → float conversions. Eight ops, none trap. May round (precision
;; loss) for Long → Float, where Float has only 24 bits of mantissa.
;;
;; Signed vs unsigned diverge once the high bit is set:
;;   * (i32) 0x80000000 as i32_s = -2^31, as i32_u = 2^31.
;;   * (i64) Long.MinValue as i64_s = -2^63, as i64_u = 2^63.
(module
  (func (export "f32_convert_i32_s") (param i32) (result f32) local.get 0 f32.convert_i32_s)
  (func (export "f32_convert_i32_u") (param i32) (result f32) local.get 0 f32.convert_i32_u)
  (func (export "f32_convert_i64_s") (param i64) (result f32) local.get 0 f32.convert_i64_s)
  (func (export "f32_convert_i64_u") (param i64) (result f32) local.get 0 f32.convert_i64_u)
  (func (export "f64_convert_i32_s") (param i32) (result f64) local.get 0 f64.convert_i32_s)
  (func (export "f64_convert_i32_u") (param i32) (result f64) local.get 0 f64.convert_i32_u)
  (func (export "f64_convert_i64_s") (param i64) (result f64) local.get 0 f64.convert_i64_s)
  (func (export "f64_convert_i64_u") (param i64) (result f64) local.get 0 f64.convert_i64_u))
