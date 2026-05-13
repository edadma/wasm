;; Edge cases that the i32 set has analogues for; verifies the same rules
;; hold at i64 width.
(module
  ;; div_s trap on divide-by-zero.
  (func (export "div_s_zero") (result i64)
    i64.const 7
    i64.const 0
    i64.div_s)

  ;; div_s trap on signed overflow (MIN_LONG / -1).
  (func (export "div_s_overflow") (result i64)
    i64.const -9223372036854775808
    i64.const -1
    i64.div_s)

  ;; rem_s MIN_LONG % -1 must NOT trap — defined to be 0.
  (func (export "rem_s_min_neg1") (result i64)
    i64.const -9223372036854775808
    i64.const -1
    i64.rem_s)

  ;; rem_s trap on divide-by-zero.
  (func (export "rem_s_zero") (result i64)
    i64.const 7
    i64.const 0
    i64.rem_s)

  ;; rem_u trap on divide-by-zero.
  (func (export "rem_u_zero") (result i64)
    i64.const 7
    i64.const 0
    i64.rem_u)

  ;; Shifts and rotates are mod-64 in the shift count.
  (func (export "shl_mod64") (param i64 i64) (result i64) local.get 0 local.get 1 i64.shl)
  (func (export "rotl_mod64") (param i64 i64) (result i64) local.get 0 local.get 1 i64.rotl)

  ;; Wrap arithmetic — adding 1 to MAX_LONG wraps to MIN_LONG.
  (func (export "add_wrap") (result i64)
    i64.const 9223372036854775807
    i64.const 1
    i64.add))
