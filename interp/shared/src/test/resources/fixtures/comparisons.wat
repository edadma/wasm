;; One export per remaining comparison op — keeps tests readable.
;; (gt_s is already exercised in if_else.wat.)
(module
  (func (export "i32_eq")    (param i32 i32) (result i32) local.get 0 local.get 1 i32.eq)
  (func (export "i32_ne")    (param i32 i32) (result i32) local.get 0 local.get 1 i32.ne)
  (func (export "i32_lt_s")  (param i32 i32) (result i32) local.get 0 local.get 1 i32.lt_s)
  (func (export "i32_le_s")  (param i32 i32) (result i32) local.get 0 local.get 1 i32.le_s)
  (func (export "i32_ge_s")  (param i32 i32) (result i32) local.get 0 local.get 1 i32.ge_s)
  (func (export "i32_eqz")   (param i32)     (result i32) local.get 0 i32.eqz))
