;; Phase 8.B: bulk-memory remainder — memory.init / data.drop / table.init /
;; elem.drop / table.copy. Five sub-opcodes under 0xFC; same dispatch
;; pattern as the existing memory.copy / memory.fill (Phase 7.B).
;;
;; Layout:
;;   - One page (64 KiB) of memory exported as "memory".
;;   - One passive data segment containing the bytes "ABCDEFGH" (8 bytes)
;;     — addressable as dataidx 0.
;;   - Two funcref tables of size 8 — primary "table" at tableidx 0,
;;     secondary "table2" at tableidx 1 (for table.copy across-tables).
;;   - Four trivial functions f0..f3 each returning their own funcidx as
;;     an i32 — gives a deterministic way to verify which funcref a table
;;     slot now holds (call_indirect through it and check the returned int).
;;   - One passive element segment with funcidx vec [f0, f1, f2, f3]
;;     — addressable as elemidx 0.
;;
;; The exports drive every opcode under test plus the
;; load_byte / read_table helpers tests use to check post-state.
;;
;; wat2wasm emits Section 12 (Data Count) automatically because memory.init
;; / data.drop are present — required by spec.
(module
  (memory (export "memory") 1)

  (table (export "table")  8 funcref)
  (table (export "table2") 8 funcref)

  ;; Passive data: 8 bytes addressable as dataidx 0.
  (data "ABCDEFGH")

  ;; Identity-like helpers — each returns its own funcidx as an i32.
  ;; Used as the funcref payload for the passive element segment and as
  ;; the call_indirect target inside `read_table`.
  (func $f0 (result i32) (i32.const 0))
  (func $f1 (result i32) (i32.const 1))
  (func $f2 (result i32) (i32.const 2))
  (func $f3 (result i32) (i32.const 3))

  ;; Passive element segment: indices addressable as elemidx 0.
  (elem funcref (ref.func $f0) (ref.func $f1) (ref.func $f2) (ref.func $f3))

  ;; --- memory.init / data.drop --------------------------------------
  (func (export "do_memory_init") (param $dst i32) (param $src i32) (param $n i32)
    (memory.init 0
      (local.get $dst)
      (local.get $src)
      (local.get $n)))

  (func (export "do_data_drop")
    (data.drop 0))

  ;; --- table.init / elem.drop / table.copy --------------------------
  (func (export "do_table_init") (param $dst i32) (param $src i32) (param $n i32)
    (table.init 0 0
      (local.get $dst)
      (local.get $src)
      (local.get $n)))

  (func (export "do_elem_drop")
    (elem.drop 0))

  (func (export "do_table_copy") (param $dst i32) (param $src i32) (param $n i32)
    (table.copy 0 0
      (local.get $dst)
      (local.get $src)
      (local.get $n)))

  (func (export "do_table_copy_across") (param $dst i32) (param $src i32) (param $n i32)
    ;; dst tableidx 1, src tableidx 0
    (table.copy 1 0
      (local.get $dst)
      (local.get $src)
      (local.get $n)))

  ;; --- read helpers --------------------------------------------------
  (func (export "load_byte") (param $addr i32) (result i32)
    (i32.load8_u (local.get $addr)))

  (type $i32fn (func (result i32)))

  ;; Reads tableidx 0, slot `n`, invokes through call_indirect and returns
  ;; whatever the funcref reports as its index. If `n` is a null slot
  ;; the program traps — tests use this to confirm in-range vs out-of-range
  ;; outcomes.
  (func (export "read_table") (param $n i32) (result i32)
    (call_indirect 0 (type $i32fn) (local.get $n)))

  (func (export "read_table2") (param $n i32) (result i32)
    (call_indirect 1 (type $i32fn) (local.get $n))))
