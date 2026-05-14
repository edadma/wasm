;; Phase 5/multi-value — function with a multi-result signature.
;; Returns two i32s; callers see both on the result Seq. The simplest
;; case to verify that function-level multi-value works end-to-end.
(module
  (func (export "two_values") (result i32 i32)
    i32.const 7
    i32.const 11))
