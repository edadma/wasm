;; Phase 7.E.2 — path_open + general-fd fd_close passthrough.
;;
;; Tests drive both syscalls via the call_* wrappers, write path bytes
;; into linear memory with store_byte, and read back the opened fd
;; (planted at opened_fd_out by the shim) with load_i32. Same shape as
;; the other wasi_*.wat fixtures: the .wat stays generic and the test
;; code owns the call patterns.
(module
  (import "wasi_snapshot_preview1" "path_open"
    (func $path_open
      (param i32 i32 i32 i32 i32 i64 i64 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_close"
    (func $fd_close (param i32) (result i32)))

  (memory 1)
  (export "memory" (memory 0))

  ;; The 9-arg passthrough. Order matches the wasi-preview1 witx:
  ;;   dirfd, dirflags, path_ptr, path_len, oflags,
  ;;   fs_rights_base, fs_rights_inheriting, fdflags, opened_fd_out
  (func (export "call_path_open")
        (param $dirfd        i32) (param $dirflags     i32)
        (param $path_ptr     i32) (param $path_len     i32)
        (param $oflags       i32) (param $rights_base  i64)
        (param $rights_inh   i64) (param $fdflags      i32)
        (param $opened_fd_out i32) (result i32)
    local.get $dirfd
    local.get $dirflags
    local.get $path_ptr
    local.get $path_len
    local.get $oflags
    local.get $rights_base
    local.get $rights_inh
    local.get $fdflags
    local.get $opened_fd_out
    call $path_open)

  (func (export "call_fd_close") (param $fd i32) (result i32)
    local.get $fd
    call $fd_close)

  ;; store_byte lets tests poke path bytes into memory before path_open.
  (func (export "store_byte") (param $addr i32) (param $b i32)
    local.get $addr
    local.get $b
    i32.store8)

  (func (export "load_byte") (param $addr i32) (result i32)
    local.get $addr
    i32.load8_u)

  (func (export "load_i32") (param $addr i32) (result i32)
    local.get $addr
    i32.load))
