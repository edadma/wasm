;; Explicitly tests "br to a loop label" — the iteration path. The loop
;; counts down from $n until it hits zero; each iteration ends with
;; `br $top`, which is *not* the natural fall-through (it's an explicit
;; branch back to the loop's start, with the label still on the stack).
;;
;;   countdown(n) returns the number of loop iterations (== n).
(module
  (func (export "countdown") (param $n i32) (result i32)
    (local $iters i32)
    block $exit
      loop $top
        local.get $n
        i32.eqz
        br_if $exit
        local.get $iters
        i32.const 1
        i32.add
        local.set $iters
        local.get $n
        i32.const 1
        i32.sub
        local.set $n
        br $top
      end
    end
    local.get $iters))
