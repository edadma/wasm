;; Constant-folded arithmetic: ((10 * 3) + 2 - 7) / 5 = 5.
;; Exercises i32.const, i32.mul, i32.add, i32.sub, i32.div_s.
(module
  (func (export "test_arith") (result i32)
    i32.const 10
    i32.const 3
    i32.mul
    i32.const 2
    i32.add
    i32.const 7
    i32.sub
    i32.const 5
    i32.div_s))
