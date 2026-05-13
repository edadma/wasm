;; i64.load{8,16,32}_{s,u} + i64.store{8,16,32} — the narrowing variants.
;; Each round-trip: store a value of the indicated width starting at addr 0,
;; then reload using the corresponding signed or unsigned load.
;;
;; The store narrows to the low N bits; the matching _s load sign-extends,
;; the _u load zero-extends.
(module
  (memory (export "mem") 1)

  (func (export "store8_load8_s") (param i64) (result i64)
    i32.const 0
    local.get 0
    i64.store8
    i32.const 0
    i64.load8_s)

  (func (export "store8_load8_u") (param i64) (result i64)
    i32.const 0
    local.get 0
    i64.store8
    i32.const 0
    i64.load8_u)

  (func (export "store16_load16_s") (param i64) (result i64)
    i32.const 0
    local.get 0
    i64.store16
    i32.const 0
    i64.load16_s)

  (func (export "store16_load16_u") (param i64) (result i64)
    i32.const 0
    local.get 0
    i64.store16
    i32.const 0
    i64.load16_u)

  (func (export "store32_load32_s") (param i64) (result i64)
    i32.const 0
    local.get 0
    i64.store32
    i32.const 0
    i64.load32_s)

  (func (export "store32_load32_u") (param i64) (result i64)
    i32.const 0
    local.get 0
    i64.store32
    i32.const 0
    i64.load32_u))
