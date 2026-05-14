;; Block with multi-result exited via `br_if`. Returns (max, min) of
;; the two params. The br_if path tests the branch unwind preserving
;; branchArity = 2 values; the fall-through path tests the normal
;; multi-result-end. Together they pin both block-end exits with
;; non-trivial multi-value carry.
(module
  (func (export "max_min") (param $a i32) (param $b i32) (result i32 i32)
    (block (result i32 i32)
      local.get $a
      local.get $b
      local.get $a
      local.get $b
      i32.ge_s
      br_if 0           ;; a >= b: exit with (a, b) = (max, min) already on stack
      drop              ;; else: replace the [a, b] pair with [b, a]
      drop
      local.get $b
      local.get $a)))
