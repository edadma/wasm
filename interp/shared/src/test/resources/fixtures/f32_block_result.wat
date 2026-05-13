;; Block whose result type is f32. Exercises the f32 blocktype byte (0x7D),
;; which used to be rejected before Phase 1.2.
(module
  (func (export "f32_block_result") (result f32)
    (block (result f32)
      f32.const 1.5)))
