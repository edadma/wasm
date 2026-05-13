;; Active data segment writes "AB" (0x41 0x42) at memory offset 0; the
;; exported function loads the i32 at offset 0 which is 0x42 0x41 as the
;; low two bytes (little-endian) = 0x4241.
(module
  (memory 1)
  (data (i32.const 0) "AB")
  (func (export "read") (result i32)
    i32.const 0
    i32.load))
