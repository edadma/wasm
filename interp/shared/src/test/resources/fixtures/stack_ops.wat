;; Parametric instructions: `select` and `drop`.
;; select pops [a, b, cond] (top is cond) and pushes a if cond != 0 else b.
;; drop discards the top value.
(module
  (func (export "test_select") (param $cond i32) (param $a i32) (param $b i32) (result i32)
    local.get $a
    local.get $b
    local.get $cond
    select)
  (func (export "test_drop") (param $a i32) (param $b i32) (result i32)
    local.get $a
    local.get $b
    drop))
