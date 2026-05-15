;; Phase 7.E.3 + 7.E.4 + 7.F — fd_read / fd_write / fd_seek /
;; fd_filestat_get / fd_fdstat_get passthrough.
;;
;; Tests open a file via call_path_open, plant iovec entries with
;; store_i32, drive each syscall via the matching call_* wrapper, and
;; read back the destination bytes / new offset / filestat struct with
;; load_byte / load_i32 / load_i64. Same generic-fixture shape as
;; wasi_path_open.wat — call patterns live in the tests.
;;
;; 7.F added call_fd_write so the read/write/seek surface lives in one
;; instance — tests that exercise the InMemoryFs through path_open
;; +fd_write don't need a separate fixture.
;;
;; Memory is 1 page (65536 bytes), exported. Layout convention used by
;; the tests:
;;   0x0000 .. 0x00FF  path bytes + small scratch
;;   0x0100 .. 0x01FF  iovec table + output i32/i64 slots
;;   0x0200 .. 0x02FF  filestat scratch (64 bytes)
;;   0x0300 .. 0xFFFF  read destinations
;; The fixture itself doesn't impose this — every address is a test arg.
(module
  (import "wasi_snapshot_preview1" "path_open"
    (func $path_open
      (param i32 i32 i32 i32 i32 i64 i64 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_close"
    (func $fd_close (param i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_read"
    (func $fd_read (param i32 i32 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_write"
    (func $fd_write (param i32 i32 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_seek"
    (func $fd_seek (param i32 i64 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_filestat_get"
    (func $fd_filestat_get (param i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_fdstat_get"
    (func $fd_fdstat_get (param i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_fdstat_set_flags"
    (func $fd_fdstat_set_flags (param i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "path_filestat_get"
    (func $path_filestat_get (param i32 i32 i32 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_sync"
    (func $fd_sync (param i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_datasync"
    (func $fd_datasync (param i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_advise"
    (func $fd_advise (param i32 i64 i64 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_allocate"
    (func $fd_allocate (param i32 i64 i64) (result i32)))
  (import "wasi_snapshot_preview1" "path_unlink_file"
    (func $path_unlink_file (param i32 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "path_create_directory"
    (func $path_create_directory (param i32 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_readdir"
    (func $fd_readdir (param i32 i32 i32 i64 i32) (result i32)))

  (memory 1)
  (export "memory" (memory 0))

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

  (func (export "call_fd_read")
        (param $fd i32) (param $iovs i32) (param $iovs_len i32)
        (param $nread_out i32) (result i32)
    local.get $fd
    local.get $iovs
    local.get $iovs_len
    local.get $nread_out
    call $fd_read)

  (func (export "call_fd_write")
        (param $fd i32) (param $iovs i32) (param $iovs_len i32)
        (param $nwritten_out i32) (result i32)
    local.get $fd
    local.get $iovs
    local.get $iovs_len
    local.get $nwritten_out
    call $fd_write)

  (func (export "call_fd_seek")
        (param $fd i32) (param $offset i64) (param $whence i32)
        (param $newoffset_out i32) (result i32)
    local.get $fd
    local.get $offset
    local.get $whence
    local.get $newoffset_out
    call $fd_seek)

  (func (export "call_fd_filestat_get")
        (param $fd i32) (param $buf i32) (result i32)
    local.get $fd
    local.get $buf
    call $fd_filestat_get)

  (func (export "call_fd_fdstat_get")
        (param $fd i32) (param $buf i32) (result i32)
    local.get $fd
    local.get $buf
    call $fd_fdstat_get)

  (func (export "call_fd_fdstat_set_flags")
        (param $fd i32) (param $flags i32) (result i32)
    local.get $fd
    local.get $flags
    call $fd_fdstat_set_flags)

  (func (export "call_path_filestat_get")
        (param $fd i32) (param $lookupflags i32)
        (param $path_ptr i32) (param $path_len i32)
        (param $buf i32) (result i32)
    local.get $fd
    local.get $lookupflags
    local.get $path_ptr
    local.get $path_len
    local.get $buf
    call $path_filestat_get)

  (func (export "call_fd_sync") (param $fd i32) (result i32)
    local.get $fd
    call $fd_sync)

  (func (export "call_fd_datasync") (param $fd i32) (result i32)
    local.get $fd
    call $fd_datasync)

  (func (export "call_fd_advise")
        (param $fd i32) (param $offset i64) (param $len i64) (param $advice i32)
        (result i32)
    local.get $fd
    local.get $offset
    local.get $len
    local.get $advice
    call $fd_advise)

  (func (export "call_fd_allocate")
        (param $fd i32) (param $offset i64) (param $len i64) (result i32)
    local.get $fd
    local.get $offset
    local.get $len
    call $fd_allocate)

  (func (export "call_path_unlink_file")
        (param $fd i32) (param $path_ptr i32) (param $path_len i32)
        (result i32)
    local.get $fd
    local.get $path_ptr
    local.get $path_len
    call $path_unlink_file)

  (func (export "call_path_create_directory")
        (param $fd i32) (param $path_ptr i32) (param $path_len i32)
        (result i32)
    local.get $fd
    local.get $path_ptr
    local.get $path_len
    call $path_create_directory)

  (func (export "call_fd_readdir")
        (param $fd i32) (param $buf i32) (param $buf_len i32)
        (param $cookie i64) (param $bufused_out i32) (result i32)
    local.get $fd
    local.get $buf
    local.get $buf_len
    local.get $cookie
    local.get $bufused_out
    call $fd_readdir)

  ;; store_byte lets tests poke path bytes / filler into memory.
  (func (export "store_byte") (param $addr i32) (param $b i32)
    local.get $addr
    local.get $b
    i32.store8)

  ;; store_i32 plants iovec entries (buf, len) and seeds output slots.
  (func (export "store_i32") (param $addr i32) (param $v i32)
    local.get $addr
    local.get $v
    i32.store)

  (func (export "load_byte") (param $addr i32) (result i32)
    local.get $addr
    i32.load8_u)

  (func (export "load_i32") (param $addr i32) (result i32)
    local.get $addr
    i32.load)

  (func (export "load_i64") (param $addr i32) (result i64)
    local.get $addr
    i64.load))
