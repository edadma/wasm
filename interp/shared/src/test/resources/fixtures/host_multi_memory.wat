;; Phase 8.D follow-up: multi-memory HostFunc surface.
;;
;; Two linear memories — mem0 (1 page) and mem1 (1 page). The host
;; imports a single function `host.mark_both(i32 marker_byte)` that
;; writes the marker into mem0[0] and mem1[0] in a single host call.
;; This is the kind of operation a HostFunc (single-memory) cannot
;; express — it sees only mem0; mem1 is invisible. HostFuncMulti
;; takes the full memory vector and can target any of them.
;;
;; Exports drive verification: mark_via_host invokes the host import,
;; then load_mem0_byte / load_mem1_byte let the test read each memory
;; back. The fixture is otherwise minimal — the value is the host
;; reach, not the wasm side.
(module
  (import "host" "mark_both" (func $mark_both (param i32)))

  (memory (export "mem0") 1 1)
  (memory (export "mem1") 1 1)

  (func (export "mark_via_host") (param $byte i32)
    (call $mark_both (local.get $byte)))

  (func (export "load_mem0_byte") (param $addr i32) (result i32)
    (i32.load8_u 0 (local.get $addr)))

  (func (export "load_mem1_byte") (param $addr i32) (result i32)
    (i32.load8_u 1 (local.get $addr))))
