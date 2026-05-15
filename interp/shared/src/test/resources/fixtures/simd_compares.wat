;; Phase 8.E.G.2 — comparisons.
;; 48 exports — one per chunk-G.2 op. Each takes two v128 arguments and
;; returns a v128 mask: each lane is all-1s on true, 0 on false.
;;
;; Groups (by sub-opcode range): i8x16 (10), i16x8 (10), i32x4 (10),
;; i64x2 (6 signed-only — wasm spec defines no _u forms for i64), f32x4 (6),
;; f64x2 (6) = 48 total.

(module
  ;; --- i8x16 (0x23..0x2C) ---------------------------------------------

  (func (export "i8x16_eq")   (param v128 v128) (result v128) local.get 0 local.get 1 i8x16.eq)
  (func (export "i8x16_ne")   (param v128 v128) (result v128) local.get 0 local.get 1 i8x16.ne)
  (func (export "i8x16_lt_s") (param v128 v128) (result v128) local.get 0 local.get 1 i8x16.lt_s)
  (func (export "i8x16_lt_u") (param v128 v128) (result v128) local.get 0 local.get 1 i8x16.lt_u)
  (func (export "i8x16_gt_s") (param v128 v128) (result v128) local.get 0 local.get 1 i8x16.gt_s)
  (func (export "i8x16_gt_u") (param v128 v128) (result v128) local.get 0 local.get 1 i8x16.gt_u)
  (func (export "i8x16_le_s") (param v128 v128) (result v128) local.get 0 local.get 1 i8x16.le_s)
  (func (export "i8x16_le_u") (param v128 v128) (result v128) local.get 0 local.get 1 i8x16.le_u)
  (func (export "i8x16_ge_s") (param v128 v128) (result v128) local.get 0 local.get 1 i8x16.ge_s)
  (func (export "i8x16_ge_u") (param v128 v128) (result v128) local.get 0 local.get 1 i8x16.ge_u)

  ;; --- i16x8 (0x2D..0x36) ---------------------------------------------

  (func (export "i16x8_eq")   (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.eq)
  (func (export "i16x8_ne")   (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.ne)
  (func (export "i16x8_lt_s") (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.lt_s)
  (func (export "i16x8_lt_u") (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.lt_u)
  (func (export "i16x8_gt_s") (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.gt_s)
  (func (export "i16x8_gt_u") (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.gt_u)
  (func (export "i16x8_le_s") (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.le_s)
  (func (export "i16x8_le_u") (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.le_u)
  (func (export "i16x8_ge_s") (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.ge_s)
  (func (export "i16x8_ge_u") (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.ge_u)

  ;; --- i32x4 (0x37..0x40) ---------------------------------------------

  (func (export "i32x4_eq")   (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.eq)
  (func (export "i32x4_ne")   (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.ne)
  (func (export "i32x4_lt_s") (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.lt_s)
  (func (export "i32x4_lt_u") (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.lt_u)
  (func (export "i32x4_gt_s") (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.gt_s)
  (func (export "i32x4_gt_u") (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.gt_u)
  (func (export "i32x4_le_s") (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.le_s)
  (func (export "i32x4_le_u") (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.le_u)
  (func (export "i32x4_ge_s") (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.ge_s)
  (func (export "i32x4_ge_u") (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.ge_u)

  ;; --- i64x2 (0xD6..0xDB) — signed-only per spec ----------------------

  (func (export "i64x2_eq")   (param v128 v128) (result v128) local.get 0 local.get 1 i64x2.eq)
  (func (export "i64x2_ne")   (param v128 v128) (result v128) local.get 0 local.get 1 i64x2.ne)
  (func (export "i64x2_lt_s") (param v128 v128) (result v128) local.get 0 local.get 1 i64x2.lt_s)
  (func (export "i64x2_gt_s") (param v128 v128) (result v128) local.get 0 local.get 1 i64x2.gt_s)
  (func (export "i64x2_le_s") (param v128 v128) (result v128) local.get 0 local.get 1 i64x2.le_s)
  (func (export "i64x2_ge_s") (param v128 v128) (result v128) local.get 0 local.get 1 i64x2.ge_s)

  ;; --- f32x4 (0x41..0x46) — IEEE NaN: only ne returns true on NaN -----

  (func (export "f32x4_eq") (param v128 v128) (result v128) local.get 0 local.get 1 f32x4.eq)
  (func (export "f32x4_ne") (param v128 v128) (result v128) local.get 0 local.get 1 f32x4.ne)
  (func (export "f32x4_lt") (param v128 v128) (result v128) local.get 0 local.get 1 f32x4.lt)
  (func (export "f32x4_gt") (param v128 v128) (result v128) local.get 0 local.get 1 f32x4.gt)
  (func (export "f32x4_le") (param v128 v128) (result v128) local.get 0 local.get 1 f32x4.le)
  (func (export "f32x4_ge") (param v128 v128) (result v128) local.get 0 local.get 1 f32x4.ge)

  ;; --- f64x2 (0x47..0x4C) --------------------------------------------

  (func (export "f64x2_eq") (param v128 v128) (result v128) local.get 0 local.get 1 f64x2.eq)
  (func (export "f64x2_ne") (param v128 v128) (result v128) local.get 0 local.get 1 f64x2.ne)
  (func (export "f64x2_lt") (param v128 v128) (result v128) local.get 0 local.get 1 f64x2.lt)
  (func (export "f64x2_gt") (param v128 v128) (result v128) local.get 0 local.get 1 f64x2.gt)
  (func (export "f64x2_le") (param v128 v128) (result v128) local.get 0 local.get 1 f64x2.le)
  (func (export "f64x2_ge") (param v128 v128) (result v128) local.get 0 local.get 1 f64x2.ge))
