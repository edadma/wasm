;; All i64 comparison opcodes (0x50–0x5A). Each returns i32 1 (true) or 0.
(module
  (func (export "i64_eqz") (param i64) (result i32) local.get 0 i64.eqz)

  (func (export "i64_eq")   (param i64 i64) (result i32) local.get 0 local.get 1 i64.eq)
  (func (export "i64_ne")   (param i64 i64) (result i32) local.get 0 local.get 1 i64.ne)
  (func (export "i64_lt_s") (param i64 i64) (result i32) local.get 0 local.get 1 i64.lt_s)
  (func (export "i64_lt_u") (param i64 i64) (result i32) local.get 0 local.get 1 i64.lt_u)
  (func (export "i64_gt_s") (param i64 i64) (result i32) local.get 0 local.get 1 i64.gt_s)
  (func (export "i64_gt_u") (param i64 i64) (result i32) local.get 0 local.get 1 i64.gt_u)
  (func (export "i64_le_s") (param i64 i64) (result i32) local.get 0 local.get 1 i64.le_s)
  (func (export "i64_le_u") (param i64 i64) (result i32) local.get 0 local.get 1 i64.le_u)
  (func (export "i64_ge_s") (param i64 i64) (result i32) local.get 0 local.get 1 i64.ge_s)
  (func (export "i64_ge_u") (param i64 i64) (result i32) local.get 0 local.get 1 i64.ge_u))
