;; `if` *without* an `else` branch — when cond is false the interpreter
;; must skip straight past the matching `end` without leaking the
;; structured-block label. (This is the exact bug a naive implementation
;; tends to ship with; pinning it with a test.)
;;
;;   maybe_inc(cond, n) = if cond then n + 1 else n
(module
  (func (export "maybe_inc") (param $cond i32) (param $n i32) (result i32)
    local.get $cond
    (if
      (then
        local.get $n
        i32.const 1
        i32.add
        local.set 1))
    local.get $n))
