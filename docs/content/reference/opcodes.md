---
title: Opcodes
summary: Every WebAssembly opcode group the interpreter handles, plus what's coming in Phase 8.
weight: 10
---

The interpreter implements the WebAssembly Core MVP plus the sign-extension proposal, the full bulk-memory proposal, and non-trapping float-to-int (`trunc_sat_*`). That's enough to run real `wasm32-wasip1` binaries produced by rustc end-to-end, and to host the full sysl standard-library test suite end-to-end as sysl's `wasm32-WASI` backend.

## Numeric (full MVP, all four scalar types)

Every `i32` / `i64` / `f32` / `f64` opcode:

- **Constants** — `const` for each type.
- **Comparisons** — signed and unsigned for ints (`lt_s`, `lt_u`, `le_s`, …); ordered for floats (`lt`, `le`, `gt`, `ge`, `eq`, `ne`).
- **Arithmetic** — `add`, `sub`, `mul`, `div_s` / `div_u` (ints), `div` (floats), `rem_s` / `rem_u`.
- **Bitwise** — `and`, `or`, `xor` (ints).
- **Shifts** — `shl`, `shr_s`, `shr_u`, `rotl`, `rotr` (ints).
- **Conversion** — every cross-type cast in the MVP (`i32.wrap_i64`, `i64.extend_i32_s`/`_u`, `i32.trunc_f32_s`/`_u`/…, `f32.convert_i32_s`/`_u`/…, `f32.demote_f64`, `f64.promote_f32`).
- **Reinterpretation** — `i32.reinterpret_f32`, `f64.reinterpret_i64`, etc. (bit-level recasts that don't change the value's bits).

IEEE-754 results are deterministic across JVM, Scala.js, and Scala Native — including NaN bit patterns, signed-zero, and subnormal edges.

## Sign-extension proposal

`i32.extend8_s`, `i32.extend16_s`, `i64.extend8_s`, `i64.extend16_s`, `i64.extend32_s`. Lifts a narrow signed value into the full operand-stack width. Required by rustc-built binaries.

## Non-trapping float-to-int (Phase 8.A)

The eight `trunc_sat_*` sub-opcodes under the `0xFC` prefix (sub-opcodes 0..7):

- `i32.trunc_sat_f32_s` / `_u`, `i32.trunc_sat_f64_s` / `_u`
- `i64.trunc_sat_f32_s` / `_u`, `i64.trunc_sat_f64_s` / `_u`

Where `trunc_f32_s` of NaN or out-of-range traps, `trunc_sat_f32_s` returns 0 for NaN and saturates at the type's `MIN_VALUE` / `MAX_VALUE` for out-of-range. Required by rustc binaries built with `-C target-feature=+nontrapping-fptoint` (now the default on stable).

## Variables

`local.get`, `local.set`, `local.tee`, `global.get`, `global.set`. Mutable and immutable globals are both supported; `global.set` on an immutable global is caught by the validator.

## Control flow

`block`, `loop`, `if` / `else` / `end`, `br`, `br_if`, `br_table`, `return`, `call`, `call_indirect`, `unreachable`, `nop`.

Multi-value blocks, loops, and ifs are supported — block parameters get re-fed on `br` to a loop, `br_if` carries multi-result values, etc.

## Memory

- **Load/store** — every width variant: `i32.load`, `i32.load8_s`/`_u`, `i32.load16_s`/`_u`, `i64.load`, `i64.load8_s`/…/`load32_s`/`_u`, `f32.load`, `f64.load`, plus all matching stores.
- **Sizing** — `memory.size` and `memory.grow`. The optional `max` from section 5 is honoured: `grow` past it returns `-1` rather than expanding.
- **Bulk-memory** — the full proposal, all seven ops under the `0xFC` prefix:
    - `memory.copy` (sub `0x0A`), `memory.fill` (sub `0x0B`) — Phase 7.B.
    - `memory.init` (sub `0x08`), `data.drop` (sub `0x09`) — Phase 8.B.
    - `table.init` (sub `0x0C`), `elem.drop` (sub `0x0D`), `table.copy` (sub `0x0E`) — Phase 8.B.

  `memory.init` / `table.init` copy from passive data / element segments;
  `data.drop` / `elem.drop` mark a segment as zero-length (idempotent).
  Active segments are still initialised at instantiation and then marked
  dropped automatically — subsequent `*.init` with `n > 0` traps, matching
  wasmtime / V8 / wabt semantics.

### Passive vs active data + element segments

Section 11 (data) and section 9 (element) now carry sealed-trait segment kinds. Active segments behave as before (copied at instantiation). Passive segments stay addressable by `dataidx` / `elemidx` until the matching `.drop`. Declarative element segments parse cleanly but are no-ops at runtime — they pre-declare funcrefs for `ref.func`, which arrives in Phase 8.C. Element-expression-bearing element segments (flags 4..7) are still rejected at parse time and land with reference types.

## Tables + functions

Section 4 + Section 9 funcref tables. `call_indirect` does a signature check at the call site against the operand-stack types and the target function's declared type; a mismatch traps with `Left(InvalidModule("call_indirect type mismatch"))`.

## Stack

`drop`, `select`.

## What isn't implemented yet

The Phase-8 menu after `trunc_sat` + bulk-memory remainder:

| Group | Sub-opcodes | Status |
|---|---|---|
| Reference types | `ref.null`, `ref.is_null`, `ref.func`, `externref`, `table.get`/`table.set`/`table.size`/`table.grow`/`table.fill` | not yet (8.C) |
| Multi-memory | every memory opcode with a non-zero memory index | not yet (8.D) |
| SIMD (v128) | every `v128.*` opcode, `i8x16.*`, `i16x8.*`, `i32x4.*`, `i64x2.*`, `f32x4.*`, `f64x2.*` | not yet (8.E) |
| Threads + atomics | every `*.atomic.*` opcode, `memory.atomic.*` | not planned |
| Exception handling | `try` / `catch` / `throw` / `rethrow` | not planned |
| GC proposal | `struct.*`, `array.*`, `ref.cast`, etc. | not planned |
| Component model | the post-MVP packaging surface | out of scope |

Each missing group is independently scoped — adding any one of them is a self-contained piece of work that doesn't touch the others. See the project [roadmap on GitHub](https://github.com/edadma/wasm) for the active Phase-8 plan.

## Validation pass

Every imported module runs through a separate validator before any code executes. See [Concepts → Validation](/concepts/validation/).

## Binary sections

| Section | ID | What it carries |
|---|---|---|
| Type     | 1  | Function signatures |
| Import   | 2  | Functions, memories, globals, tables imported from the host |
| Function | 3  | Function-index → type-index mapping |
| Table    | 4  | Funcref tables |
| Memory   | 5  | Linear-memory definitions |
| Global   | 6  | Module-level globals |
| Export   | 7  | Names exposed to the host |
| Start    | 8  | Function index run at instantiate time |
| Element  | 9  | Funcref table initializers |
| Code     | 10 | Function bodies |
| Data     | 11 | Linear-memory initializers (active + passive) |
| DataCount | 12 | u32 = number of data segments; required when a function uses `memory.init` or `data.drop` |

Custom sections are skipped harmlessly.
