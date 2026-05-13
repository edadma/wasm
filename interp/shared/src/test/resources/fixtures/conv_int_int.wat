;; Integer ↔ integer conversions: i32.wrap_i64 + i64.extend_i32_{s,u}.
;; None of these trap; they're pure bit narrowing/widening.
(module
  (func (export "wrap_i64")        (param i64) (result i32) local.get 0 i32.wrap_i64)
  (func (export "extend_i32_s")    (param i32) (result i64) local.get 0 i64.extend_i32_s)
  (func (export "extend_i32_u")    (param i32) (result i64) local.get 0 i64.extend_i32_u))
