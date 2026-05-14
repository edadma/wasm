;; Phase 8.E.A: SIMD foundations — `v128.const` and end-to-end V128
;; type plumbing.
;;
;; Encoding: 0xFD 0x0C followed by 16 raw little-endian bytes. The
;; text-form lane annotations (`i32x4`, `f64x2`, etc.) are wat-side
;; only; binary just sees 16 bytes. Tests compare raw byte arrays
;; round-tripped through invoke.
;;
;; Also exercises:
;;   - v128 in function result types (Section 1 Type with valtype 0x7B);
;;   - v128 in function param types (caller hands a V128 in via invoke);
;;   - v128 in local declarations (zero-init to 16 zero bytes);
;;   - v128-valued blocktypes (`(block (result v128) ...)`).
(module
  ;; Returns the constant <1, 2, 3, 4> read as four i32 lanes, little-endian.
  (func (export "const_i32x4") (result v128)
    (v128.const i32x4 1 2 3 4))

  ;; Returns the constant <0x0102, 0x0304, 0x0506, 0x0708, 0x090A, 0x0B0C,
  ;; 0x0D0E, 0x0F10> read as eight i16 lanes, demonstrating that the
  ;; binary form is a flat 16-byte little-endian payload.
  (func (export "const_i16x8") (result v128)
    (v128.const i16x8 0x0102 0x0304 0x0506 0x0708 0x090a 0x0b0c 0x0d0e 0x0f10))

  ;; Same constant from a different lane annotation: 16 explicit i8 bytes.
  ;; The resulting V128 should be byte-identical to const_i16x8.
  (func (export "const_i8x16_same") (result v128)
    (v128.const i8x16 0x02 0x01 0x04 0x03 0x06 0x05 0x08 0x07
                      0x0a 0x09 0x0c 0x0b 0x0e 0x0d 0x10 0x0f))

  ;; Identity — a v128 parameter survives a single local round-trip
  ;; unchanged. Exercises v128 parameter typing + local storage.
  (func (export "identity_v128") (param $v v128) (result v128)
    (local $tmp v128)
    (local.set $tmp (local.get $v))
    (local.get $tmp))

  ;; A v128-typed block result. The block ends with v128.const on the
  ;; stack; the outer function returns it.
  (func (export "block_v128") (result v128)
    (block (result v128)
      (v128.const i32x4 0xdeadbeef 0 0 0)))

  ;; A local v128 declared but never written — must be all-zero on read
  ;; (zero-init invariant).
  (func (export "zero_init_local") (result v128)
    (local $z v128)
    (local.get $z)))
