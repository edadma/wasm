;; If cond != 0 the block exits early carrying 100; otherwise it falls through
;; and produces 200. Exercises `br` (via br_if 0) targeting a result-typed block.
(module
  (func (export "test_br") (param $cond i32) (result i32)
    (block (result i32)
      i32.const 100
      local.get $cond
      br_if 0     ;; pops cond; if non-zero, exits block carrying 100
      drop
      i32.const 200)))
