;; Regression for the missing `i16x8.q15mulr_sat_s` (SIMD sub-opcode
;; 0x82) surfaced by simd_i16x8_q15mulr_sat_s.wast in the W3C spec
;; runner. Only the relaxed-SIMD form (0x111) was implemented. Spec
;; semantics: (a * b + 0x4000) >> 15, saturated to [-32768, 32767].
;; The clamp matters specifically for (-32768) × (-32768) which would
;; otherwise produce 32768.
(module
  (func (export "q15mulr") (param $a v128) (param $b v128) (result v128)
    (i16x8.q15mulr_sat_s (local.get $a) (local.get $b))))
