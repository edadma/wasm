;; Block with both params and result. The two function params are
;; pushed before the block; the block consumes them as its operand
;; frame (paramArity = 2) and produces one i32. Verifies that
;; label.stackHeight is recorded *below* the params so the function's
;; outer stack stays consistent.
(module
  (func (export "add_via_block") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    (block (param i32 i32) (result i32)
      i32.add)))
