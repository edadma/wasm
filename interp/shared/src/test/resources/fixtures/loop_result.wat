;; A `loop` with an i32 result type (not just void). The loop's body must
;; leave exactly one i32 on the stack when the natural fall-through `end`
;; is reached. Every existing fixture uses void loops; this one fires the
;; `Loop` label code with `resultArity = 1`.
(module
  (func (export "loop_result") (result i32)
    (loop (result i32)
      i32.const 42)))
