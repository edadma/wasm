;; Phase 2 — globals (per-type round-trip): one mutable global per scalar
;; type with a non-zero init value, plus getters and setters. Pins that
;; each `*.const` form in a section-6 init expr decodes correctly (the
;; f32/f64 immediates are 4 / 8 raw little-endian IEEE-754 bytes — distinct
;; from the LEB128 used for the i32/i64 forms).
(module
  (global $gi (mut i32) (i32.const  0x0bad0dad))
  (global $gj (mut i64) (i64.const  0x1122334455667788))
  (global $gf (mut f32) (f32.const  1.5))
  (global $gd (mut f64) (f64.const -2.5))

  (func (export "get_i32") (result i32) global.get $gi)
  (func (export "get_i64") (result i64) global.get $gj)
  (func (export "get_f32") (result f32) global.get $gf)
  (func (export "get_f64") (result f64) global.get $gd)

  (func (export "set_i32") (param i32) local.get 0 global.set $gi)
  (func (export "set_i64") (param i64) local.get 0 global.set $gj)
  (func (export "set_f32") (param f32) local.get 0 global.set $gf)
  (func (export "set_f64") (param f64) local.get 0 global.set $gd)

  ;; Also surface the globals themselves — the immutable-export path
  ;; doesn't apply here (these are mut), but the export-table layout
  ;; with mixed kinds is exercised.
  (export "gi" (global $gi))
  (export "gj" (global $gj))
  (export "gf" (global $gf))
  (export "gd" (global $gd)))
