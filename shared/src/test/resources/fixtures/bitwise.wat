;; The five integer bitwise / shift ops, each as its own export.
;; shl / shr_s naturally exercise the WASM "shift count mod 32" rule when
;; the test passes counts > 31.
(module
  (func (export "i32_and")   (param i32 i32) (result i32) local.get 0 local.get 1 i32.and)
  (func (export "i32_or")    (param i32 i32) (result i32) local.get 0 local.get 1 i32.or)
  (func (export "i32_xor")   (param i32 i32) (result i32) local.get 0 local.get 1 i32.xor)
  (func (export "i32_shl")   (param i32 i32) (result i32) local.get 0 local.get 1 i32.shl)
  (func (export "i32_shr_s") (param i32 i32) (result i32) local.get 0 local.get 1 i32.shr_s))
