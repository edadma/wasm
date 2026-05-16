;; Regression for the memarg alignment validation bug surfaced by the
;; W3C spec runner against testsuite/align.wast. The validator now
;; enforces `align <= log2(natural-width)` for plain (non-atomic)
;; load/store ops; previously this check was silently skipped, so a
;; module like the one below (i32.load8_s with align=2 — but the
;; natural width is 1, so log2(1)=0) instantiated successfully.
;;
;; Built with `wat2wasm --no-check` since wabt would otherwise reject
;; the module up-front.
(module
  (memory 1)
  (func (export "bad_load") (param i32) (result i32)
    (i32.load8_s align=2 (local.get 0))))
