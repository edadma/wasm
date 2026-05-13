;; Float → int truncations. Eight ops, all trap on NaN / ±Inf / out-of-range.
;; The MVP "trunc" forms do not saturate — they trap. (Saturating variants
;; live in the bulk/non-trapping float-to-int proposal; not in Phase 1.4.)
;;
;; Each function takes the source float as a param so the test harness can
;; drive in-range values, boundary values, and traps from Scala — there's no
;; need to encode the constants in the .wat itself.
(module
  ;; i32 family
  (func (export "i32_trunc_f32_s") (param f32) (result i32) local.get 0 i32.trunc_f32_s)
  (func (export "i32_trunc_f32_u") (param f32) (result i32) local.get 0 i32.trunc_f32_u)
  (func (export "i32_trunc_f64_s") (param f64) (result i32) local.get 0 i32.trunc_f64_s)
  (func (export "i32_trunc_f64_u") (param f64) (result i32) local.get 0 i32.trunc_f64_u)

  ;; i64 family
  (func (export "i64_trunc_f32_s") (param f32) (result i64) local.get 0 i64.trunc_f32_s)
  (func (export "i64_trunc_f32_u") (param f32) (result i64) local.get 0 i64.trunc_f32_u)
  (func (export "i64_trunc_f64_s") (param f64) (result i64) local.get 0 i64.trunc_f64_s)
  (func (export "i64_trunc_f64_u") (param f64) (result i64) local.get 0 i64.trunc_f64_u))
