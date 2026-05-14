;; A function returning two i32s, and a caller that consumes both. The
;; callee's pair lands on the value stack as two separate values; the
;; caller's `i32.add` immediately consumes them. Verifies that the
;; existing call/return paths handle multi-result without any
;; additional plumbing — once function signatures accept multi-result
;; result vectors, the runtime takeRight(arity) loop just works.
(module
  (func $pair (result i32 i32)
    i32.const 100
    i32.const 200)
  (func (export "sum_pair") (result i32)
    call $pair
    i32.add))
