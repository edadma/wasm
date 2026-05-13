;; 2's-complement wrap-around for the three core arithmetic ops.
;;
;;   add_wrap: MAX_INT + 1            -> MIN_INT
;;   sub_wrap: MIN_INT - 1            -> MAX_INT
;;   mul_wrap: 0x10000 * 0x10000      -> 0       (top 32 bits dropped)
;;
;; All three are defined by the WASM spec as i32 add/sub/mul mod 2^32.
(module
  (func (export "add_wrap") (result i32)
    i32.const 2147483647    ;; MAX_INT
    i32.const 1
    i32.add)
  (func (export "sub_wrap") (result i32)
    i32.const -2147483648   ;; MIN_INT
    i32.const 1
    i32.sub)
  (func (export "mul_wrap") (result i32)
    i32.const 65536
    i32.const 65536
    i32.mul))
