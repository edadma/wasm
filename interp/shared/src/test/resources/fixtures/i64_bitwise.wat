;; i64 bitwise + shifts + rotates + bit-counting ops.
;; Covers 0x79–0x8A (i64.clz/ctz/popcnt + and/or/xor/shl/shr_s/shr_u/rotl/rotr).
(module
  (func (export "i64_and") (param i64 i64) (result i64) local.get 0 local.get 1 i64.and)
  (func (export "i64_or")  (param i64 i64) (result i64) local.get 0 local.get 1 i64.or)
  (func (export "i64_xor") (param i64 i64) (result i64) local.get 0 local.get 1 i64.xor)

  (func (export "i64_shl")   (param i64 i64) (result i64) local.get 0 local.get 1 i64.shl)
  (func (export "i64_shr_s") (param i64 i64) (result i64) local.get 0 local.get 1 i64.shr_s)
  (func (export "i64_shr_u") (param i64 i64) (result i64) local.get 0 local.get 1 i64.shr_u)
  (func (export "i64_rotl")  (param i64 i64) (result i64) local.get 0 local.get 1 i64.rotl)
  (func (export "i64_rotr")  (param i64 i64) (result i64) local.get 0 local.get 1 i64.rotr)

  (func (export "i64_clz")    (param i64) (result i64) local.get 0 i64.clz)
  (func (export "i64_ctz")    (param i64) (result i64) local.get 0 i64.ctz)
  (func (export "i64_popcnt") (param i64) (result i64) local.get 0 i64.popcnt))
