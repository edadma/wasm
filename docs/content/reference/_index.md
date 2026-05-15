---
title: Reference
summary: What's implemented, what isn't, and the error variants you can pattern-match on.
weight: 50
---

The Quickstart and Concepts pages cover the model. This section is for lookups — *does the interpreter implement X*, *what's the type of Y*, *what error fires on Z*.

## At a glance

**Implemented (0.1.1):**

- Every WebAssembly Core opcode (numeric, control flow, memory, tables, parametric, variables).
- Sign-extension proposal (`i{32,64}.extend{8,16,32}_s`).
- Non-trapping float-to-int (`*.trunc_sat_f*_*`).
- Bulk-memory (`memory.{init,copy,fill}`, `data.drop`, `table.{init,copy,fill}`, `elem.drop`).
- Reference types (`funcref`, `externref`, `ref.{null,is_null,func}`, `table.{get,set,grow,size,fill}`, typed `select t*`).
- Multi-memory (every memory opcode carries a memidx; modules may declare more than one memory).
- Full SIMD proposal — all ~236 ops under the `0xFD` prefix (`V128` value type plumbed end-to-end).
- Multi-value blocks + functions.
- Start section.
- Validation pass that runs before any code does.

**Not implemented:**

- Threads + atomics (`*.atomic.*`).
- Exception handling (`try` / `catch` / `throw` / `rethrow`).
- GC proposal (`struct.*`, `array.*`, `ref.cast`).
- Component model (out of scope — packaging proposal, not a wasm core feature).

See [Opcodes](/reference/opcodes/) for the by-byte detail.

- [Opcodes](/reference/opcodes/) — every WebAssembly opcode group, supported or planned.
- [Errors](/reference/errors/) — the `WasmError` ADT in one table.
