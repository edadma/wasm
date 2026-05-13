;; Sum of integers 1..n using a while-style block+loop+br_if.
;; The block's exit label is targeted by br_if when i > n; the loop's start
;; label is targeted by the unconditional br at the end of the body.
(module
  (func (export "test_loop") (param $n i32) (result i32)
    (local $i   i32)
    (local $sum i32)
    i32.const 1
    local.set $i
    block $exit
      loop $top
        local.get $i
        local.get $n
        i32.gt_s
        br_if $exit
        local.get $sum
        local.get $i
        i32.add
        local.set $sum
        local.get $i
        i32.const 1
        i32.add
        local.set $i
        br $top
      end
    end
    local.get $sum))
