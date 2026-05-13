;; Memory with an explicit max limit — exercises the `flag & 0x01 != 0`
;; branch of `readLimits` that bare-memory fixtures don't reach.
(module
  (memory 1 4)
  (func (export "size_at_zero") (result i32)
    i32.const 0
    i32.load))
