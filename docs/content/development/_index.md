---
title: Development
summary: Sub-project breakdown, filesystem layout, running the test suite, the W3C testsuite runner, and regenerating .wasm fixtures from their .wat sources.
weight: 60
---

This section is for contributors and anyone working from a checkout of the repo rather than the published Maven Central artifacts.

- [Architecture](/development/architecture/) — the three published libraries (`interp`, `wasi`, plus the unpublished `cli`), what each one ships, and the source-tree layout.
- [Testing](/development/testing/) — the hand-rolled PASS/FAIL test runner, how to invoke the unit suites on each backend, how to wire the W3C testsuite manifests in, and how to regenerate the committed `.wasm` test fixtures from their `.wat` sources.
