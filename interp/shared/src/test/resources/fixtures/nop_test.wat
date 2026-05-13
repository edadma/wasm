;; `nop` must be functionally invisible — its only test is that surrounding
;; computation still works when nops are sprinkled through it.
(module
  (func (export "with_nops") (param $a i32) (result i32)
    nop
    local.get $a
    nop
    i32.const 1
    nop
    i32.add
    nop))
