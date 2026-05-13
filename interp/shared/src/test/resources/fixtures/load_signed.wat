;; Stores one byte and reads it back with i32.load8_s. The reload must
;; sign-extend, so writing 0xFF (=-1 as signed byte) must return -1, not 255.
(module
  (memory 1)
  (func (export "store_load_signed") (param $addr i32) (param $val i32) (result i32)
    local.get $addr
    local.get $val
    i32.store8
    local.get $addr
    i32.load8_s))
