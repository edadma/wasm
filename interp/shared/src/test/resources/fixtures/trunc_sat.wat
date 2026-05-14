;; Phase 8.A: non-trapping (saturating) float→int conversions.
;; One export per 0xFC sub-opcode 0..7 — the eight trunc_sat variants
;; covering {i32,i64} × {f32,f64} × {signed,unsigned}.
;;
;; Saturating semantics (all eight):
;;   NaN                              → 0
;;   v < INT_MIN (or v <= -1 for u)   → INT_MIN  (signed)  / 0  (unsigned)
;;   v > INT_MAX (or >= 2^N for u)    → INT_MAX
;;   otherwise                        → truncate toward zero
(module
  (func (export "i32_trunc_sat_f32_s") (param f32) (result i32)
    (i32.trunc_sat_f32_s (local.get 0)))

  (func (export "i32_trunc_sat_f32_u") (param f32) (result i32)
    (i32.trunc_sat_f32_u (local.get 0)))

  (func (export "i32_trunc_sat_f64_s") (param f64) (result i32)
    (i32.trunc_sat_f64_s (local.get 0)))

  (func (export "i32_trunc_sat_f64_u") (param f64) (result i32)
    (i32.trunc_sat_f64_u (local.get 0)))

  (func (export "i64_trunc_sat_f32_s") (param f32) (result i64)
    (i64.trunc_sat_f32_s (local.get 0)))

  (func (export "i64_trunc_sat_f32_u") (param f32) (result i64)
    (i64.trunc_sat_f32_u (local.get 0)))

  (func (export "i64_trunc_sat_f64_s") (param f64) (result i64)
    (i64.trunc_sat_f64_s (local.get 0)))

  (func (export "i64_trunc_sat_f64_u") (param f64) (result i64)
    (i64.trunc_sat_f64_u (local.get 0))))
