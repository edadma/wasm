;; `unreachable` must abort execution with the UnreachableExecuted error.
;; The function declares a result type but never produces one — the trap
;; fires before reaching any value-producing instruction.
(module
  (func (export "trap") (result i32)
    unreachable))
