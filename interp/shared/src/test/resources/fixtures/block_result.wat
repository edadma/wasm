;; Block with an i32 result falling through naturally (no br). Distinct
;; from br_block.wat which exits the block via `br_if 0`.
(module
  (func (export "block_result") (result i32)
    (block (result i32)
      i32.const 17)))
