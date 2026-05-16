---
title: Spec compliance
summary: Running the W3C WebAssembly testsuite against the interpreter — what's wired in, what's pinned as known-failing, and how to extend the slice.
weight: 30
---

The interpreter has an integrated runner for the official [WebAssembly testsuite](https://github.com/WebAssembly/testsuite). It consumes the `.wast` files, dispatches each `assert_return` / `assert_trap` / `assert_invalid` / `assert_malformed` command against `Runtime.instantiate` + `inst.invoke`, and tracks per-file pass / fail / skip totals.

The slice that's wired in covers 33 manifests — numerics, conversions, control flow, memory addressing, function pointers — over **~9,500 assertions**.

## Running it

The runner is JVM-only (it needs filesystem access for the manifest tree):

```bash
sbt 'interpJVM/Test/runMain io.github.edadma.wasm.spec.SpecComplianceTests'
```

Output is one line per manifest plus a totals line. Each file is tagged `OK`, `KNOWN` (failures expected and tolerated — see below), or `FAIL` (failures that mean a regression):

```text
== W3C spec compliance ==
  OK    address                  259 pass,    0 fail,    1 skip
  KNOWN align                     76 pass,   43 fail,   46 skip
  OK    block                    208 pass,    0 fail,   15 skip
  ...
  OK    unwind                    50 pass,    0 fail,    0 skip

== Spec totals: 9285 passed, 209 failed, 285 skipped (of 9779) ==
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

Three manifests are pinned in `SpecComplianceTests.KnownFailures` with explicit reasons, so the overall sweep stays green while the gap stays visible:

| Manifest   | Why                                                                                                                          |
|------------|-------------------------------------------------------------------------------------------------------------------------------|
| `align`    | Validator doesn't enforce `align <= log2(natural-width)` on plain load / store. Atomic ops *do* get the check.                |
| `br_table` | Testsuite module 0 uses the typed function-references reftype `(ref null func)` (wire byte `0x63`); proposal not implemented. |
| `if`       | Four validator gaps where an `if` branch's stack height doesn't match the declared block result arity.                        |

Fixing any of these will trip an "UNEXPECTED PASSES" warning until the manifest is removed from `KnownFailures.names`.

## What the runner caught

Light triage during the initial run-up surfaced two real interpreter bugs:

1. **`i32.trunc_f64_s` over-rejected values strictly between `-2^31` and `-2^31 - 1`** (e.g. `-2147483648.9`, which truncates to `INT_MIN` and is in range). The range check was `v < -2^31` where it should have been `v <= -2^31 - 1`.
2. **`MemArg.offset` was an `Int`**, so a wasm u32 offset like `0xFFFFFFFF` was stored as Java `-1`. The Long sum `addr + offset` then sign-extended, turning a guaranteed-OOB load into a wrap-to-low-memory load. Widening the field to `Long` and masking on construction restores the trap.

Both ship with regression tests in `NumericTests` and `MemoryTests`.
