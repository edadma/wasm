;; Phase 8.E.H — narrow / widen (extend) / extadd_pairwise / extmul +
;; float-int trunc_sat / convert + f32↔f64 demote / promote.
;;
;; 42 exports — one per chunk-H op.
;;
;; Groups:
;;   - narrow (4): saturating Short→Byte / Int→Short, signed + unsigned clamp
;;   - extend (12): widen low/high half × 3 shape pairs (i8→i16, i16→i32,
;;                  i32→i64), each with _s/_u sign-form
;;   - extadd_pairwise (4): pairwise adjacent-lane sum-with-extension across
;;                          2 shape pairs (i8x16→i16x8, i16x8→i32x4)
;;   - extmul (12): widening multiply (full-width product) of low/high half,
;;                  3 shape pairs (i8→i16, i16→i32, i32→i64), with _s/_u
;;   - demote/promote (2): f64x2 ↔ f32x4 (only one direction at a time)
;;   - trunc_sat (4): float → int with NaN→0 and overflow saturation
;;   - convert (4): int → float

(module
  ;; --- narrow (0x65/0x66/0x85/0x86) ---------------------------------

  (func (export "i8x16_narrow_i16x8_s") (param v128 v128) (result v128)
    local.get 0 local.get 1 i8x16.narrow_i16x8_s)
  (func (export "i8x16_narrow_i16x8_u") (param v128 v128) (result v128)
    local.get 0 local.get 1 i8x16.narrow_i16x8_u)
  (func (export "i16x8_narrow_i32x4_s") (param v128 v128) (result v128)
    local.get 0 local.get 1 i16x8.narrow_i32x4_s)
  (func (export "i16x8_narrow_i32x4_u") (param v128 v128) (result v128)
    local.get 0 local.get 1 i16x8.narrow_i32x4_u)

  ;; --- extend (widen) — 12 ops, subs 0x87..0x8A/0xA7..0xAA/0xC7..0xCA --

  (func (export "i16x8_extend_low_i8x16_s")  (param v128) (result v128) local.get 0 i16x8.extend_low_i8x16_s)
  (func (export "i16x8_extend_high_i8x16_s") (param v128) (result v128) local.get 0 i16x8.extend_high_i8x16_s)
  (func (export "i16x8_extend_low_i8x16_u")  (param v128) (result v128) local.get 0 i16x8.extend_low_i8x16_u)
  (func (export "i16x8_extend_high_i8x16_u") (param v128) (result v128) local.get 0 i16x8.extend_high_i8x16_u)

  (func (export "i32x4_extend_low_i16x8_s")  (param v128) (result v128) local.get 0 i32x4.extend_low_i16x8_s)
  (func (export "i32x4_extend_high_i16x8_s") (param v128) (result v128) local.get 0 i32x4.extend_high_i16x8_s)
  (func (export "i32x4_extend_low_i16x8_u")  (param v128) (result v128) local.get 0 i32x4.extend_low_i16x8_u)
  (func (export "i32x4_extend_high_i16x8_u") (param v128) (result v128) local.get 0 i32x4.extend_high_i16x8_u)

  (func (export "i64x2_extend_low_i32x4_s")  (param v128) (result v128) local.get 0 i64x2.extend_low_i32x4_s)
  (func (export "i64x2_extend_high_i32x4_s") (param v128) (result v128) local.get 0 i64x2.extend_high_i32x4_s)
  (func (export "i64x2_extend_low_i32x4_u")  (param v128) (result v128) local.get 0 i64x2.extend_low_i32x4_u)
  (func (export "i64x2_extend_high_i32x4_u") (param v128) (result v128) local.get 0 i64x2.extend_high_i32x4_u)

  ;; --- extadd_pairwise — 4 ops, subs 0x7C..0x7F ---------------------

  (func (export "i16x8_extadd_pairwise_i8x16_s") (param v128) (result v128)
    local.get 0 i16x8.extadd_pairwise_i8x16_s)
  (func (export "i16x8_extadd_pairwise_i8x16_u") (param v128) (result v128)
    local.get 0 i16x8.extadd_pairwise_i8x16_u)
  (func (export "i32x4_extadd_pairwise_i16x8_s") (param v128) (result v128)
    local.get 0 i32x4.extadd_pairwise_i16x8_s)
  (func (export "i32x4_extadd_pairwise_i16x8_u") (param v128) (result v128)
    local.get 0 i32x4.extadd_pairwise_i16x8_u)

  ;; --- extmul — 12 ops, subs 0x9C..0x9F/0xBC..0xBF/0xDC..0xDF -------

  (func (export "i16x8_extmul_low_i8x16_s")  (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.extmul_low_i8x16_s)
  (func (export "i16x8_extmul_high_i8x16_s") (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.extmul_high_i8x16_s)
  (func (export "i16x8_extmul_low_i8x16_u")  (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.extmul_low_i8x16_u)
  (func (export "i16x8_extmul_high_i8x16_u") (param v128 v128) (result v128) local.get 0 local.get 1 i16x8.extmul_high_i8x16_u)

  (func (export "i32x4_extmul_low_i16x8_s")  (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.extmul_low_i16x8_s)
  (func (export "i32x4_extmul_high_i16x8_s") (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.extmul_high_i16x8_s)
  (func (export "i32x4_extmul_low_i16x8_u")  (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.extmul_low_i16x8_u)
  (func (export "i32x4_extmul_high_i16x8_u") (param v128 v128) (result v128) local.get 0 local.get 1 i32x4.extmul_high_i16x8_u)

  (func (export "i64x2_extmul_low_i32x4_s")  (param v128 v128) (result v128) local.get 0 local.get 1 i64x2.extmul_low_i32x4_s)
  (func (export "i64x2_extmul_high_i32x4_s") (param v128 v128) (result v128) local.get 0 local.get 1 i64x2.extmul_high_i32x4_s)
  (func (export "i64x2_extmul_low_i32x4_u")  (param v128 v128) (result v128) local.get 0 local.get 1 i64x2.extmul_low_i32x4_u)
  (func (export "i64x2_extmul_high_i32x4_u") (param v128 v128) (result v128) local.get 0 local.get 1 i64x2.extmul_high_i32x4_u)

  ;; --- demote / promote (0x5E/0x5F) ----------------------------------

  (func (export "f32x4_demote_f64x2_zero") (param v128) (result v128)
    local.get 0 f32x4.demote_f64x2_zero)
  (func (export "f64x2_promote_low_f32x4") (param v128) (result v128)
    local.get 0 f64x2.promote_low_f32x4)

  ;; --- trunc_sat / convert (0xF8..0xFF) -----------------------------

  (func (export "i32x4_trunc_sat_f32x4_s")      (param v128) (result v128) local.get 0 i32x4.trunc_sat_f32x4_s)
  (func (export "i32x4_trunc_sat_f32x4_u")      (param v128) (result v128) local.get 0 i32x4.trunc_sat_f32x4_u)
  (func (export "f32x4_convert_i32x4_s")        (param v128) (result v128) local.get 0 f32x4.convert_i32x4_s)
  (func (export "f32x4_convert_i32x4_u")        (param v128) (result v128) local.get 0 f32x4.convert_i32x4_u)
  (func (export "i32x4_trunc_sat_f64x2_s_zero") (param v128) (result v128) local.get 0 i32x4.trunc_sat_f64x2_s_zero)
  (func (export "i32x4_trunc_sat_f64x2_u_zero") (param v128) (result v128) local.get 0 i32x4.trunc_sat_f64x2_u_zero)
  (func (export "f64x2_convert_low_i32x4_s")    (param v128) (result v128) local.get 0 f64x2.convert_low_i32x4_s)
  (func (export "f64x2_convert_low_i32x4_u")    (param v128) (result v128) local.get 0 f64x2.convert_low_i32x4_u))
