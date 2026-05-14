;; if/else producing two i32s. Exercises both arms of the if dispatch
;; with multi-result encoding — same code path as a block but with the
;; condition pop and the else-branch dispatch.
(module
  (func (export "if_two") (param $cond i32) (result i32 i32)
    local.get $cond
    (if (result i32 i32)
      (then
        i32.const 10
        i32.const 20)
      (else
        i32.const 100
        i32.const 200))))
