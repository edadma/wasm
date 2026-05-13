;; Returns (a + b)^2 — exercises local.get, local.set, local.tee implicitly
;; (we use set+get+get; tee is exercised separately in if/else).
(module
  (func (export "test_locals") (param $a i32) (param $b i32) (result i32)
    (local $tmp i32)
    local.get $a
    local.get $b
    i32.add
    local.set $tmp
    local.get $tmp
    local.get $tmp
    i32.mul))
