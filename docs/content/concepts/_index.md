---
title: Concepts
summary: The validator, the host import surface, the trap model — the model you need to use the library beyond copy-paste.
weight: 20
---

The Quickstart shows the three common shapes of caller code. This section is about the model behind those shapes: what `Runtime.instantiate` actually does before it returns, how host imports get wired up, and what kinds of failures cross the API boundary.

- [Validation](/concepts/validation/) — what runs before any wasm code does.
- [Host imports](/concepts/host-imports/) — `HostModule` and `HostFunc` from the inside.
- [Traps and errors](/concepts/traps-and-errors/) — the `WasmError` model and what causes each variant.
