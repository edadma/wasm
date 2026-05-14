;; All five sign-extension proposal opcodes (Phase 7.D). Each export
;; takes a raw i32 / i64 and applies the corresponding extension; tests
;; pin both the positive-low-bit and the negative (high-bit-set) cases.
(module
  (func (export "i32_extend8_s") (param $v i32) (result i32)
    (local.get $v)
    (i32.extend8_s))
  (func (export "i32_extend16_s") (param $v i32) (result i32)
    (local.get $v)
    (i32.extend16_s))
  (func (export "i64_extend8_s") (param $v i64) (result i64)
    (local.get $v)
    (i64.extend8_s))
  (func (export "i64_extend16_s") (param $v i64) (result i64)
    (local.get $v)
    (i64.extend16_s))
  (func (export "i64_extend32_s") (param $v i64) (result i64)
    (local.get $v)
    (i64.extend32_s)))
