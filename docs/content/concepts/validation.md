---
title: Validation
summary: Every module passes a separate validator before any code runs. Bad binaries fail at instantiate time with a precise error.
weight: 10
---

`Runtime.instantiate(bytes, …)` is two passes: a parser that turns the byte stream into a typed `Module` representation, and a validator that walks the resulting module section-by-section before any byte of guest code is interpreted. Either step can return `Left(InvalidModule(message))` — and when it does, the message is precise enough to point at the offending opcode by byte offset.

## What the validator checks

For every function body, the validator runs an abstract interpreter that tracks the operand stack as a list of value types (`i32` / `i64` / `f32` / `f64` / Polymorphic), plus a control-frame stack tracking `block` / `loop` / `if` scopes:

- Every opcode pops the right number of operands and the right value types from the abstract stack.
- Every opcode pushes the result types it claims to.
- Every `br` / `br_if` / `br_table` target index is in range, and the branch arguments (operand-stack top values) match the target's parameter types.
- `call` and `call_indirect` arguments match the callee's signature.
- `local.get` / `local.set` / `local.tee` indices are in range; `global.set` only targets mutable globals.
- Memory load/store accesses use a memory index that exists in section 5.
- Table accesses use a table index that exists in section 4, with the right element type.

Unreachable code after `br`, `return`, or `unreachable` is type-checked too — the spec requires it.

## What "fail at instantiate" looks like

Bad binaries surface as `Left(InvalidModule(message))` from `Runtime.instantiate`:

```text
function 7: byte offset 0x1a3: i32.add expected i32 i32 on stack, found i32 f64
function 7: byte offset 0x205: br target depth 3 out of range (max 2)
function 2: byte offset 0x40: call_indirect signature mismatch — table 0 element 5 has type (i32) -> (i32), expected () -> (i32)
```

The byte offset is from the start of the function body, not the whole module, so it lines up directly with what `wasm-objdump -d` shows.

## Binary sections recognised

| Section | Number | What it carries |
|---------|--------|-----------------|
| Type     | 1  | Function signatures (`(param i32 i32) (result i32)` etc.) |
| Import   | 2  | Functions, memories, globals, tables imported from the host |
| Function | 3  | Function-index → type-index mapping (signature for each body) |
| Table    | 4  | Funcref tables (used by `call_indirect`) |
| Memory   | 5  | Linear-memory definitions (initial + optional max pages) |
| Global   | 6  | Module-level globals (mutable and immutable) |
| Export   | 7  | Names exposed to the host |
| Start    | 8  | A function index to run at instantiate time, before any export call |
| Element  | 9  | Funcref table initializers |
| Code     | 10 | Function bodies |
| Data     | 11 | Linear-memory initializers |

Custom sections are skipped harmlessly. The `name` custom section is not yet used (functions appear as `function <N>` in error messages, not by their debug name).

## LEB128

Unsigned and signed LEB128 (up to 64 bits) are implemented from scratch — no `BigInteger` allocation, no platform-specific decoder. The format dominates wasm's wire encoding: every type index, function index, local index, memory immediate, and integer constant goes through it.

## What validation does *not* catch

By design, a validator only catches violations of wasm's static type system. It does not catch:

- **Trap-at-runtime errors** — divide-by-zero, integer overflow on signed `div`, out-of-bounds memory access, indirect-call signature mismatch, branch index out of range. These surface as [`InvalidModule(msg)` from a running function](/concepts/traps-and-errors/), not from `instantiate`.
- **Behavioural bugs in the guest program** — infinite loops, deadlocks, wrong answers. Those are for the guest to debug, not the host.
- **Resource-exhaustion at instantiate** — if a module claims a min-memory of 50,000 pages (≈ 3.2 GiB), the interpreter tries to allocate it. Validate the bytes against a size policy before handing them to `instantiate`.
