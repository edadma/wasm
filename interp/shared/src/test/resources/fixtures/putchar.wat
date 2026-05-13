;; Writes "Hi!" via the imported env.putchar host function.
;; Exercises import resolution and call-into-host.
(module
  (import "env" "putchar" (func $putchar (param i32)))
  (func (export "hello")
    i32.const 72   ;; 'H'
    call $putchar
    i32.const 105  ;; 'i'
    call $putchar
    i32.const 33   ;; '!'
    call $putchar))
