;; Phase 3 — call_indirect (signature check): a table populated with
;; functions of three different signatures. Each exported entry point
;; expects a specific slot; calling through a slot whose signature
;; doesn't match the typeidx immediate is a trap.
;;
;;   slot 0 : (i32) -> i32          neg
;;   slot 1 : (i64) -> i64          neg64
;;   slot 2 : (i32, i32) -> i32     mul
;;
(module
  (type $i_i   (func (param i32) (result i32)))
  (type $l_l   (func (param i64) (result i64)))
  (type $ii_i  (func (param i32) (param i32) (result i32)))

  (table 3 funcref)
  (elem (i32.const 0) $neg $neg64 $mul)

  (func $neg (type $i_i)
    i32.const 0
    local.get 0
    i32.sub)

  (func $neg64 (type $l_l)
    i64.const 0
    local.get 0
    i64.sub)

  (func $mul (type $ii_i)
    local.get 0
    local.get 1
    i32.mul)

  ;; well-typed dispatch through slot 0
  (func (export "call_neg_i32") (param i32) (result i32)
    local.get 0
    i32.const 0
    call_indirect (type $i_i))

  ;; well-typed dispatch through slot 1
  (func (export "call_neg_i64") (param i64) (result i64)
    local.get 0
    i32.const 1
    call_indirect (type $l_l))

  ;; well-typed dispatch through slot 2
  (func (export "call_mul") (param i32) (param i32) (result i32)
    local.get 0
    local.get 1
    i32.const 2
    call_indirect (type $ii_i))

  ;; sig-mismatch trap path: caller declares (i32)->i32 but slot 2 is
  ;; (i32, i32)->i32. Pushes one dummy i32 so the operand stack has
  ;; *something* for the (wrong) declared type before the trap fires.
  (func (export "wrong_sig") (param i32) (result i32)
    local.get 0
    i32.const 2
    call_indirect (type $i_i)))
