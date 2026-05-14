;; Section 8 (Start) writing to linear memory. Sysl-style backends
;; would typically use the start hook to prime constants, set up
;; arenas, etc. — this fixture pins that memory writes from within a
;; start function survive into ModuleInstance and are observable
;; through `peek`. (Active data segments already initialise memory
;; before start runs; start can then layer on dynamic init.)
(module
  (memory (export "mem") 1)
  (func $init
    i32.const 100
    i32.const 0x12345678
    i32.store)
  (func (export "peek") (result i32)
    i32.const 100
    i32.load)
  (start $init))
