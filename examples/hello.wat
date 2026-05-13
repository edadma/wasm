;; Classic "Hello, world!\n" via env.putchar.
;;
;; Compile and run:
;;   wat2wasm examples/hello.wat -o examples/hello.wasm
;;   sbt 'wasmJVM/run examples/hello.wasm'
(module
  (import "env" "putchar" (func $putchar (param i32)))
  (func (export "main")
    i32.const 72   call $putchar    ;; 'H'
    i32.const 101  call $putchar    ;; 'e'
    i32.const 108  call $putchar    ;; 'l'
    i32.const 108  call $putchar    ;; 'l'
    i32.const 111  call $putchar    ;; 'o'
    i32.const 44   call $putchar    ;; ','
    i32.const 32   call $putchar    ;; ' '
    i32.const 119  call $putchar    ;; 'w'
    i32.const 111  call $putchar    ;; 'o'
    i32.const 114  call $putchar    ;; 'r'
    i32.const 108  call $putchar    ;; 'l'
    i32.const 100  call $putchar    ;; 'd'
    i32.const 33   call $putchar    ;; '!'
    i32.const 10   call $putchar))  ;; '\n'
