;; i32.rem_s plus its special "MIN_INT % -1 == 0" rule (WASM spec defines
;; this case as producing 0 rather than trapping, unlike Java's `%`).
(module
  (func (export "i32_rem_s") (param i32 i32) (result i32) local.get 0 local.get 1 i32.rem_s))
