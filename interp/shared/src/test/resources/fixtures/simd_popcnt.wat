;; Regression for the missing `i8x16.popcnt` (SIMD sub-opcode 0x62)
;; surfaced by simd_i8x16_arith2.wast in the W3C spec runner. We had
;; abs (0x60) and neg (0x61) but jumped to 0x63 (all_true), leaving
;; popcnt unimplemented. Now lands in the chunk-D unop table.
(module
  (func (export "popcnt") (param $v v128) (result v128)
    (i8x16.popcnt (local.get $v))))
