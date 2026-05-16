---
title: Spec compliance
summary: Running the W3C WebAssembly testsuite against the interpreter — what's wired in, what's pinned as known-failing, and how to extend the slice.
weight: 30
---

The interpreter has an integrated runner for the official [WebAssembly testsuite](https://github.com/WebAssembly/testsuite). It consumes the `.wast` files, dispatches each `assert_return` / `assert_trap` / `assert_invalid` / `assert_malformed` command against `Runtime.instantiate` + `inst.invoke`, and tracks per-file pass / fail / skip totals.

The slice that's wired in covers **142 manifests** — numerics, conversions, control flow, memory addressing, function pointers, the complete SIMD proposal, bulk memory + tables + element segments, the EH and tail-call proposals, plus binary-format and UTF-8 edge-case manifests — over **~53,000 assertions**.

## Running it

The runner is JVM-only (it needs filesystem access for the manifest tree):

```bash
sbt 'interpJVM/Test/runMain io.github.edadma.wasm.spec.SpecComplianceTests'
```

Output is one line per manifest plus a totals line. Each file is tagged `OK`, `KNOWN` (failures expected and tolerated — see below), or `FAIL` (failures that mean a regression):

```text
== W3C spec compliance ==
  OK    address                  259 pass,    0 fail,    1 skip
  OK    align                    119 pass,    0 fail,   46 skip
  OK    block                    208 pass,    0 fail,   15 skip
  ...
  KNOWN br_table                  24 pass,  162 fail,    0 skip
  ...
  OK    simd_i16x8_q15mulr_sat_s    30 pass,    0 fail,    0 skip
  ...
  OK    unwind                    50 pass,    0 fail,    0 skip

== Spec totals: 51084 passed, 830 failed, 1296 skipped (of 53210) ==
```

Exit code is non-zero iff at least one manifest is not `OK` or `KNOWN`.

## How the pipeline works

The testsuite is in `.wast` source form. `wast2json` (from [wabt](https://github.com/WebAssembly/wabt)) pre-processes each `.wast` into a JSON command manifest plus a directory of per-module `.wasm` binaries. The Scala runner consumes those — it never parses `.wast` text itself, so the WAT parsing burden lives entirely outside the project.

`scripts/build-spec-tests.sh` regenerates the manifest tree from a local checkout:

```bash
git clone https://github.com/WebAssembly/testsuite /tmp/wasm-testsuite
./scripts/build-spec-tests.sh /tmp/wasm-testsuite
```

The script names the curated slice explicitly (a `FILES=(...)` array). To add a manifest, append its name (without `.wast`), re-run the script, and run the spec suite to see what surfaces.

## Skipped commands

Two categories of command are skipped, not run:

- **`assert_malformed` / `assert_invalid` with `module_type: "text"`.** wast2json couldn't binary-encode the module (intentionally malformed text). Our interpreter only accepts binary input, so there's nothing to test. ~285 commands across the current slice fall here.
- **Anything outside the dispatch ADT.** `register` for cross-module imports, `assert_unlinkable`, `assert_uninstantiable`, etc. The current slice doesn't emit those, but the runner skips rather than crashes if a future addition does.

Skipped commands count toward the totals line but don't affect pass / fail status.

## Known failures

13 manifests are pinned in `SpecComplianceTests.KnownFailures` — each one needs non-trivial implementation work beyond the surgical bug-fix pattern. They're grouped by the feature gap they represent:

**Function-references / GC proposals** (not on the roadmap):

| Manifest          | Why                                                                  |
|-------------------|----------------------------------------------------------------------|
| `br_table`        | Module 0 uses `(ref null func)` short form (wire byte `0x63`).       |
| `table-sub`       | Same reftype short form.                                             |
| `local_init`      | `(ref func)` non-null short form (`0x64`).                           |
| `unreached-valid` | Function-references + typed reftype lookup.                          |

**Compact-imports proposal** (gates 9 manifests):

The wasm-3.0 testsuite ships modules using an experimental "compact-imports" wire format ([github.com/WebAssembly/compact-imports](https://github.com/WebAssembly/compact-imports)) that groups imports under a shared module name with a 3-byte magic header. Our parser still expects the wasm-2.0 `count import*` shape and rejects the compact form with `unknown import kind 0x7f`. The supporting features (kind `0x03` global imports, host-resolved `HostGlobal`, extended-const i32/i64 add/sub/mul, relaxed const-expr `global.get` over any earlier immutable global) all landed in 0.3.0; only the wire-format extension itself remains.

| Manifest      |
|---------------|
| `data`        |
| `elem`        |
| `exports`     |
| `global`      |
| `imports`     |
| `memory_grow` |
| `names`       |
| `table_copy`  |
| `table_grow`  |

**Cross-module `register`** (runner-side):

The wast2json command stream includes `register` commands that bind a module instance to an external name for subsequent imports; our runner doesn't implement that dispatch.

| Manifest  |
|-----------|
| `linking` |

Fixing any of these will trip an "UNEXPECTED PASSES" warning until the manifest is removed from `KnownFailures.names`.

## What the runner caught

Triage across the initial run-up and five coverage-expansion passes surfaced thirteen real interpreter bugs:

1. **`i32.trunc_f64_s` over-rejected values strictly between `-2^31` and `-2^31 - 1`** (e.g. `-2147483648.9`, which truncates to `INT_MIN` and is in range). The range check was `v < -2^31` where it should have been `v <= -2^31 - 1`.
2. **`MemArg.offset` was an `Int`**, so a wasm u32 offset like `0xFFFFFFFF` was stored as Java `-1`. The Long sum `addr + offset` then sign-extended, turning a guaranteed-OOB load into a wrap-to-low-memory load. Widening the field to `Long` and masking on construction restores the trap.
3. **`align` immediate validation was missing for plain load / store.** The atomic path enforced `align == log2(natural-width)` but `skipMemArg` (the plain path) read the field and dropped it. A module with `i32.load8_s align=2` (natural width 1, log2 = 0) instantiated successfully. Now enforced — every load/store opcode and every SIMD load/store carries an `accessWidth` to `skipMemArg`, which rejects `align > log2(width)`.
4. **`if` without `else` accepted mismatched params/results.** The form `if bt e* end` (no else) has an implicit empty else-branch with type `[t1*] → [t1*]`, so it only validates iff `startTypes == endTypes`. We were silently accepting cases like `(if (result i32) (then (i32.const 0)))` where the implicit else can't satisfy the result. Now rejected at instantiation.
5. **`v128.const` (SIMD prefix 0xFD + sub 0x0C) wasn't accepted in const expressions** — only scalar/ref const forms were recognised. `(global v128 (v128.const ...))` and v128 data-segment offsets failed to parse. Const-expr reader now decodes the 16 raw bytes.
6. **Untyped `select` (0x1B) rejected v128 operands.** The SIMD proposal treats v128 as a numtype for the purpose of `select`; only the typed `select t*` form (0x1C) is reserved for reftypes. Numeric predicate now includes v128.
7. **`i8x16.popcnt` (SIMD sub-opcode 0x62) was completely unimplemented** — we had abs (0x60) and neg (0x61) but jumped to 0x63 (all_true).
8. **`i16x8.q15mulr_sat_s` (SIMD sub-opcode 0x82) was completely unimplemented** — only the relaxed-SIMD variant (0x111) was present. Spec semantics: `(a*b + 0x4000) >> 15`, saturated to i16 range.
9. **`try_table` catch labels counted with the try_table on the label stack.** Per the EH proposal, catch label indices count from the OUTER scope — the try_table is not yet on the label stack from the catch clause's perspective. Both the validator (pushed the try_table frame before validating catches) and the runtime (didn't pop the try_table label before `branchTo`) had matching off-by-one errors. Fix moves the `pushCtrl` after the catch-vector validation and adds a label-pop in the runtime's throw-dispatch path.
10. **Export-section validation was missing entirely.** Two spec rules went unenforced: (a) export names must be unique within a module (duplicate `(export "foo" ...)` declarations were silently accepted, with the second shadowing the first); (b) each export's index must be in range for its kind (a `funcidx` past the imports+defs count, a `globalidx` past the global section, etc. all instantiated). Added a single pass over `module.exports` at the top of `Validator.validate` that checks both invariants.
11. **Name-field UTF-8 validation was missing.** Every `name` byte sequence in the wire format (import module/field names, export names, custom-section ids, name-section subsections) must be valid UTF-8 per the spec. We were decoding via `new String(bytes, "UTF-8")` which silently maps malformed bytes to U+FFFD instead of rejecting. Added an RFC 3629 strict walker called from `Cursor.readName` that rejects stray continuations, overlong forms (`0xC0`/`0xC1` and the overlong 3/4-byte variants), surrogate codepoints (U+D800..U+DFFF), values past U+10FFFF, lead bytes `0xF5`..`0xFF`, and truncated multi-byte sequences. Also let the diagnostic propagate from `parseCustomSection` so the section name itself is enforced (was silently caught).
12. **Binary-format strictness gaps** — seven small rules our parser was lax about, surfaced together by the `binary`, `binary-leb128`, and `custom` manifests:
    - **LEB128 range checks.** `readU32` / `readS32` / `readS64` accepted oversize encodings: a 5-byte ULEB whose final byte had data bits past position 32 silently overflowed; signed forms were equally permissive on the sign-extension bits. Final byte now enforces the u32 / s32 / s64 width, with the spec's distinct diagnostics for "integer too large" (value outside type range) and "integer representation too long" (more bytes than the type's max).
    - **Section ID range.** IDs outside 0..13 silently fell through `case _ => ()`. Now rejected as "malformed section id".
    - **Section order + uniqueness.** Non-custom sections must appear at most once and in canonical *logical* order. The IDs aren't monotonically ascending — Tag (13) is logically between Memory (5) and Global (6), and DataCount (12) is between Element (9) and Code (10). A small id → position table enforces both rules.
    - **Section size mismatch.** `c.pos = secEnd` after each section silently absorbed under- or over-consumed bytes; now each non-custom section must land exactly at `secEnd`.
    - **Custom section name overruns size.** A custom section with declared size 0 has no bytes for even the name-length prefix. We were reading past the section into the next one. `parseCustomSection` now verifies the name read didn't overshoot `secEnd`.
    - **Too many locals.** A function's local-count groups summed past `2^32 - 1` were accepted; even a single huge group (e.g. `count = 0x40000000`) OOM-ed the allocation loop before the sum check could fire. Now the code reads all (count, type) groups first, sums in `Long` arithmetic with overflow check, *then* expands.
13. **Imported globals + extended-const + relaxed const-expr** were not surfaced. The parser silently skipped import kind `0x03`, every const-expr accepted only a single literal opcode, and `global.get` in a const-expr was rejected outright. Three spec features that travel together now land as one drop: (a) `GlobalImport` joins `FuncImport` / `TagImport` in the module model, surfaces at instantiation via a new `HostGlobal` resolved against `HostModule.globals`, and occupies the leading slots of the unified globalidx space; (b) the const-expr reader is now a small stack-machine parser that flattens `iN.add` / `iN.sub` / `iN.mul` (extended-const proposal) into an expression tree the validator and runtime evaluate recursively; (c) the validator allows `global.get N` over any earlier-defined immutable global (wasm-3.0 relaxation), with forward-reference and mutability rejection. Active data / element segment offsets accept the same const-expr forms. The wasm-3.0 testsuite's "compact-imports" wire format extension is a separate proposal and remains pinned.

All thirteen ship with regression tests in `NumericTests`, `MemoryTests`, `MultiValueAndStartTests`, `SimdIntArithTests`, `SimdConstTests`, `TryTableTests`, and `ParserAndRuntimeTests`.
