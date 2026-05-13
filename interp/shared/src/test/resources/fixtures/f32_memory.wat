;; f32 memory round-trip + f32 local zero-init.
;; load/store IEEE-754 bit patterns survive (NaN, denormals, signed zeros).
(module
  (memory (export "mem") 1)

  (func (export "f32_roundtrip") (param i32 f32) (result f32)
    local.get 0
    local.get 1
    f32.store
    local.get 0
    f32.load)

  (func (export "f32_local_zero") (result f32)
    (local f32)
    local.get 0))
