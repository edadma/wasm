;; Regression for the v128.const-in-const-expr parser gap surfaced by
;; the W3C spec runner. simd_const.wast, simd_splat.wast,
;; simd_lane.wast, and all four simd_store{8,16,32,64}_lane.wast
;; manifests use `(global v128 (v128.const ...))` — the const-expr
;; reader rejected opcode 0xFD because only scalar/ref forms were
;; recognised. Now `0xFD` + sub-opcode 12 + 16 raw bytes is accepted.
(module
  (global $g v128 (v128.const i32x4 0x11223344 0x55667788 0x99aabbcc 0xddeeff00))
  (func (export "read") (result v128)
    (global.get $g)))
