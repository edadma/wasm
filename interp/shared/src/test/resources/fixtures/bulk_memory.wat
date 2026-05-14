;; Bulk-memory subset used in Phase 7.D: memory.copy + memory.fill.
;; One page memory (64 KiB) exported. Three helpers:
;;   - `do_copy (dst src n)` invokes memory.copy
;;   - `do_fill (dst v n)`  invokes memory.fill (low 8 bits of v)
;;   - `load_byte (addr)`   returns the byte at `addr` as i32 (zero-extended)
;;   - `store_byte (addr v)` writes the low 8 bits of `v` at `addr`
(module
  (memory (export "memory") 1)

  (func (export "do_copy") (param $dst i32) (param $src i32) (param $n i32)
    (local.get $dst)
    (local.get $src)
    (local.get $n)
    (memory.copy))

  (func (export "do_fill") (param $dst i32) (param $v i32) (param $n i32)
    (local.get $dst)
    (local.get $v)
    (local.get $n)
    (memory.fill))

  (func (export "load_byte") (param $addr i32) (result i32)
    (local.get $addr)
    (i32.load8_u))

  (func (export "store_byte") (param $addr i32) (param $v i32)
    (local.get $addr)
    (local.get $v)
    (i32.store8)))
