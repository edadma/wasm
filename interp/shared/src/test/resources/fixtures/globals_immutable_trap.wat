;; Phase 2 — globals (mutability trap): exports a function that attempts
;; `global.set` against an immutable global. Phase 2 catches this at run
;; time (Phase 6 will lift it into a validation-time rejection — at which
;; point this fixture should fail to instantiate rather than fail to call).
(module
  (global $k i32 (i32.const 99))

  (func (export "try_overwrite") (param i32)
    local.get 0
    global.set $k)

  (func (export "get_k") (result i32)
    global.get $k))
