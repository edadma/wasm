;; Phase 7.B — args + environ passthrough.
;;
;; Exposes the four wasi syscalls directly to the test side. The test
;; plants pointers, calls one of the syscalls, and reads back memory to
;; assert what the shim wrote. Memory poke/peek helpers (write_i32 /
;; load_i32 / load_byte) are shared with `wasi_passthrough.wat`'s shape.
(module
  (import "wasi_snapshot_preview1" "args_sizes_get"
    (func $args_sizes_get (param i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "args_get"
    (func $args_get (param i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "environ_sizes_get"
    (func $environ_sizes_get (param i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "environ_get"
    (func $environ_get (param i32 i32) (result i32)))

  (memory 1)
  (export "memory" (memory 0))

  (func (export "call_args_sizes_get")
        (param $count_ptr i32) (param $buf_size_ptr i32) (result i32)
    local.get $count_ptr
    local.get $buf_size_ptr
    call $args_sizes_get)

  (func (export "call_args_get")
        (param $argv_ptr i32) (param $buf_ptr i32) (result i32)
    local.get $argv_ptr
    local.get $buf_ptr
    call $args_get)

  (func (export "call_environ_sizes_get")
        (param $count_ptr i32) (param $buf_size_ptr i32) (result i32)
    local.get $count_ptr
    local.get $buf_size_ptr
    call $environ_sizes_get)

  (func (export "call_environ_get")
        (param $envp_ptr i32) (param $buf_ptr i32) (result i32)
    local.get $envp_ptr
    local.get $buf_ptr
    call $environ_get)

  (func (export "load_i32") (param $addr i32) (result i32)
    local.get $addr
    i32.load)

  (func (export "load_byte") (param $addr i32) (result i32)
    local.get $addr
    i32.load8_u))
