;; Non-function exports (memory + global) — exercises the parser's
;; "silently ignore non-function export" branch. The function export `f`
;; is the only thing the runtime sees in the export table.
(module
  (memory (export "mem") 1)
  (global (export "g") i32 (i32.const 7))
  (func (export "f") (result i32) i32.const 4))
