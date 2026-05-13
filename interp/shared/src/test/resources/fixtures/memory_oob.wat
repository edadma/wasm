;; Loads beyond the end of linear memory must trap with MemoryOutOfBounds.
;; Memory is 1 page (65 536 bytes); load at 65 535 reads bytes 65 535..65 538
;; which is partially past the end.
(module
  (memory 1)
  (func (export "load_oob") (param $addr i32) (result i32)
    local.get $addr
    i32.load))
