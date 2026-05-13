;; sign(a): 1 if a > 0, otherwise -1.
;; Exercises i32.gt_s, if/else with i32 result, local.tee (via the parameter store-and-test idiom).
(module
  (func (export "test_if") (param $a i32) (result i32)
    local.get $a
    local.tee 0     ;; redundant but exercises local.tee
    i32.const 0
    i32.gt_s
    (if (result i32)
      (then i32.const 1)
      (else i32.const -1))))
