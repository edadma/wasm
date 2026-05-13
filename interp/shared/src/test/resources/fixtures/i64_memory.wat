;; i64.load / i64.store round-trip, plus an i64 local round-trip.
;; Memory is one page (64 KiB). Each helper takes an i32 address + i64 value,
;; stores, then reloads — proving the LE 8-byte load and store cooperate.
(module
  (memory (export "mem") 1)

  (func (export "i64_roundtrip") (param i32 i64) (result i64)
    local.get 0
    local.get 1
    i64.store
    local.get 0
    i64.load)

  ;; Sanity check: an i64 local zero-initializes to 0 (separate from i32 locals).
  (func (export "i64_local_zero") (result i64)
    (local i64)
    local.get 0))
