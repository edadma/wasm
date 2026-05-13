;; Reinterpret: pure bit-cast, no rounding, no traps. NaN payloads survive
;; the round-trip because we use the raw IEEE-754 bit conversions (java's
;; floatToRawIntBits / doubleToRawLongBits, not the canonicalising forms).
(module
  (func (export "i32_reinterpret_f32") (param f32) (result i32) local.get 0 i32.reinterpret_f32)
  (func (export "i64_reinterpret_f64") (param f64) (result i64) local.get 0 i64.reinterpret_f64)
  (func (export "f32_reinterpret_i32") (param i32) (result f32) local.get 0 f32.reinterpret_i32)
  (func (export "f64_reinterpret_i64") (param i64) (result f64) local.get 0 f64.reinterpret_i64))
