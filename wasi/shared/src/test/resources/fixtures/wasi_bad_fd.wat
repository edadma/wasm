;; Phase 7.A — fd_write to an unknown fd. Returns the errno verbatim
;; (no `drop`) so the test can assert it equals 8 (EBADF). Phase 7.A
;; supports only fd 1 (stdout) and fd 2 (stderr); anything else returns
;; EBADF without touching the iovec or stamping `nwritten`.
(module
  (import "wasi_snapshot_preview1" "fd_write"
    (func $fd_write (param i32 i32 i32 i32) (result i32)))

  (memory 1)
  (export "memory" (memory 0))

  (data (i32.const 8) "x")

  ;; Plant iovec { 8, 1 }, attempt write to fd 99, return errno.
  (func (export "try_fd") (param $fd i32) (result i32)
    i32.const 0
    i32.const 8
    i32.store
    i32.const 4
    i32.const 1
    i32.store
    local.get $fd
    i32.const 0
    i32.const 1
    i32.const 16
    call $fd_write))
