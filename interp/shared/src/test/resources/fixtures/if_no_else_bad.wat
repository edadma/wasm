;; Regression for the `if`-without-else validator gap surfaced by the
;; W3C spec runner against testsuite/if.wast lines 934, 953, 966, 972.
;;
;; The form `(if (result i32) (then EXPR))` (no else) is invalid when
;; the block's params don't equal its results: the implicit empty else-
;; branch passes through the params, so an empty else can only satisfy
;; the block type when `startTypes == endTypes`. Here params=[] and
;; results=[i32], so the implicit else has type `[]→[]` but is required
;; to have type `[]→[i32]`. Previously accepted; the validator now
;; rejects it at instantiation.
;;
;; Built with `wat2wasm --no-check`.
(module
  (func (export "bad_if") (result i32)
    (i32.const 1)
    (if (result i32)
      (then (i32.const 0)))))
