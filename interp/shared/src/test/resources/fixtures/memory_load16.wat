;; Phase 4 — i32.load16_s / i32.load16_u / i32.store16.
;;
;; The data segment primes a known 4-byte pattern at offset 0:
;;   bytes 0..1 = 0x00 0x80 → little-endian u16 0x8000 (= 32768 unsigned,
;;                                                       = -32768 signed)
;;   bytes 2..3 = 0xff 0x7f → little-endian u16 0x7fff (= 32767 either way)
;;
;; Exports:
;;   load_signed(addr)         → sign-extended into i32
;;   load_unsigned(addr)       → zero-extended into i32
;;   store16_then_read(addr, v)→ write low 16 bits of v, read back unsigned.
;;                              Used to pin that bits 16..31 of the source
;;                              are dropped on store.
(module
  (memory 1)
  (data (i32.const 0) "\00\80\ff\7f")

  (func (export "load_signed") (param i32) (result i32)
    local.get 0
    i32.load16_s)

  (func (export "load_unsigned") (param i32) (result i32)
    local.get 0
    i32.load16_u)

  (func (export "store16_then_read") (param $addr i32) (param $v i32) (result i32)
    local.get $addr
    local.get $v
    i32.store16
    local.get $addr
    i32.load16_u))
