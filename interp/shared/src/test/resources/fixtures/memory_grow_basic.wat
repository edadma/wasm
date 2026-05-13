;; Phase 4 — memory.size / memory.grow with a declared max.
;;
;; Memory starts at 1 page (= 64 KiB), capped at 4 pages. The exports
;; exercise both opcodes plus the grow-failure return path:
;;
;;   get_size       → current page count
;;   grow_by(n)     → memory.grow with delta n; returns prev size on
;;                    success, -1 on failure (does NOT trap)
;;   store_then_grow(addr, byte, delta)
;;                  → write `byte` at `addr`, then grow by `delta`,
;;                    then read the byte back. Pins persistence of
;;                    written bytes across a successful grow.
(module
  (memory 1 4)

  (func (export "get_size") (result i32)
    memory.size)

  (func (export "grow_by") (param i32) (result i32)
    local.get 0
    memory.grow)

  (func (export "store_then_grow") (param $addr i32) (param $byte i32) (param $delta i32) (result i32)
    local.get $addr
    local.get $byte
    i32.store8
    local.get $delta
    memory.grow
    drop
    local.get $addr
    i32.load8_u))
