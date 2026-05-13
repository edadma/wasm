;; Data segment placed past the end of the declared memory — instantiation
;; must surface MemoryOutOfBounds rather than corrupting whatever lives
;; past the array.
(module
  (memory 1)
  (data (i32.const 65530) "0123456789abcdef")  ;; 16 bytes from 65530 ⇒ past 65536
  (func (export "noop")))
