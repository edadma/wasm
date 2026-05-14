;; Phase 7.C — clock_time_get / random_get / fd_close passthrough.
;;
;; The test plants/reads memory through write_byte / write_i32 / load_byte
;; / load_i32 / load_i64 and invokes one syscall at a time via the
;; call_* wrappers. Same shape as wasi_passthrough.wat / wasi_args.wat —
;; the fixture stays generic and the test owns the call shapes.
;;
;; Note: clock_time_get's second arg (precision) is an i64; the
;; wrapper plumbs it through unchanged. load_i64 lets the test read
;; back the 8-byte nanosecond timestamp the shim wrote.
(module
  (import "wasi_snapshot_preview1" "clock_time_get"
    (func $clock_time_get (param i32 i64 i32) (result i32)))
  (import "wasi_snapshot_preview1" "random_get"
    (func $random_get (param i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_close"
    (func $fd_close (param i32) (result i32)))

  (memory 1)
  (export "memory" (memory 0))

  (func (export "call_clock_time_get")
        (param $clock_id i32) (param $precision i64) (param $time_ptr i32)
        (result i32)
    local.get $clock_id
    local.get $precision
    local.get $time_ptr
    call $clock_time_get)

  (func (export "call_random_get")
        (param $buf i32) (param $buf_len i32) (result i32)
    local.get $buf
    local.get $buf_len
    call $random_get)

  (func (export "call_fd_close") (param $fd i32) (result i32)
    local.get $fd
    call $fd_close)

  (func (export "write_byte") (param $addr i32) (param $b i32)
    local.get $addr
    local.get $b
    i32.store8)

  (func (export "load_byte") (param $addr i32) (result i32)
    local.get $addr
    i32.load8_u)

  (func (export "load_i32") (param $addr i32) (result i32)
    local.get $addr
    i32.load)

  (func (export "load_i64") (param $addr i32) (result i64)
    local.get $addr
    i64.load))
