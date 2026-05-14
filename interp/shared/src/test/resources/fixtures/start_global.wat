;; Section 8 (Start) — a `() -> ()` function that writes a mutable
;; global. The function is referenced by `(start ...)` so wat2wasm
;; emits section 8 with funcidx = 0. After `Runtime.instantiate`
;; returns successfully, `peek` reads back the global and observes
;; the side effect — proving the start function actually ran.
;;
;; Function 0 ($init): () -> ()           — start target
;; Function 1 (peek):  () -> i32           — exported, reads $g
(module
  (global $g (mut i32) (i32.const 0))
  (func $init
    i32.const 42
    global.set $g)
  (func (export "peek") (result i32)
    global.get $g)
  (start $init))
