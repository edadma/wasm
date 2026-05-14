;; Phase 8.E.C: SIMD lane access.
;;
;; Exercises every lane-access sub-opcode in chunk C:
;;
;;   - {i8x16,i16x8,i32x4,i64x2,f32x4,f64x2}.splat   (subs 0x0F..0x14).
;;   - {i8x16,i16x8}.extract_lane_s/_u, {i32x4,i64x2,f32x4,f64x2}.extract_lane
;;     (subs 0x15, 0x16, 0x18, 0x19, 0x1B, 0x1D, 0x1F, 0x21).
;;   - {i8x16,i16x8,i32x4,i64x2,f32x4,f64x2}.replace_lane
;;     (subs 0x17, 0x1A, 0x1C, 0x1E, 0x20, 0x22).
;;   - i8x16.shuffle (sub 0x0D), i8x16.swizzle (sub 0x0E).
;;
;; Lane indices are immediate operands, so most coverage is via one export
;; per (op, lane) pair the test cares about. The Scala suite reads back
;; the V128 byte payload via the runtime and asserts byte-equality, the
;; same shape as SimdLoadStoreTests.
(module

  ;; --- splats ---------------------------------------------------------

  (func (export "splat_i8x16") (param $x i32) (result v128)
    (i8x16.splat (local.get $x)))

  (func (export "splat_i16x8") (param $x i32) (result v128)
    (i16x8.splat (local.get $x)))

  (func (export "splat_i32x4") (param $x i32) (result v128)
    (i32x4.splat (local.get $x)))

  (func (export "splat_i64x2") (param $x i64) (result v128)
    (i64x2.splat (local.get $x)))

  (func (export "splat_f32x4") (param $x f32) (result v128)
    (f32x4.splat (local.get $x)))

  (func (export "splat_f64x2") (param $x f64) (result v128)
    (f64x2.splat (local.get $x)))

  ;; --- extract_lane ----------------------------------------------------
  ;;
  ;; Each export hardcodes a representative lane immediate. Two i8x16/i16x8
  ;; ends exercise signed vs. unsigned extension (the *_s vs *_u variants).

  (func (export "extract_i8x16_s_0")  (param $v v128) (result i32) (i8x16.extract_lane_s 0  (local.get $v)))
  (func (export "extract_i8x16_s_15") (param $v v128) (result i32) (i8x16.extract_lane_s 15 (local.get $v)))
  (func (export "extract_i8x16_u_15") (param $v v128) (result i32) (i8x16.extract_lane_u 15 (local.get $v)))

  (func (export "extract_i16x8_s_0") (param $v v128) (result i32) (i16x8.extract_lane_s 0 (local.get $v)))
  (func (export "extract_i16x8_s_7") (param $v v128) (result i32) (i16x8.extract_lane_s 7 (local.get $v)))
  (func (export "extract_i16x8_u_7") (param $v v128) (result i32) (i16x8.extract_lane_u 7 (local.get $v)))

  (func (export "extract_i32x4_0") (param $v v128) (result i32) (i32x4.extract_lane 0 (local.get $v)))
  (func (export "extract_i32x4_3") (param $v v128) (result i32) (i32x4.extract_lane 3 (local.get $v)))

  (func (export "extract_i64x2_0") (param $v v128) (result i64) (i64x2.extract_lane 0 (local.get $v)))
  (func (export "extract_i64x2_1") (param $v v128) (result i64) (i64x2.extract_lane 1 (local.get $v)))

  (func (export "extract_f32x4_0") (param $v v128) (result f32) (f32x4.extract_lane 0 (local.get $v)))
  (func (export "extract_f32x4_3") (param $v v128) (result f32) (f32x4.extract_lane 3 (local.get $v)))

  (func (export "extract_f64x2_0") (param $v v128) (result f64) (f64x2.extract_lane 0 (local.get $v)))
  (func (export "extract_f64x2_1") (param $v v128) (result f64) (f64x2.extract_lane 1 (local.get $v)))

  ;; --- replace_lane ----------------------------------------------------
  ;;
  ;; Each export takes a v128 + scalar, replaces one specific lane, and
  ;; returns the new v128. Lane choices cover low, mid, and high indices.

  (func (export "replace_i8x16_5") (param $v v128) (param $x i32) (result v128)
    (i8x16.replace_lane 5 (local.get $v) (local.get $x)))

  (func (export "replace_i16x8_3") (param $v v128) (param $x i32) (result v128)
    (i16x8.replace_lane 3 (local.get $v) (local.get $x)))

  (func (export "replace_i32x4_2") (param $v v128) (param $x i32) (result v128)
    (i32x4.replace_lane 2 (local.get $v) (local.get $x)))

  (func (export "replace_i64x2_1") (param $v v128) (param $x i64) (result v128)
    (i64x2.replace_lane 1 (local.get $v) (local.get $x)))

  (func (export "replace_f32x4_2") (param $v v128) (param $x f32) (result v128)
    (f32x4.replace_lane 2 (local.get $v) (local.get $x)))

  (func (export "replace_f64x2_0") (param $v v128) (param $x f64) (result v128)
    (f64x2.replace_lane 0 (local.get $v) (local.get $x)))

  ;; --- shuffle ---------------------------------------------------------
  ;;
  ;; The 16 immediate indices below pick alternately from $a (0..15) and
  ;; $b (16..31), interleaving them byte-by-byte.

  (func (export "shuffle_interleave") (param $a v128) (param $b v128) (result v128)
    (i8x16.shuffle
      0 16 1 17 2 18 3 19 4 20 5 21 6 22 7 23
      (local.get $a) (local.get $b)))

  ;; Reverse 16 bytes of $a (ignores $b — indices 0..15 all from $a, in
  ;; descending order).
  (func (export "shuffle_reverse_a") (param $a v128) (param $b v128) (result v128)
    (i8x16.shuffle
      15 14 13 12 11 10 9 8 7 6 5 4 3 2 1 0
      (local.get $a) (local.get $b)))

  ;; --- swizzle ---------------------------------------------------------
  ;;
  ;; Dynamic-shuffle of $v using $s as a 16-byte index vector. The Scala
  ;; test poking bytes into $s exercises both the in-range path (idx < 16
  ;; picks v[idx]) and the OOB path (idx >= 16 yields 0).

  (func (export "swizzle") (param $v v128) (param $s v128) (result v128)
    (i8x16.swizzle (local.get $v) (local.get $s))))
