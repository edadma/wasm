;; f64 memory round-trip + f64 local zero-init.
;; load/store IEEE-754 bit patterns survive (NaN, denormals, signed zeros).
(module
  (memory (export "mem") 1)

  (func (export "f64_roundtrip") (param i32 f64) (result f64)
    local.get 0
    local.get 1
    f64.store
    local.get 0
    f64.load)

  (func (export "f64_local_zero") (result f64)
    (local f64)
    local.get 0))
