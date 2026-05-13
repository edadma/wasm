;; Explicit `return` — used for early exit from the middle of a function.
;; When $a > 0, return 42 before the final i32.const 0 is reached.
(module
  (func (export "early_return") (param $a i32) (result i32)
    local.get $a
    i32.const 0
    i32.gt_s
    (if
      (then
        i32.const 42
        return))
    i32.const 0))
