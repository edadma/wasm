;; br_table over a 3-entry vec + default. Exposes a single function
;; (`select i32) -> i32` whose result identifies which branch ran:
;;   sel == 0 → 10
;;   sel == 1 → 20
;;   sel == 2 → 30
;;   sel  ∉ [0, 3) → 99   (default)
;;
;; The labels resolve outward: br 0 = the outermost block (the one
;; whose end is the closest "after"). We use three nested blocks plus
;; a default-block wrapper, with br_table at the centre. Each block's
;; "after" position emits a different i32.const, then a `return`.
(module
  (func (export "select") (param $sel i32) (result i32)
    (block $defl
      (block $l2
        (block $l1
          (block $l0
            (local.get $sel)
            (br_table $l0 $l1 $l2 $defl))
          ;; reached by br_table → $l0  (sel == 0)
          (i32.const 10)
          (return))
        ;; reached by br_table → $l1  (sel == 1)
        (i32.const 20)
        (return))
      ;; reached by br_table → $l2  (sel == 2)
      (i32.const 30)
      (return))
    ;; reached by br_table → $defl  (sel out of range)
    (i32.const 99)))
