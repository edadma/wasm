package io.github.edadma.wasm

/** WebAssembly value types and the runtime values that inhabit them.
  *
  * `Value` and `ValueType` are sealed hierarchies covering all four MVP
  * scalar types: I32, I64, F32, F64. Future reference / vector types extend
  * the hierarchies without breaking the binary API.
  */

sealed trait Value
final case class I32(value: Int)    extends Value
final case class I64(value: Long)   extends Value
final case class F32(value: Float)  extends Value
final case class F64(value: Double) extends Value

enum ValueType:
  case I32Type
  case I64Type
  case F32Type
  case F64Type

/** A function signature — vector of param types in, vector of result types out.
  * MVP allows at most one result type. */
final case class FuncType(params: Vector[ValueType], results: Vector[ValueType])

/** An imported function. MVP ignores table/memory/global imports during parse;
  * if the module needed them it will fail at instantiation or use. */
final case class FuncImport(module: String, name: String, typeIdx: Int)

sealed trait Export { def name: String }
final case class FuncExport(name: String, funcIdx: Int)     extends Export
final case class GlobalExport(name: String, globalIdx: Int) extends Export
final case class TableExport(name: String, tableIdx: Int)   extends Export
// TODO: MemoryExport — not surfaced yet

final case class MemoryLimits(min: Int, max: Option[Int])

/** A module-defined table. MVP allows funcref (`0x70`) only; the `refType`
  * byte is stored verbatim so a future externref pass can recognise the
  * historical 0x6f without re-parsing. `min` is the initial slot count;
  * any slot the element segments don't cover starts as a null funcref
  * (encoded at the runtime as the int `-1`).
  *
  * Imported tables are not represented yet (Phase 5 alongside imported
  * globals).
  */
final case class Table(refType: Int, min: Int, max: Option[Int])

/** An active element segment: at instantiation, copy `funcIndices` into
  * `tables(tableIdx)` starting at `offset`. MVP only models the active form
  * (flag 0 and flag 2 in the binary). Passive / declarative segments are
  * deferred. */
final case class ElementSegment(tableIdx: Int, offset: Int, funcIndices: Vector[Int])

/** A module-defined global. The init expression is evaluated at parse time
  * for the MVP-style `*.const` form and stored directly here as `initialValue`;
  * `Runtime.instantiate` copies that into the live globals array. `mutable` is
  * the section-6 mutability byte (0x00 = const, 0x01 = var) — `global.set` on
  * an immutable global traps at run time (and once Phase 6 ships, at
  * validation time).
  *
  * Imported globals are not represented yet (Phase 5 — keeps the surface
  * small while Phase 2 lands).
  */
final case class Global(valueType: ValueType, mutable: Boolean, initialValue: Value)

/** Active data segment: `bytes` are copied into memory 0 at `offset` during instantiation. */
final case class DataSegment(offset: Int, bytes: Array[Byte])

/** One entry in the Code section.
  *
  * @param locals One entry per local *beyond the parameters* (the binary stores
  *               run-length pairs; we expand them up-front so indexing is direct).
  * @param body   Raw instruction bytes, ending in 0x0B (`end`).
  */
final case class FuncBody(locals: Vector[ValueType], body: Array[Byte])

/** A fully-parsed but not-yet-instantiated module.
  *
  * `startFunction` is the funcidx that section 8 (Start) requested be
  * invoked automatically at instantiation, or `None` if the module
  * doesn't declare one. The spec constrains the target to have
  * signature `() -> ()`; that check happens at instantiation rather than
  * parse time because parsing doesn't (yet) cross-validate funcidx
  * against the resolved function table.
  */
final case class WasmModule(
    types: Vector[FuncType],
    imports: Vector[FuncImport],
    functions: Vector[Int],          // type indices, one per defined function (matches `codes` 1:1)
    tables: Vector[Table],           // module-defined tables (imports not surfaced yet)
    memories: Vector[MemoryLimits],
    globals: Vector[Global],         // module-defined globals (imports not surfaced yet)
    exports: Vector[Export],
    elements: Vector[ElementSegment],// active element segments — applied to `tables` at instantiation
    codes: Vector[FuncBody],
    data: Vector[DataSegment],
    startFunction: Option[Int],      // section 8 (Start) — funcidx to invoke at instantiation
)

/** All failure modes surfaced by the public API.
  *
  * The spec called for case classes; empty-arg case objects are the idiomatic
  * Scala equivalent for the singletons and they pattern-match the same way.
  *
  * `InvalidModule` is the catch-all for structural problems in the binary
  * (truncated LEB128, unknown section payload, etc.) that don't fit one of the
  * specific variants. The other seven names mirror the spec exactly.
  */
sealed trait WasmError

object WasmError:
  case object InvalidMagic                                  extends WasmError
  case object TypeMismatch                                  extends WasmError
  case object UnreachableExecuted                           extends WasmError
  case object MemoryOutOfBounds                             extends WasmError
  final case class UnknownOpcode(byte: Int)                 extends WasmError
  final case class UnknownImport(module: String, name: String) extends WasmError
  final case class ExportNotFound(name: String)             extends WasmError
  final case class InvalidModule(message: String)           extends WasmError
