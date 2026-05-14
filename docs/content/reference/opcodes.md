---
title: Opcodes
summary: Every WebAssembly opcode group the interpreter handles, plus what's coming in Phase 8.
weight: 10
---

The interpreter implements the WebAssembly Core MVP plus the sign-extension proposal, the full bulk-memory proposal, non-trapping float-to-int (`trunc_sat_*`), the reference-types proposal (funcref, externref, `ref.null` / `ref.is_null` / `ref.func`, `table.get` / `table.set` / `table.size` / `table.grow` / `table.fill`, typed `select t*`), the multi-memory proposal (every memory opcode now carries a memidx; modules may declare more than one linear memory, with a parallel `HostFuncMulti` surface for host functions that need to reach beyond memidx 0), and the **foundations + memory ops of the SIMD proposal** (`V128` value type plumbed end-to-end; `v128.const`; `v128.load` / `v128.store` with all twelve width-variant loads; lane-aware ops landing chunk-by-chunk through 8.E). That's enough to run real `wasm32-wasip1` binaries produced by rustc end-to-end, and to host the full sysl standard-library test suite end-to-end as sysl's `wasm32-WASI` backend.

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

The WebAssembly SIMD proposal adds ~236 opcodes under the `0xFD` prefix and a new `V128` value type (16 raw bytes, lane interpretation chosen per-opcode). Landing the proposal is a multi-chunk project; **Chunks A, B, C, D, E, F, and G.1 are shipped.** The remainder (chunk G.2 comparison ops, H narrow/widen + conversions, I dot/lane mem ops) is still to come.

### Foundations (Chunk A — done)

- **`V128` value type** (wire byte `0x7B`). First-class in function params, results, locals, globals, and blocktypes. Locals zero-init to 16 zero bytes.
- **`v128.const`** (`0xFD 0x0C` + 16 raw little-endian bytes). The wat-side annotations (`i32x4 1 2 3 4`, `i16x8 ...`, etc.) are text-form only; the binary just sees 16 opaque bytes.
- **0xFD prefix dispatch** — the SIMD sub-opcode is LEB-encoded.

Tests cover raw byte round-trips, parameter / local / block-result plumbing, zero-init, and the wat-form lane-annotation equivalence (`v128.const i8x16` and `v128.const i16x8` of the same byte payload produce identical V128 values).

### Loads + stores (Chunk B — done)

