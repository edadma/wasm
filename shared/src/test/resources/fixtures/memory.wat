;; Store an i32 at $addr and load it back. Round-trips through linear memory.
;; Also stores a byte and reloads it unsigned to exercise i32.store8 / i32.load8_u.
(module
  (memory 1)
  (func (export "i32_roundtrip") (param $addr i32) (param $val i32) (result i32)
    local.get $addr
    local.get $val
    i32.store
    local.get $addr
    i32.load)
  (func (export "byte_roundtrip") (param $addr i32) (param $val i32) (result i32)
    local.get $addr
    local.get $val
    i32.store8
    local.get $addr
    i32.load8_u))
