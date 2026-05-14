;; Phase 8.E.B: SIMD loads + stores.
;;
;; Exercises every load/store sub-opcode in chunk B:
;;
;;   - v128.load                  (sub  0) — full 16B little-endian load.
;;   - v128.store                 (sub 11) — full 16B little-endian store.
;;   - v128.load8x8_s/_u          (subs 1, 2) — 8 i8 src lanes → 8 i16 lanes.
;;   - v128.load16x4_s/_u         (subs 3, 4) — 4 i16 src lanes → 4 i32 lanes.
;;   - v128.load32x2_s/_u         (subs 5, 6) — 2 i32 src lanes → 2 i64 lanes.
;;   - v128.load{8,16,32,64}_splat (subs 7..10) — broadcast N bytes.
;;   - v128.load32_zero / load64_zero (subs 92, 93) — zero-extend into lane 0.
;;
;; A single 1-page memory holds 16-byte aligned fixtures at known offsets.
;; The test harness reads the V128 byte payload back and asserts exact byte
;; equality.
(module
  (memory (export "mem") 1)

  ;; Round-trip a v128 through memory: store at $addr, load from $addr.
  ;; Asserts v128.store + v128.load are byte-identical to v128.const.
  (func (export "store_then_load") (param $addr i32) (param $v v128) (result v128)
    (v128.store (local.get $addr) (local.get $v))
    (v128.load (local.get $addr)))

  ;; Plain v128.load at offset 0 (after the caller has written 16 bytes
  ;; into memory through scalar i32.store ops).
  (func (export "load_at") (param $addr i32) (result v128)
    (v128.load (local.get $addr)))

  ;; v128.load with a static offset immediate. addr + offset must lie
  ;; within memory. Validates the memarg's offset is honoured.
  (func (export "load_at_off") (param $addr i32) (result v128)
    (v128.load offset=16 (local.get $addr)))

  ;; Splat the byte at $addr across all 16 lanes.
  (func (export "load8_splat_at") (param $addr i32) (result v128)
    (v128.load8_splat (local.get $addr)))

  (func (export "load16_splat_at") (param $addr i32) (result v128)
    (v128.load16_splat (local.get $addr)))

  (func (export "load32_splat_at") (param $addr i32) (result v128)
    (v128.load32_splat (local.get $addr)))

  (func (export "load64_splat_at") (param $addr i32) (result v128)
    (v128.load64_splat (local.get $addr)))

  ;; Read 4 bytes into lane 0, zero the remaining 12 bytes.
  (func (export "load32_zero_at") (param $addr i32) (result v128)
    (v128.load32_zero (local.get $addr)))

  ;; Read 8 bytes into lane 0, zero the remaining 8 bytes.
  (func (export "load64_zero_at") (param $addr i32) (result v128)
    (v128.load64_zero (local.get $addr)))

  ;; Sign-extending pair loads — read N small lanes, widen to half-count
  ;; wider lanes with sign extension.
  (func (export "load8x8_s_at") (param $addr i32) (result v128)
    (v128.load8x8_s (local.get $addr)))

  (func (export "load8x8_u_at") (param $addr i32) (result v128)
    (v128.load8x8_u (local.get $addr)))

  (func (export "load16x4_s_at") (param $addr i32) (result v128)
    (v128.load16x4_s (local.get $addr)))

  (func (export "load16x4_u_at") (param $addr i32) (result v128)
    (v128.load16x4_u (local.get $addr)))

  (func (export "load32x2_s_at") (param $addr i32) (result v128)
    (v128.load32x2_s (local.get $addr)))

  (func (export "load32x2_u_at") (param $addr i32) (result v128)
    (v128.load32x2_u (local.get $addr)))

  ;; Trap path: load at an out-of-bounds address. Memory is 1 page = 64KB.
  ;; addr = 65521 + 16 = 65537 > 65536 → memory-out-of-bounds trap.
  (func (export "load_oob") (param $addr i32) (result v128)
    (v128.load (local.get $addr))))
