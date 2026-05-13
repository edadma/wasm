;; Recursive factorial: fact(n) = if n <= 1 then 1 else n * fact(n-1).
;; Exercises `call` to the same function and recursive frame handling.
(module
  (func $fact (export "fact") (param $n i32) (result i32)
    local.get $n
    i32.const 1
    i32.le_s
    (if (result i32)
      (then i32.const 1)
      (else
        local.get $n
        local.get $n
        i32.const 1
        i32.sub
        call $fact
        i32.mul))))
