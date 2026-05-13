;; Basic i64 arithmetic: const, add, sub, mul, div_s, div_u, rem_s, rem_u.
;; Each function takes two i64 args and returns one i64 so we can drive every
;; case end-to-end from the Scala-side test harness.
(module
  (func (export "i64_const_pair") (result i64)
    ;; 0x100000001 + 0x200000002 = 0x300000003 (proves SLEB64 immediate works)
    i64.const 0x100000001
    i64.const 0x200000002
    i64.add)

  (func (export "i64_add") (param i64 i64) (result i64)
    local.get 0
    local.get 1
    i64.add)

  (func (export "i64_sub") (param i64 i64) (result i64)
    local.get 0
    local.get 1
    i64.sub)

  (func (export "i64_mul") (param i64 i64) (result i64)
    local.get 0
    local.get 1
    i64.mul)

  (func (export "i64_div_s") (param i64 i64) (result i64)
    local.get 0
    local.get 1
    i64.div_s)

  (func (export "i64_div_u") (param i64 i64) (result i64)
    local.get 0
    local.get 1
    i64.div_u)

  (func (export "i64_rem_s") (param i64 i64) (result i64)
    local.get 0
    local.get 1
    i64.rem_s)

  (func (export "i64_rem_u") (param i64 i64) (result i64)
    local.get 0
    local.get 1
    i64.rem_u))
