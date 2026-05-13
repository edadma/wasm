;; Imports a table — exercises the parser's "skip table import" branch.
;; The MVP interpreter doesn't model tables, so the parser silently
;; discards the import descriptor. Since we don't add the table to the
;; module's import list, the runtime never tries to resolve it.
(module
  (import "env" "tab" (table 0 funcref))
  (func (export "f") (result i32) i32.const 1))
