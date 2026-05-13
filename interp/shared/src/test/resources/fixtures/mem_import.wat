;; Imports a memory — exercises the parser's "skip memory import" branch.
;; Same shape as table_import.wat (silently discarded by the parser).
(module
  (import "env" "mem" (memory 1))
  (func (export "f") (result i32) i32.const 2))
