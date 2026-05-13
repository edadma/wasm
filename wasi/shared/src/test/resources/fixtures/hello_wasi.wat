;; Phase 7.A — canonical WASI hello world. Imports `fd_write`, exports
;; `memory` (per the WASI ABI, even though our shim doesn't read it via
;; the export — host functions get `memory` directly), and `_start`.
;;
;; The "Hello, WASI!\n" string is 13 bytes, planted at offset 8 so the
;; iovec descriptor lives unobstructed at offset 0. Layout:
;;
;;   memory[0..4)    iovec.buf       = 8
;;   memory[4..8)    iovec.buf_len   = 13
;;   memory[8..21)   "Hello, WASI!\n"
;;   memory[24..28)  nwritten        ← stamped by fd_write
;;
;; The errno return is dropped — the test reads the collecting stdout
;; sink directly to verify the write landed.
(module
  (import "wasi_snapshot_preview1" "fd_write"
    (func $fd_write (param i32 i32 i32 i32) (result i32)))

  (memory 1)
  (export "memory" (memory 0))

  (data (i32.const 8) "Hello, WASI!\n")

  (func (export "_start")
    ;; iovec[0] = { buf=8, buf_len=13 }
    i32.const 0
    i32.const 8
    i32.store
    i32.const 4
    i32.const 13
    i32.store

    ;; fd_write(stdout=1, iovs=0, iovs_len=1, nwritten=24)
    i32.const 1
    i32.const 0
    i32.const 1
    i32.const 24
    call $fd_write
    drop)

  ;; Test hook: read the `nwritten` slot so the test can assert the
  ;; expected byte count without doing another fd_write.
  (func (export "nwritten") (result i32)
    i32.const 24
    i32.load))
