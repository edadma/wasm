;; Phase 3 — call_indirect (trap paths): a table sized 4 with only
;; slots 0..1 populated (slots 2..3 stay as null funcrefs because the
;; element segment is shorter than the table). Exports three entry
;; points that exercise each trap class.
(module
  (type $i_i (func (param i32) (result i32)))

  (table 4 funcref)
  (elem (i32.const 0) $id $minus_one)

  (func $id (type $i_i)
    local.get 0)

  (func $minus_one (type $i_i)
    local.get 0
    i32.const 1
    i32.sub)

  ;; Slot 2 / 3 are null — calling either traps with "null funcref".
  (func (export "via_null") (param i32) (result i32)
    local.get 0
    i32.const 2
    call_indirect (type $i_i))

  ;; Slot 99 is well past the table — traps with "out of table bounds".
  (func (export "via_oob") (param i32) (result i32)
    local.get 0
    i32.const 99
    call_indirect (type $i_i)))
