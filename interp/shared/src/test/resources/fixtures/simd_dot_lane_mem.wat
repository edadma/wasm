;; Phase 8.E.I: SIMD dot product + load_lane / store_lane.
;;
;; Exercises every chunk-I sub-opcode (9 ops):
;;
;;   - i32x4.dot_i16x8_s        (sub 0xBA) — pairwise multiply + add into i32.
;;   - v128.load{8,16,32,64}_lane  (subs 0x54..0x57) — read N bytes into one
;;                                  lane of an existing v128, preserving the
;;                                  other lanes.
;;   - v128.store{8,16,32,64}_lane (subs 0x58..0x5B) — write one lane's N
;;                                  bytes to memory.
;;
;; The lane ops take both a memarg AND a 1-byte lane immediate — the only
;; SIMD op family with that combination. A single 1-page memory holds the
;; round-trip buffer.
(module
  (memory (export "mem") 1)

  ;; Pairwise multiply-then-add: each i32 result lane k =
  ;;   sign_extend(a[2k]) * sign_extend(b[2k]) +
  ;;   sign_extend(a[2k+1]) * sign_extend(b[2k+1])
  ;; The i16 product fits exact in i32; the pair-sum wraps two's-complement
  ;; on overflow.
  (func (export "dot_i16x8_s") (param $a v128) (param $b v128) (result v128)
    (i32x4.dot_i16x8_s (local.get $a) (local.get $b)))

  ;; --- load_lane: read N bytes from $addr into lane $laneidx of $src,
  ;; preserving the other (16/8/4/2 - 1) lanes. lane immediate is fixed
  ;; per export so the validator's lane-bound check can fire.

  (func (export "load8_lane_0") (param $addr i32) (param $src v128) (result v128)
    (v128.load8_lane 0 (local.get $addr) (local.get $src)))
  (func (export "load8_lane_15") (param $addr i32) (param $src v128) (result v128)
    (v128.load8_lane 15 (local.get $addr) (local.get $src)))

  (func (export "load16_lane_0") (param $addr i32) (param $src v128) (result v128)
    (v128.load16_lane 0 (local.get $addr) (local.get $src)))
  (func (export "load16_lane_7") (param $addr i32) (param $src v128) (result v128)
    (v128.load16_lane 7 (local.get $addr) (local.get $src)))

  (func (export "load32_lane_0") (param $addr i32) (param $src v128) (result v128)
    (v128.load32_lane 0 (local.get $addr) (local.get $src)))
  (func (export "load32_lane_3") (param $addr i32) (param $src v128) (result v128)
    (v128.load32_lane 3 (local.get $addr) (local.get $src)))

  (func (export "load64_lane_0") (param $addr i32) (param $src v128) (result v128)
    (v128.load64_lane 0 (local.get $addr) (local.get $src)))
  (func (export "load64_lane_1") (param $addr i32) (param $src v128) (result v128)
    (v128.load64_lane 1 (local.get $addr) (local.get $src)))

  ;; --- store_lane: write the $laneidx-th N bytes of $src to memory at $addr.
  ;; No result. lane is a static immediate.

  (func (export "store8_lane_0")  (param $addr i32) (param $src v128)
    (v128.store8_lane  0 (local.get $addr) (local.get $src)))
  (func (export "store8_lane_15") (param $addr i32) (param $src v128)
    (v128.store8_lane 15 (local.get $addr) (local.get $src)))

  (func (export "store16_lane_0") (param $addr i32) (param $src v128)
    (v128.store16_lane 0 (local.get $addr) (local.get $src)))
  (func (export "store16_lane_7") (param $addr i32) (param $src v128)
    (v128.store16_lane 7 (local.get $addr) (local.get $src)))

  (func (export "store32_lane_0") (param $addr i32) (param $src v128)
    (v128.store32_lane 0 (local.get $addr) (local.get $src)))
  (func (export "store32_lane_3") (param $addr i32) (param $src v128)
    (v128.store32_lane 3 (local.get $addr) (local.get $src)))

  (func (export "store64_lane_0") (param $addr i32) (param $src v128)
    (v128.store64_lane 0 (local.get $addr) (local.get $src)))
  (func (export "store64_lane_1") (param $addr i32) (param $src v128)
    (v128.store64_lane 1 (local.get $addr) (local.get $src)))

  ;; --- Helper exports for round-trip tests: read a full v128 back from
  ;; memory after a store_lane has written one lane's worth.
  (func (export "load_at") (param $addr i32) (result v128)
    (v128.load (local.get $addr)))

  ;; Trap path: load_lane at an out-of-bounds address. 1 page = 65536 bytes;
  ;; load8_lane reads 1 byte at addr — caller passes 65536 to trap.
  (func (export "load8_lane_oob") (param $addr i32) (param $src v128) (result v128)
    (v128.load8_lane 0 (local.get $addr) (local.get $src))))
