;; i32.store crossing the end of linear memory must trap. The companion
;; load test only covers reads; this exercises the write path of the
;; bounds check.
(module
  (memory 1)
  (func (export "store_oob") (param $addr i32) (param $val i32)
    local.get $addr
    local.get $val
    i32.store))
