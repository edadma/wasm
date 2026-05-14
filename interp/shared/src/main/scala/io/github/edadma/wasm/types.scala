package io.github.edadma.wasm

/** WebAssembly value types and the runtime values that inhabit them.
  *
  * `Value` and `ValueType` are sealed hierarchies covering the four MVP
  * scalar types — I32, I64, F32, F64 — plus the two reference kinds added
  * by the reference-types proposal (Phase 8.C): funcref and externref.
  * Vector types (SIMD v128) will extend the hierarchies further without
  * breaking the binary API.
  */

sealed trait Value
final case class I32(value: Int)    extends Value
final case class I64(value: Long)   extends Value
final case class F32(value: Float)  extends Value
final case class F64(value: Double) extends Value

/** A typed null reference. `refType` distinguishes a funcref-null from an
  * externref-null, since the spec's `ref.is_null` is polymorphic over both
  * but `table.set` is not (an externref-null can't go into a funcref table).
  */
final case class RefNull(refType: RefType) extends Value

/** A non-null funcref pointing at the module's `funcIdx`-th function
  * (imports first, then defined). Comes from `ref.func` and from active /
  * passive element segments. */
final case class RefFunc(funcIdx: Int) extends Value

/** A non-null externref carrying an opaque host object. Wasm code can
  * only move externrefs around (`table.{get,set}`, `local.{get,set}`,
  * `ref.is_null`) — it can't inspect or call them. The host hands them in
  * and pulls them back out through the public API. */
final case class RefExtern(value: AnyRef) extends Value

/** The two reference kinds in the reference-types proposal. The wire
  * encoding is `0x70` for funcref and `0x6F` for externref (both fit in
  * the `ValueType` LEB-byte slot). */
enum RefType:
  case FuncRef
  case ExternRef

object RefType:
  /** Reverse of the wire-byte encoding. Used by the parser when reading
    * table reftype, element-segment reftype, ref.null immediate, and
    * blocktype byte. Anything else is the caller's responsibility to
    * reject — this is a strict mapper. */
  def fromByte(b: Int): Option[RefType] = b match
    case 0x70 => Some(FuncRef)
    case 0x6f => Some(ExternRef)
    case _    => None

  def toByte(r: RefType): Int = r match
    case FuncRef   => 0x70
    case ExternRef => 0x6f

enum ValueType:
  case I32Type
  case I64Type
  case F32Type
  case F64Type
  case FuncRefType
  case ExternRefType

object ValueType:
  /** Convert a [[RefType]] into the matching `ValueType`. The validator's
    * abstract operand stack uses `ValueType` uniformly, so reftypes need
    * to be liftable into it. */
  def fromRef(r: RefType): ValueType = r match
    case RefType.FuncRef   => FuncRefType
    case RefType.ExternRef => ExternRefType

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

/** A module-defined table. Phase 8.C surfaces externref tables (`0x6F`)
  * alongside funcref (`0x70`); the [[RefType]] carries the distinction.
  * `min` is the initial slot count; any slot the element segments don't
  * cover starts as a typed null (`RefNull(refType)`).
  *
  * Imported tables are not represented yet (Phase 5 alongside imported
  * globals).
  */
final case class Table(refType: RefType, min: Int, max: Option[Int])

/** An element segment. Phase 8.B added passive + declarative variants
  * alongside the MVP active form so `table.init` / `elem.drop` had
  * something to address. Phase 8.C generalises the payload from
  * `Vector[Int]` (funcidxs only) to `Vector[Value]` carrying typed
  * reference values — either `RefFunc(idx)` for funcref entries or
  * `RefNull(refType)` for null entries (externref segments are also
  * representable). Each segment carries its element [[RefType]] so the
  * validator can enforce table-vs-segment compatibility on `table.init`.
  *
  *   Active      — flag 0 / 2 / 4 / 6: copied into `tables(tableIdx)` at
  *                 `offset` during instantiation. Post-init the segment
  *                 is treated as "dropped" — `table.init` with n > 0 on
  *                 an active segment traps OOB by design.
  *   Passive     — flag 1 / 5: refs stay addressable by elemidx until
  *                 `elem.drop`.
  *   Declarative — flag 3 / 7: pre-declares funcrefs for `ref.func`
  *                 resolution; runtime treats as dropped. Phase 8.C
  *                 enforces that any `ref.func funcidx` reference at
  *                 validation time names a declared funcidx (declared
  *                 via a declarative segment, an export, the start
  *                 function, or another element segment's payload).
  */
sealed trait ElementSegment:
  def refType: RefType
  def refs:    Vector[Value]

object ElementSegment:
  /** Active: copy `refs` into `tables(tableIdx)` at `offset` at
    * instantiation. The runtime then marks this segment "dropped" so
    * subsequent `table.init` with n > 0 traps. */
  final case class Active(tableIdx: Int, offset: Int, refType: RefType, refs: Vector[Value]) extends ElementSegment

  /** Passive: refs remain addressable by elemidx until `elem.drop`. */
  final case class Passive(refType: RefType, refs: Vector[Value]) extends ElementSegment

  /** Declarative: parsed for `ref.func` pre-declaration; runtime no-op. */
  final case class Declarative(refType: RefType, refs: Vector[Value]) extends ElementSegment

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
