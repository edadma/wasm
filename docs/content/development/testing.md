---
title: Testing
summary: Unit suites on every backend, the W3C testsuite runner, and how to refresh `.wasm` test fixtures from their `.wat` sources.
weight: 20
---

## Unit suites

The interpreter has no test framework dependency — tests are `@main`-style objects with a hand-rolled PASS/FAIL runner. The same code runs on all three backends:

```bash
sbt 'interpJVM/Test/run'    # 653 interpreter tests
sbt 'interpJS/Test/run'
sbt 'interpNative/Test/run'

sbt 'wasiJVM/Test/run'      # 209 WASI tests on JVM/Native (193 on JS,
sbt 'wasiJS/Test/run'       #                   16 socket tests skipped)
sbt 'wasiNative/Test/run'

sbt 'cliJVM/Test/run'       # 19 CLI tests (JVM-only)
```

Total: **881 tests** on JVM (653 interp + 209 wasi + 19 cli) — all three backends green for `interp` and `wasi`. Three of those are end-to-end integration tests against real rustc-built `wasm32-wasip1` binaries.

Each `Test/run` target prints one `OK <name>` or `FAIL <name> — <reason>` line per assertion plus a final summary; the process exits non-zero if any assertion failed. Add new tests by appending `test("name") { ... }` calls inside the appropriate category file under `interp/shared/src/test/scala/io/github/edadma/wasm/`.

## W3C testsuite

The official [WebAssembly testsuite](https://github.com/WebAssembly/testsuite) runs through an integrated runner — **~53,000 assertions** across **142 manifests** covering numerics, control flow, memory addressing, function pointers, the complete SIMD proposal, bulk memory + tables + element segments, EH and tail-call proposals, plus binary-format and UTF-8 edge cases.

```bash
./scripts/build-spec-tests.sh /path/to/wasm-testsuite     # one-time, regenerates JSON fixtures
sbt 'interpJVM/Test/runMain io.github.edadma.wasm.spec.SpecComplianceTests'
```

The runner consumes `wast2json` JSON command manifests + per-module `.wasm` binaries; it never parses `.wast` text. The script's curated slice is the `FILES=(...)` array — append a manifest name and re-run to expand coverage.

See [Reference → Spec compliance](/reference/spec-compliance/) for current pass / fail / known-failure totals and the manifests pinned in `KnownFailures`.

## Regenerating fixtures

The `.wasm` test fixtures are produced from hand-written `.wat` files with `wat2wasm` (from [WABT](https://github.com/WebAssembly/wabt)). The committed `.wasm` binaries let anyone run the tests without WABT installed; `Fixtures.scala` is regenerated automatically on every `sbt compile`.

After editing any `.wat`:

```bash
cd interp/shared/src/test/resources/fixtures
for f in *.wat; do wat2wasm "$f" -o "${f%.wat}.wasm"; done

cd wasi/shared/src/test/resources/fixtures
for f in *.wat; do wat2wasm "$f" -o "${f%.wat}.wasm"; done
```

The three rustc-built fixtures are committed with their `.rs` source alongside as documentation; they're rebuilt by hand when their semantics change (Cargo + the `wasm32-wasip1` target).
