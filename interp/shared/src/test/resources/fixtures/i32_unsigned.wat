;; Phase 1.5 fills in the i32 ops the original MVP subset skipped:
;; the four unsigned compares, three bit-counting unops, div_u/rem_u,
;; and shr_u/rotl/rotr. Their i64 cousins have been live since Phase 1.1,
;; so this is a same-shape pass at i32 width.
(module
  ;; Unsigned compares — return i32 (boolean).
  (func (export "i32_lt_u") (param i32 i32) (result i32) local.get 0 local.get 1 i32.lt_u)
  (func (export "i32_gt_u") (param i32 i32) (result i32) local.get 0 local.get 1 i32.gt_u)
  (func (export "i32_le_u") (param i32 i32) (result i32) local.get 0 local.get 1 i32.le_u)
  (func (export "i32_ge_u") (param i32 i32) (result i32) local.get 0 local.get 1 i32.ge_u)

  ;; Bit-counting unops (result i32 — unlike the i64 forms whose result
  ;; type matches the operand width, here both operand and result are i32).
  (func (export "i32_clz")    (param i32) (result i32) local.get 0 i32.clz)
  (func (export "i32_ctz")    (param i32) (result i32) local.get 0 i32.ctz)
  (func (export "i32_popcnt") (param i32) (result i32) local.get 0 i32.popcnt)

  ;; Unsigned divide / remainder. Both trap on divisor zero. There is no
  ;; overflow case (signed MIN/-1 doesn't apply at unsigned width).
  (func (export "i32_div_u") (param i32 i32) (result i32) local.get 0 local.get 1 i32.div_u)
  (func (export "i32_rem_u") (param i32 i32) (result i32) local.get 0 local.get 1 i32.rem_u)

  ;; Shift count is masked mod 32. shr_u fills the vacated bits with 0
  ;; regardless of the operand's sign bit, distinguishing it from shr_s.
  (func (export "i32_shr_u") (param i32 i32) (result i32) local.get 0 local.get 1 i32.shr_u)
  (func (export "i32_rotl")  (param i32 i32) (result i32) local.get 0 local.get 1 i32.rotl)
  (func (export "i32_rotr")  (param i32 i32) (result i32) local.get 0 local.get 1 i32.rotr))
