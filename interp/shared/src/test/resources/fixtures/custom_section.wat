;; wat2wasm always emits a "name" custom section (section id 0) when given
;; named functions/locals; this fixture deliberately uses many names so the
;; parser is forced to walk past a custom section. The interpreter never
;; touches custom sections — they're skipped silently.
(module
  (func $fancy (export "fancy") (param $first i32) (param $second i32) (result i32)
    (local $tmp i32)
    local.get $first
    local.get $second
    i32.add
    local.set $tmp
    local.get $tmp))
