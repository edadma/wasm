;; Phase 3 — call_indirect (positive): one table, two same-signature
;; functions, dispatch by index passed from outside. Pins the headline
;; behaviour — selecting a callee at run time without a `call funcidx`.
(module
  (type $i_i (func (param i32) (result i32)))

  (table 2 funcref)
  (elem (i32.const 0) $add_one $double)

  (func $add_one (type $i_i)
    local.get 0
    i32.const 1
    i32.add)

  (func $double (type $i_i)
    local.get 0
    local.get 0
    i32.add)

  ;; (slot_idx, arg) -> result. Looks the function up in table 0 and
  ;; calls it with the second argument.
  (func (export "dispatch") (param $slot i32) (param $arg i32) (result i32)
    local.get $arg
    local.get $slot
    call_indirect (type $i_i)))
