;; Phase 2 — globals (basic): one mutable counter + one immutable seed.
;;
;; Demonstrates the headline behaviour globals add over locals: persistence
;; *across* function calls. `bump` reads `counter`, adds 1, stores back; two
;; consecutive `bump` calls must observe the running total. `seed` is `const`
;; — `try_write_seed` exists only to be invoked by the immutable-trap test
;; (it's a hot landmine, not a real entry point).
(module
  (global $counter (mut i32) (i32.const 0))
  (global $seed         i32  (i32.const 42))

  ;; Read the running counter without touching it.
  (func (export "get_count") (result i32)
    global.get $counter)

  ;; counter = counter + 1; return new value. There is no `global.tee`
  ;; (only `local.tee`), so we compute, store, then re-fetch.
  (func (export "bump") (result i32)
    global.get $counter
    i32.const 1
    i32.add
    global.set $counter
    global.get $counter)

  ;; counter = arg; return prior value.
  (func (export "set_count") (param i32) (result i32)
    global.get $counter              ;; old value, kept for return
    local.get 0
    global.set $counter)

  ;; Read the immutable seed.
  (func (export "get_seed") (result i32)
    global.get $seed)

  ;; Also export the globals themselves — lets the host read the live
  ;; values without going through a getter function, which the per-type
  ;; round-trip tests want.
  (export "counter" (global $counter))
  (export "seed"    (global $seed)))
