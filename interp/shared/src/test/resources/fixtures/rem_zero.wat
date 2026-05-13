;; i32.rem_s with a zero divisor must trap; arith_edge covered div by zero
;; and MIN%-1 but not the rem-by-zero path.
(module
  (func (export "rem_zero") (result i32)
    i32.const 7
    i32.const 0
    i32.rem_s))
