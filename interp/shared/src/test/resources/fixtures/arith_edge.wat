;; Edge cases for integer arithmetic that must trap or follow special rules.
;;   div_zero    — i32.div_s by 0   → trap
;;   div_overflow— MIN_INT / -1     → trap
;;   rem_min_neg1— MIN_INT % -1     → 0 (per WASM spec; differs from Java)
;;   shl_mod32   — shl 1 by 33      → 2 (count masked to low 5 bits)
;;   shr_s_mod32 — shr_s -8 by 33   → -4 (arithmetic shift, count mod 32)
(module
  (func (export "div_zero") (result i32)
    i32.const 10
    i32.const 0
    i32.div_s)
  (func (export "div_overflow") (result i32)
    i32.const -2147483648
    i32.const -1
    i32.div_s)
  (func (export "rem_min_neg1") (result i32)
    i32.const -2147483648
    i32.const -1
    i32.rem_s)
  (func (export "shl_mod32") (param $a i32) (param $b i32) (result i32)
    local.get $a
    local.get $b
    i32.shl)
  (func (export "shr_s_mod32") (param $a i32) (param $b i32) (result i32)
    local.get $a
    local.get $b
    i32.shr_s))
