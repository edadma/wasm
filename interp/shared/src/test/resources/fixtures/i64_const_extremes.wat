;; Min/max i64 constants — pin the SLEB64 decoder against the boundaries
;; (full 10-byte encodings).
(module
  (func (export "i64_max") (result i64)
    i64.const 9223372036854775807)   ;; Long.MaxValue

  (func (export "i64_min") (result i64)
    i64.const -9223372036854775808)  ;; Long.MinValue

  (func (export "i64_minus_one") (result i64)
    i64.const -1))