Every load is `[i32 addr] → [v128]`; the store is `[i32 addr, v128 value] → []`. All ops carry a Phase-8.D-shaped memarg (the alignment LEB's bit 6 flags an optional memidx LEB; offset follows).

| Opcode | Sub | What it does |
|---|---|---|
| `v128.load` | `0x00` | Full 16-byte little-endian load. |
| `v128.load8x8_s` / `_u` | `0x01` / `0x02` | Read 8 source bytes, widen each (sign / zero) into 8 i16 lanes. |
| `v128.load16x4_s` / `_u` | `0x03` / `0x04` | Read 4 source i16s, widen each into 4 i32 lanes. |
| `v128.load32x2_s` / `_u` | `0x05` / `0x06` | Read 2 source i32s, widen each into 2 i64 lanes. |
| `v128.load8_splat` | `0x07` | Read 1 byte, broadcast across all 16 lanes. |
| `v128.load16_splat` | `0x08` | Read 2 bytes, broadcast across all 8 i16 lanes. |
| `v128.load32_splat` | `0x09` | Read 4 bytes, broadcast across all 4 i32 lanes. |
| `v128.load64_splat` | `0x0A` | Read 8 bytes, broadcast across all 2 i64 lanes. |
| `v128.store` | `0x0B` | Full 16-byte little-endian store. |
| `v128.load32_zero` | `0x5C` | Read 4 bytes into lane 0; zero the remaining 12. |
| `v128.load64_zero` | `0x5D` | Read 8 bytes into lane 0; zero the remaining 8. |

Out-of-bounds (addr + offset + width past memory end) traps with `MemoryOutOfBounds`, same shape as the scalar memory ops.

### Lane access (Chunk C — done)

Every "build / inspect / rearrange a v128 lane-by-lane" surface lives here. Lane shapes — `i8x16`, `i16x8`, `i32x4`, `i64x2`, `f32x4`, `f64x2` — pick the lane width (1/2/4/8 bytes) and the count (16/8/4/2 lanes). Lane immediates are validated `< lane_count` at compile time.

| Opcode | Sub | What it does |
|---|---|---|
| `i8x16.shuffle` | `0x0D` | 16-byte laneidx immediate (each `< 32`); each result lane is `a[c]` if `c<16` else `b[c-16]`. |
| `i8x16.swizzle` | `0x0E` | Dynamic shuffle. `s` (top) is the index vector, `v` (below) is the source. Result lane `i` = `v[s[i]]` if `s[i] < 16` else `0`. |
| `i8x16.splat` | `0x0F` | Broadcast the low 8 bits of an i32 to 16 lanes. |
| `i16x8.splat` | `0x10` | Broadcast the low 16 bits LE to 8 lanes. |
| `i32x4.splat` | `0x11` | Broadcast 4 LE bytes to 4 lanes. |
| `i64x2.splat` | `0x12` | Broadcast 8 LE bytes to 2 lanes. |
| `f32x4.splat` | `0x13` | Broadcast the IEEE-754 bit pattern of an f32 to 4 lanes. |
| `f64x2.splat` | `0x14` | Broadcast the IEEE-754 bit pattern of an f64 to 2 lanes. |
| `i8x16.extract_lane_s` / `_u` | `0x15` / `0x16` | Read 1 byte at lane (signed / zero extended to i32). |
| `i8x16.replace_lane` | `0x17` | Write the low byte of an i32 at the lane. |
| `i16x8.extract_lane_s` / `_u` | `0x18` / `0x19` | Read 2 LE bytes at lane (signed / zero extended to i32). |
| `i16x8.replace_lane` | `0x1A` | Write the low 16 bits LE at the lane. |
| `i32x4.extract_lane` | `0x1B` | Read 4 LE bytes at lane → i32. |
| `i32x4.replace_lane` | `0x1C` | Write 4 LE bytes at lane. |
| `i64x2.extract_lane` | `0x1D` | Read 8 LE bytes at lane → i64. |
| `i64x2.replace_lane` | `0x1E` | Write 8 LE bytes at lane. |
| `f32x4.extract_lane` | `0x1F` | Read 4 LE bytes at lane → f32 (raw IEEE-754 bits, no NaN canonicalisation). |
| `f32x4.replace_lane` | `0x20` | Write 4 LE bytes at lane. |
| `f64x2.extract_lane` | `0x21` | Read 8 LE bytes at lane → f64. |
| `f64x2.replace_lane` | `0x22` | Write 8 LE bytes at lane. |

`extract_lane` / `replace_lane` carry a 1-byte lane immediate after the sub-opcode; `i8x16.shuffle` carries a 16-byte laneidx vector. `splat` and `swizzle` have no immediate beyond the sub-opcode.

### Integer arithmetic (Chunk D — done)

Lane-wise integer arithmetic across every integer shape. Plain `add` / `sub` / `mul` wrap modulo 2^lane_width; `_sat_s` / `_sat_u` clamp at the signed / unsigned bounds; `avgr_u` is the rounding unsigned average `(a + b + 1) / 2`. No `i8x16.mul` in the spec, no saturating variants past `i16x8`, no `avgr_u` past `i16x8`. All ops have no immediate.

| Opcode | Sub | What it does |
|---|---|---|
| `i8x16.abs` | `0x60` | `abs(MinValue)` wraps to `MinValue` (overflow mod 2^8). |
| `i8x16.neg` | `0x61` | `neg(MinValue)` wraps likewise. |
| `i8x16.add` | `0x6E` | Wraps mod 256 per lane. |
| `i8x16.add_sat_s` / `_u` | `0x6F` / `0x70` | Clamps to `[-128, 127]` / `[0, 255]`. |
| `i8x16.sub` | `0x71` | Wraps mod 256. |
| `i8x16.sub_sat_s` / `_u` | `0x72` / `0x73` | Clamps to the signed / unsigned lane bounds. |
| `i8x16.avgr_u` | `0x7B` | `(a + b + 1) / 2` per lane (rounds up). |
| `i16x8.abs` / `neg` | `0x80` / `0x81` | Same shape as `i8x16`. |
| `i16x8.add` / `add_sat_s` / `add_sat_u` | `0x8E` / `0x8F` / `0x90` | Wrap / clamp to `[-32768, 32767]` / `[0, 65535]`. |
| `i16x8.sub` / `sub_sat_s` / `sub_sat_u` | `0x91` / `0x92` / `0x93` | Wrap / clamp. |
| `i16x8.mul` | `0x95` | Low 16 bits of the full-width product. |
| `i16x8.avgr_u` | `0x9B` | `(a + b + 1) / 2` per lane unsigned. |
| `i32x4.abs` / `neg` | `0xA0` / `0xA1` | `abs(Int.MinValue)` wraps. |
| `i32x4.add` / `sub` / `mul` | `0xAE` / `0xB1` / `0xB5` | Wrap mod 2^32; `mul` is the low 32 bits. |
| `i64x2.abs` / `neg` | `0xC0` / `0xC1` | `abs(Long.MinValue)` wraps. |
| `i64x2.add` / `sub` / `mul` | `0xCE` / `0xD1` / `0xD5` | Wrap mod 2^64. |

Sub-opcodes ≥ `0x80` encode as 2-byte LEBs in the binary; `wat2wasm` emits the right shape, and the dispatch's LEB decoder handles either width transparently.

### Shifts + min/max (Chunk E — done)

Lane-wise shifts (`shl`, `shr_s`, `shr_u`) on all four integer shapes, plus per-lane signed and unsigned min/max on `i8x16`, `i16x8`, `i32x4` (the spec excludes `i64x2.min/max`). Shifts pop the i32 count from the operand stack — it's *not* an immediate — and the spec takes `count mod lane_width`, so e.g. `i8x16.shl(_, 8)` is the identity.

| Opcode | Sub | What it does |
|---|---|---|
| `i8x16.shl` | `0x6B` | Shift left; count mod 8. |
| `i8x16.shr_s` / `_u` | `0x6C` / `0x6D` | Arithmetic / logical right shift; count mod 8. |
| `i8x16.min_s` / `_u` | `0x76` / `0x77` | Per-lane signed / unsigned minimum. |
| `i8x16.max_s` / `_u` | `0x78` / `0x79` | Per-lane signed / unsigned maximum. |
| `i16x8.shl` | `0x8B` | Shift left; count mod 16. |
| `i16x8.shr_s` / `_u` | `0x8C` / `0x8D` | Arithmetic / logical right shift; count mod 16. |
| `i16x8.min_s` / `_u` | `0x96` / `0x97` | Per-lane signed / unsigned minimum. |
| `i16x8.max_s` / `_u` | `0x98` / `0x99` | Per-lane signed / unsigned maximum. |
| `i32x4.shl` | `0xAB` | Shift left; count mod 32. |
| `i32x4.shr_s` / `_u` | `0xAC` / `0xAD` | Arithmetic / logical right shift; count mod 32. |
| `i32x4.min_s` / `_u` | `0xB6` / `0xB7` | Per-lane signed / unsigned minimum. |
| `i32x4.max_s` / `_u` | `0xB8` / `0xB9` | Per-lane signed / unsigned maximum. |
| `i64x2.shl` | `0xCB` | Shift left; count mod 64. |
| `i64x2.shr_s` / `_u` | `0xCC` / `0xCD` | Arithmetic / logical right shift; count mod 64. |

The signed vs unsigned distinction matters at the lane width: byte `0xFF` is `-1` signed but `255` unsigned, so `i8x16.shr_s` of it stays `-1` while `i8x16.shr_u` of it becomes `0x7F`; `i8x16.min_s` picks `-1` as the minimum but `i8x16.min_u` picks `0`.

### Float arithmetic (Chunk F — done)

Lane-wise IEEE-754 arithmetic across `f32x4` and `f64x2`. Rounding (`ceil`, `floor`, `trunc`, `nearest`) and `abs` / `neg` / `sqrt` are unary; `add` / `sub` / `mul` / `div` and `min` / `max` / `pmin` / `pmax` are binary. All ops have no immediate.

`abs` / `neg` are bit-level (clear / flip the sign bit) and preserve NaN payloads exactly — useful for round-tripping signaling NaNs. Arithmetic ops produce *an* NaN when any operand is NaN; the bit pattern follows the same implementation-defined rule as scalar `f32.add` / `f64.add` (in practice the JVM's canonical `0x7FC00000` / `0x7FF8000000000000`).

`min` and `max` use IEEE-754 semantics: NaN-involving inputs produce NaN, and `-0` orders below `+0`. `pmin(a,b)` and `pmax(a,b)` follow the spec's compare-then-pick formula — `pmin = if b<a then b else a`, `pmax = if a<b then b else a` — which means a NaN-involving compare always returns `false`, so `a` is picked. That gives `pmin` / `pmax` a *different* NaN behaviour from `min` / `max`: a non-NaN `a` paired with a NaN `b` yields `a` (no NaN propagation), but a NaN `a` always propagates.

| Opcode | Sub | What it does |
|---|---|---|
| `f32x4.ceil` | `0x67` | Round each lane toward +Inf. |
| `f32x4.floor` | `0x68` | Round each lane toward -Inf. |
| `f32x4.trunc` | `0x69` | Round each lane toward zero (preserves signed zero). |
| `f32x4.nearest` | `0x6A` | Round half to even per lane. |
| `f64x2.ceil` / `floor` | `0x74` / `0x75` | Same shape as `f32x4`, double precision. |
| `f64x2.trunc` | `0x7A` | |
| `f64x2.nearest` | `0x94` | |
| `f32x4.abs` / `neg` | `0xE0` / `0xE1` | Bit-twiddle the sign bit; NaN payloads preserved. |
| `f32x4.sqrt` | `0xE3` | `sqrt(-x>0) = NaN`; `sqrt(-0) = -0`. |
| `f32x4.add` / `sub` / `mul` / `div` | `0xE4` / `0xE5` / `0xE6` / `0xE7` | IEEE-754 per lane. `0/0 = NaN`, `1/0 = +Inf`. |
| `f32x4.min` / `max` | `0xE8` / `0xE9` | NaN → NaN; `min(-0,+0) = -0`. |
| `f32x4.pmin` / `pmax` | `0xEA` / `0xEB` | `if b<a then b else a` / `if a<b then b else a`; NaN-compare picks `a`. |
| `f64x2.abs` / `neg` | `0xEC` / `0xED` | Bit-level sign manipulation. |
| `f64x2.sqrt` | `0xEF` | |
| `f64x2.add` / `sub` / `mul` / `div` | `0xF0` / `0xF1` / `0xF2` / `0xF3` | |
| `f64x2.min` / `max` | `0xF4` / `0xF5` | |
| `f64x2.pmin` / `pmax` | `0xF6` / `0xF7` | |

### Bitwise + reductions (Chunk G.1 — done)

Six bitwise ops that ignore lane shape (the v128 is just 16 raw bytes), plus nine v128 → i32 reductions. `v128.bitselect` is the only SIMD ternary op — it takes three v128 operands `(a, b, c)` and returns `(a AND c) OR (b AND NOT c)`, where `c` is the selector mask. All 15 ops have no immediate past the sub-opcode.

`any_true` is shape-agnostic — any byte non-zero returns `1`, otherwise `0`. `*.all_true` is lane-shape-aware: a v128 whose bytes are `[0, 1, 0, 1, 0, 1, ...]` is `i8x16.all_true = 0` (every other byte is zero) but `i16x8.all_true = 1` (every 16-bit lane is non-zero). `*.bitmask` packs the MSB of each lane into the i32 result at the lane-indexed bit position (lane 0 → bit 0).

| Opcode | Sub | What it does |
|---|---|---|
| `v128.not` | `0x4D` | Bitwise complement of all 16 bytes. |
| `v128.and` | `0x4E` | Bitwise AND. |
| `v128.andnot` | `0x4F` | `a AND (NOT b)` — note the asymmetry. |
| `v128.or` | `0x50` | Bitwise OR. |
| `v128.xor` | `0x51` | Bitwise XOR. |
| `v128.bitselect` | `0x52` | `(a AND c) OR (b AND NOT c)`; ternary. |
| `v128.any_true` | `0x53` | `1` if any bit is set, else `0`. |
| `i8x16.all_true` | `0x63` | `1` iff every byte is non-zero. |
| `i16x8.all_true` | `0x83` | `1` iff every 16-bit lane is non-zero. |
| `i32x4.all_true` | `0xA3` | `1` iff every i32 lane is non-zero. |
| `i64x2.all_true` | `0xC3` | `1` iff both i64 lanes are non-zero. |
| `i8x16.bitmask` | `0x64` | Top bit of each byte → 16-bit mask in i32. |
| `i16x8.bitmask` | `0x84` | Top bit of each i16 lane → 8-bit mask. |
| `i32x4.bitmask` | `0xA4` | Top bit of each i32 lane → 4-bit mask. |
| `i64x2.bitmask` | `0xC4` | Top bit of each i64 lane → 2-bit mask. |

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
| SIMD remainder | comparisons (G.2), narrow/widen + conversions (H), dot product + lane mem ops (I) | in progress (8.E, chunks G.2..I) |
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
