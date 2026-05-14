;; Loop with a single i32 param + result. `br $cont` carries the
;; current value back into the loop as the new param — verifying that
;; loop branches re-feed paramArity values (not resultArity). Counts
;; how many times the input halves before reaching 1 (floor(log2 n)).
;; For n = 8 → 3; for n = 1 → 0; for n = 16 → 4.
(module
  (func (export "log2_floor") (param $n i32) (result i32)
    (local $cur i32) (local $count i32)
    local.get $n
    (loop $cont (param i32) (result i32)
      local.set $cur
      local.get $cur
      i32.const 1
      i32.gt_s
      if
        local.get $count
        i32.const 1
        i32.add
        local.set $count
        local.get $cur
        i32.const 1
        i32.shr_s
        br $cont
      end
      local.get $count)))
