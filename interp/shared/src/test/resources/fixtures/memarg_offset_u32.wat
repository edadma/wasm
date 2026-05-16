;; Regression for the u32 memarg offset bug surfaced by the W3C spec
;; runner against testsuite/address.wast lines 207–211. memarg.offset is
;; a wasm u32 (0..2^32-1). Earlier, MemArg.offset was an `Int`, so an
;; offset like 4294967295 (0xFFFFFFFF) was stored as Java's -1 and then
;; sign-extended on the `addr + offset` Long sum — turning a guaranteed-
;; OOB load into a wrap-to-low-memory load. Widening the field to Long
;; and masking on construction fixes it.
(module
  (memory 1)
  (func (export "load_off_max") (param $i i32) (result i32)
    (i32.load offset=4294967295 (local.get $i))))
