;; Function with an empty body (just the terminating `end`). The
;; interpreter's outer-end handler — frame.labels.isEmpty at end —
;; is the only path here; no opcodes are dispatched.
(module
  (func (export "empty")))
