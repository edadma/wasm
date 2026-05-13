;; Phase 7.A — multi-iovec fd_write. Three slices ("foo", "BAR", "baz")
;; assembled at distinct memory addresses, fed through fd_write as three
;; iovecs in one call. Pins:
;;   - bytes land in iovec order (concat result is "fooBARbaz")
;;   - the `nwritten` slot reflects the sum of all three buf_lens (9)
;;
;; Layout:
;;   memory[0..24)   three iovec descriptors (8 bytes each)
;;   memory[32..36)  nwritten
;;   memory[64..67)  "foo"
;;   memory[67..70)  "BAR"
;;   memory[70..73)  "baz"
(module
  (import "wasi_snapshot_preview1" "fd_write"
    (func $fd_write (param i32 i32 i32 i32) (result i32)))

  (memory 1)
  (export "memory" (memory 0))

  (data (i32.const 64) "foo")
  (data (i32.const 67) "BAR")
  (data (i32.const 70) "baz")

  (func (export "_start")
    ;; iovec[0] = { 64, 3 }
    i32.const 0
    i32.const 64
    i32.store
    i32.const 4
    i32.const 3
    i32.store
    ;; iovec[1] = { 67, 3 }
    i32.const 8
    i32.const 67
    i32.store
    i32.const 12
    i32.const 3
    i32.store
    ;; iovec[2] = { 70, 3 }
    i32.const 16
    i32.const 70
    i32.store
    i32.const 20
    i32.const 3
    i32.store

    ;; fd_write(1, 0, 3, 32)
    i32.const 1
    i32.const 0
    i32.const 3
    i32.const 32
    call $fd_write
    drop)

  (func (export "nwritten") (result i32)
    i32.const 32
    i32.load))
