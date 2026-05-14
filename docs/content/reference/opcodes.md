---
title: Opcodes
summary: Every WebAssembly opcode group the interpreter handles, plus what's coming in Phase 8.
weight: 10
---

The interpreter implements the WebAssembly Core MVP plus the sign-extension proposal, the full bulk-memory proposal, non-trapping float-to-int (`trunc_sat_*`), the reference-types proposal (funcref, externref, `ref.null` / `ref.is_null` / `ref.func`, `table.get` / `table.set` / `table.size` / `table.grow` / `table.fill`, typed `select t*`), the multi-memory proposal (every memory opcode now carries a memidx; modules may declare more than one linear memory, with a parallel `HostFuncMulti` surface for host functions that need to reach beyond memidx 0), and the **foundations of the SIMD proposal** (`V128` value type plumbed end-to-end + `v128.const`; lane-aware ops landing chunk-by-chunk through 8.E). That's enough to run real `wasm32-wasip1` binaries produced by rustc end-to-end, and to host the full sysl standard-library test suite end-to-end as sysl's `wasm32-WASI` backend.

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

Section 11 (data) and section 9 (element) carry sealed-trait segment kinds. Active segments behave as before (copied at instantiation). Passive segments stay addressable by `dataidx` / `elemidx` until the matching `.drop`. Declarative element segments pre-declare funcrefs for `ref.func`. Element-expression-bearing forms (flags 4..7) parse `ref.null` and `ref.func` as their constant expressions; segments may carry either funcref or externref payloads.

## Reference types (Phase 8.C)

Funcref + externref ride on a small set of new opcodes:

- **`ref.null`** (`0xD0` + reftype byte) — typed null reference. `ref.null func` produces a null funcref; `ref.null extern` a null externref.
- **`ref.is_null`** (`0xD1`) — pop a reference, push `1` if it's a `ref.null`, else `0`.
- **`ref.func`** (`0xD2` + funcidx LEB) — produce a non-null funcref pointing at the named function. The validator enforces that the funcidx is *declared* — i.e. it appears in an export, in `start`, or in any element segment. Body-only references would be circular and so don't count.
- **`table.get`** (`0x25` + tableidx LEB), **`table.set`** (`0x26` + tableidx LEB) — read / write a table slot. Operand type is the table's reftype.
- **`table.size`** (`0xFC` sub `16` + tableidx LEB), **`table.grow`** (`0xFC` sub `15` + tableidx LEB), **`table.fill`** (`0xFC` sub `17` + tableidx LEB) — runtime-side table resize + range fill, with a typed fill value.

Externref slots carry an opaque host `AnyRef`. Wasm code can only move them around (`table.{get,set}`, `local.{get,set}`, `global.{get,set}`, `ref.is_null`); inspection happens host-side via the public API. The host hands them in as `Value.RefExtern(yourObject)` and pulls them back as the same identity.

`call_indirect` is now spec-restricted to funcref tables (an externref table can't carry callable funcrefs); the validator rejects mismatches before code runs.

## Tables + functions

Section 4 funcref + externref tables. `call_indirect` does a signature check at the call site against the operand-stack types and the target function's declared type; a mismatch traps with `Left(InvalidModule("call_indirect type mismatch"))`.

## Stack

`drop`, `select`. Two `select` forms:

- **Untyped `select`** (`0x1B`) — operand types are inferred. Spec-restricted to numeric value types when reference types are present; a reftype operand is rejected at validation with a "use select t*" diagnostic.
- **Typed `select t*`** (`0x1C`) — explicit operand type, encoded as `0x1C u32:count valtype[count]` with `count == 1` (multi-value `select` isn't enabled by any shipped proposal). Required for funcref / externref operands; also accepts the four numeric scalars.

## SIMD (Phase 8.E, in progress)

The WebAssembly SIMD proposal adds ~236 opcodes under the `0xFD` prefix and a new `V128` value type (16 raw bytes, lane interpretation chosen per-opcode). Landing the proposal is a multi-chunk project; **Chunk A (foundations + `v128.const`) is shipped.** Chunks B..I add the lane-aware load/store, splat/extract/replace, integer and float arithmetic, comparisons, conversions, and the special dot/lane ops.

### Foundations (Chunk A — done)

- **`V128` value type** (wire byte `0x7B`). First-class in function params, results, locals, globals, and blocktypes. Locals zero-init to 16 zero bytes.
- **`v128.const`** (`0xFD 0x0C` + 16 raw little-endian bytes). The wat-side annotations (`i32x4 1 2 3 4`, `i16x8 ...`, etc.) are text-form only; the binary just sees 16 opaque bytes.
- **0xFD prefix dispatch** — the SIMD sub-opcode is LEB-encoded. Currently only `v128.const` (sub 12) is implemented; other sub-opcodes surface as `UnknownOpcode(0xFD)`.

Tests cover raw byte round-trips, parameter / local / block-result plumbing, zero-init, and the wat-form lane-annotation equivalence (`v128.const i8x16` and `v128.const i16x8` of the same byte payload produce identical V128 values).

## Multi-memory (Phase 8.D)

Modules may declare any number of linear memories. Each memory opcode threads a `memidx` through its immediate:

- **Load/store memarg** — Phase 8.D repurposes bit 6 of the alignment LEB as a "memidx-present" flag. When set, a memidx LEB follows; alignment is the LEB with that bit cleared. Single-memory modules emit the MVP shape (no flag, memidx = 0 implicit).
- **`memory.size` / `memory.grow` / `memory.fill`** — the byte that was a must-be-zero reserved slot becomes a memidx LEB.
- **`memory.copy`** — two memidx LEBs (dst, src), allowing memory-to-memory copies between distinct memories.
- **`memory.init`** — second immediate is a memidx LEB (was reserved).

`ModuleInstance.memories: Array[Memory]` exposes the full vector; `.memory` keeps backwards compat returning memory 0. `.exportedMemory(name)` resolves an exported memory by name.

## What isn't implemented yet

| Group | Sub-opcodes | Status |
|---|---|---|
| SIMD remainder | every `v128.*` opcode except `v128.const`, plus `i8x16.*`, `i16x8.*`, `i32x4.*`, `i64x2.*`, `f32x4.*`, `f64x2.*` | in progress (8.E, chunks B..I) |
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
| Table    | 4  | Funcref + externref tables |
| Memory   | 5  | Linear-memory definitions |
| Global   | 6  | Module-level globals (scalar + reftype) |
| Export   | 7  | Names exposed to the host |
| Start    | 8  | Function index run at instantiate time |
| Element  | 9  | Table initializers (funcidx + elemexpr forms, funcref + externref) |
| Code     | 10 | Function bodies |
| Data     | 11 | Linear-memory initializers (active + passive) |
| DataCount | 12 | u32 = number of data segments; required when a function uses `memory.init` or `data.drop` |

Custom sections are skipped harmlessly.
