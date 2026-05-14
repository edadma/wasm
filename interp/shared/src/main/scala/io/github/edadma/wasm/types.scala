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

/** An element segment. Phase 8.B extends the MVP active-only shape with
  * passive + declarative variants so `table.init` / `elem.drop` have
  * something to address. The funcref/funcidx encoding is shared by all
  * three forms (the elemexpr-bearing flags 4..7 are reference-types-
  * proposal territory and stay rejected by the parser).
  *
  *   Active     — flag 0 / flag 2 in the binary; copied into
  *                `tables(tableIdx)` at `offset` during instantiation
  *                (current behaviour). Still referenceable by elemidx
  *                from `table.init`, but post-instantiation the segment
  *                is treated as "dropped" — `table.init` with n>0 on
  *                an active segment traps OOB by design.
  *   Passive    — flag 1; bytes stay around as an elemidx-addressable
  *                table fragment. `table.init` copies; `elem.drop`
  *                marks it as effectively empty.
  *   Declarative — flag 3; the spec describes this as a "no-op at
  *                instantiation, no-op at run time" marker used to
  *                pre-declare funcrefs that `ref.func` would otherwise
  *                fail to resolve. Until Phase 8.C (reference types)
  *                lands `ref.func`, declarative segments are
  *                accepted-and-ignored at the type level. */
sealed trait ElementSegment:
  def funcIndices: Vector[Int]

object ElementSegment:
  /** Active: copy `funcIndices` into `tables(tableIdx)` at `offset` at
    * instantiation. The runtime then marks this segment "dropped" so
    * subsequent `table.init` with n > 0 traps. */
  final case class Active(tableIdx: Int, offset: Int, funcIndices: Vector[Int]) extends ElementSegment

  /** Passive: indices remain addressable by elemidx until `elem.drop`. */
  final case class Passive(funcIndices: Vector[Int]) extends ElementSegment

  /** Declarative: parsed for `ref.func` pre-declaration; runtime no-op. */
  final case class Declarative(funcIndices: Vector[Int]) extends ElementSegment

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

/** A data segment. Phase 8.B extends the MVP active-only shape with a
  * passive variant so `memory.init` / `data.drop` have something to
  * address. Active and passive both carry a `bytes` payload; passive
  * has no offset (it's set by `memory.init` at run time). */
sealed trait DataSegment:
  def bytes: Array[Byte]

object DataSegment:
  /** Active: copied into memory `memIdx` at `offset` during instantiation.
    * Post-instantiation the segment is "dropped" — subsequent
    * `memory.init` with n > 0 traps. */
  final case class Active(memIdx: Int, offset: Int, bytes: Array[Byte]) extends DataSegment

  /** Passive: bytes remain addressable by dataidx until `data.drop`. */
  final case class Passive(bytes: Array[Byte]) extends DataSegment

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
    elements: Vector[ElementSegment],// element segments — active ones populate `tables` at instantiation
    codes: Vector[FuncBody],
    data: Vector[DataSegment],
    startFunction: Option[Int],      // section 8 (Start) — funcidx to invoke at instantiation
    // Section 12 (DataCount). `Some(n)` if the binary declared one (required
    // by the spec for any module that uses `memory.init` / `data.drop`); the
    // validator gates those ops on `dataCount.isDefined` matching `data.length`.
    // `None` if the section was absent.
    dataCount: Option[Int] = None,
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
