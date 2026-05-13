;; Phase 7.A — `proc_exit(42)`. The shim throws `Wasi.WasiExit(42)`;
;; `Wasi.run` catches it and returns `Right(42)`. Any code AFTER
;; `proc_exit` is unreachable — the `unreachable` here is a defensive
;; trap to catch a busted shim that lets execution fall through.
(module
  (import "wasi_snapshot_preview1" "proc_exit"
    (func $proc_exit (param i32)))

  (memory 1)
  (export "memory" (memory 0))

  (func (export "_start")
    i32.const 42
    call $proc_exit
    unreachable))
