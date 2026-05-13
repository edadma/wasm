;; Phase 7.A — bare passthrough to `fd_write`. The test calls
;; `fd_write_raw(fd, iovs, iovs_len, nwritten)` directly so it can pin
;; any errno path (EBADF / EFAULT / success) without baking specific
;; arg shapes into the fixture.
;;
;; Memory layout doesn't matter to the fixture itself — the test plants
;; whatever it needs into the iovec / data region by calling write_i32
;; (and reads via load_i32) before invoking fd_write_raw.
(module
  (import "wasi_snapshot_preview1" "fd_write"
    (func $fd_write (param i32 i32 i32 i32) (result i32)))

  (memory 1)
  (export "memory" (memory 0))

  (func (export "fd_write_raw")
        (param $fd i32) (param $iovs i32) (param $iovs_len i32) (param $nwritten i32)
        (result i32)
    local.get $fd
    local.get $iovs
    local.get $iovs_len
    local.get $nwritten
    call $fd_write)

  (func (export "write_i32") (param $addr i32) (param $v i32)
    local.get $addr
    local.get $v
    i32.store)

  (func (export "load_i32") (param $addr i32) (result i32)
    local.get $addr
    i32.load)

  (func (export "write_byte") (param $addr i32) (param $b i32)
    local.get $addr
    local.get $b
    i32.store8))
