;; Phase 4 — memory.grow with no declared max.
;;
;; Starts at 1 page; the grow request (4 pages = 256 KiB total) is well
;; within the host's implicit cap and the Int.MaxValue-bytes guard. Pins
;; that the absence of a `(memory 1 N)` upper bound doesn't artificially
;; block grow.
(module
  (memory 1)

  (func (export "size_initial") (result i32)
    memory.size)

  (func (export "grow_and_resize") (result i32)
    i32.const 4
    memory.grow                 ;; -> 1 (the previous page count)
    drop
    memory.size))               ;; -> 5
