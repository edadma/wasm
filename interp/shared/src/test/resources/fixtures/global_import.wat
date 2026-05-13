;; Imports a global — exercises the parser's "skip global import" branch
;; (consumes the valtype byte and the mutability byte).
(module
  (import "env" "g" (global i32))
  (func (export "f") (result i32) i32.const 3))
