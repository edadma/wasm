;; Phase 7.E.1 — fd_prestat_get / fd_prestat_dir_name passthrough.
;;
;; Tests drive both syscalls directly via the call_* wrappers and read
;; back the bytes the shim planted with load_byte / load_i32. Same
;; shape as wasi_clock_random.wat / wasi_passthrough.wat — the fixture
;; stays generic and the test code owns the call patterns.
(module
  (import "wasi_snapshot_preview1" "fd_prestat_get"
    (func $fd_prestat_get (param i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_prestat_dir_name"
    (func $fd_prestat_dir_name (param i32 i32 i32) (result i32)))

  (memory 1)
  (export "memory" (memory 0))

  (func (export "call_prestat_get")
        (param $fd i32) (param $buf i32) (result i32)
    local.get $fd
    local.get $buf
    call $fd_prestat_get)

  (func (export "call_prestat_dir_name")
        (param $fd i32) (param $path_ptr i32) (param $path_len i32)
        (result i32)
    local.get $fd
    local.get $path_ptr
    local.get $path_len
    call $fd_prestat_dir_name)

  (func (export "load_byte") (param $addr i32) (result i32)
    local.get $addr
    i32.load8_u)

  (func (export "load_i32") (param $addr i32) (result i32)
    local.get $addr
    i32.load))
