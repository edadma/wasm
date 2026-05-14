;; Phase 8.D: multi-memory — every memory opcode threads a memidx through.
;;
;; Layout:
;;   - Two memories, each 1 page (64 KiB):
;;       memidx 0 exported as "mem0"
;;       memidx 1 exported as "mem1"
;;   - Two active data segments seeding distinct payloads at offset 0:
;;       segment 0 → mem0: "AAAAAAAA" (8 bytes)
;;       segment 1 → mem1: "BBBBBBBB" (8 bytes)
;;   - One passive data segment "CDEFGHIJ" (for memory.init tests below).
;;
;; The exported functions cover every memory op with the memidx threaded
;; in. wat2wasm doesn't expose multi-memory by default — the build script
;; passes --enable-multi-memory.
(module
  (memory $mem0 (export "mem0") 1)
  (memory $mem1 (export "mem1") 1)

  (data (memory $mem0) (i32.const 0) "AAAAAAAA")
  (data (memory $mem1) (i32.const 0) "BBBBBBBB")
  (data "CDEFGHIJ")                              ;; passive, dataidx 2

  ;; --- per-memory loads / stores ------------------------------------
  (func (export "load8_u_m0") (param $addr i32) (result i32)
    (i32.load8_u $mem0 (local.get $addr)))

  (func (export "load8_u_m1") (param $addr i32) (result i32)
    (i32.load8_u $mem1 (local.get $addr)))

  (func (export "store8_m0") (param $addr i32) (param $v i32)
    (i32.store8 $mem0 (local.get $addr) (local.get $v)))

  (func (export "store8_m1") (param $addr i32) (param $v i32)
    (i32.store8 $mem1 (local.get $addr) (local.get $v)))

  ;; --- memory.size / memory.grow ------------------------------------
  (func (export "size_m0") (result i32) (memory.size $mem0))
  (func (export "size_m1") (result i32) (memory.size $mem1))

  (func (export "grow_m0") (param $delta i32) (result i32) (memory.grow $mem0 (local.get $delta)))
  (func (export "grow_m1") (param $delta i32) (result i32) (memory.grow $mem1 (local.get $delta)))

  ;; --- memory.fill --------------------------------------------------
  (func (export "fill_m0") (param $dst i32) (param $v i32) (param $n i32)
    (memory.fill $mem0 (local.get $dst) (local.get $v) (local.get $n)))

  (func (export "fill_m1") (param $dst i32) (param $v i32) (param $n i32)
    (memory.fill $mem1 (local.get $dst) (local.get $v) (local.get $n)))

  ;; --- memory.copy: same-memory + across-memory ---------------------
  (func (export "copy_within_m0") (param $dst i32) (param $src i32) (param $n i32)
    (memory.copy $mem0 $mem0 (local.get $dst) (local.get $src) (local.get $n)))

  (func (export "copy_m0_to_m1") (param $dst i32) (param $src i32) (param $n i32)
    (memory.copy $mem1 $mem0 (local.get $dst) (local.get $src) (local.get $n)))

  (func (export "copy_m1_to_m0") (param $dst i32) (param $src i32) (param $n i32)
    (memory.copy $mem0 $mem1 (local.get $dst) (local.get $src) (local.get $n)))

  ;; --- memory.init from passive segment 2 into either memory --------
  ;; Text form: (memory.init <memidx> <dataidx> dst src n)
  (func (export "init_m0") (param $dst i32) (param $src i32) (param $n i32)
    (memory.init $mem0 2 (local.get $dst) (local.get $src) (local.get $n)))

  (func (export "init_m1") (param $dst i32) (param $src i32) (param $n i32)
    (memory.init $mem1 2 (local.get $dst) (local.get $src) (local.get $n))))
