;; `br` with a non-zero label index, plus unconditional `br` (vs. br_if).
;;
;; Inside $inner the labels are: 0 = $inner, 1 = $outer.
;; Note that values pushed *outside* a child block are not visible inside it
;; for branching purposes, so we push the carried value (100) inside $inner.
;;
;;   br_if 1  carries the top value (100) out to $outer when cond != 0
;;   the fall-through drops 100 and produces 200 instead
(module
  (func (export "nested") (param $cond i32) (result i32)
    (block $outer (result i32)
      (block $inner
        i32.const 100
        local.get $cond
        br_if 1
        drop)
      i32.const 200)))
