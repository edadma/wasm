;; Block whose result type is i64. Exercises the i64 blocktype byte (0x7E)
;; which used to be rejected; now lookup of arity 1 + result tracking on
;; fall-through must preserve the I64 value.
(module
  (func (export "i64_block_result") (result i64)
    (block (result i64)
      i64.const 9876543210)))
