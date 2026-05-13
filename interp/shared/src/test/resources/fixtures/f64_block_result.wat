;; Block whose result type is f64. Exercises the f64 blocktype byte (0x7C),
;; which used to be rejected before Phase 1.3.
(module
  (func (export "f64_block_result") (result f64)
    (block (result f64)
      f64.const 1.5)))
