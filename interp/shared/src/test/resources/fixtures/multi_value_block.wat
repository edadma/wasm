;; Block with a multi-result type — falls through naturally, the two
;; values land on the surrounding stack, and a subsequent `i32.add`
;; consumes them. Exercises the typeidx-encoded blocktype on the
;; *block* side (paramArity = 0, resultArity = 2).
(module
  (func (export "block_sum") (result i32)
    (block (result i32 i32)
      i32.const 100
      i32.const 23)
    i32.add))
