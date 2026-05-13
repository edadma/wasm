package io.github.edadma.wasm

/** WebAssembly value types and the runtime values that inhabit them.
  *
  * `Value` and `ValueType` stay sealed hierarchies so future variants
  * (`F32`, `F64`) can be added without breaking the binary API.
  */

sealed trait Value
final case class I32(value: Int)   extends Value
final case class I64(value: Long)  extends Value
final case class F32(value: Float) extends Value
// TODO: F64(value: Double)

enum ValueType:
  case I32Type
  case I64Type
  case F32Type
  // TODO: F64Type

/** A function signature — vector of param types in, vector of result types out.
  * MVP allows at most one result type. */
final case class FuncType(params: Vector[ValueType], results: Vector[ValueType])

/** An imported function. MVP ignores table/memory/global imports during parse;
  * if the module needed them it will fail at instantiation or use. */
final case class FuncImport(module: String, name: String, typeIdx: Int)

sealed trait Export { def name: String }
final case class FuncExport(name: String, funcIdx: Int) extends Export
// TODO: MemoryExport, TableExport, GlobalExport — not surfaced in MVP

final case class MemoryLimits(min: Int, max: Option[Int])

/** Active data segment: `bytes` are copied into memory 0 at `offset` during instantiation. */
final case class DataSegment(offset: Int, bytes: Array[Byte])

/** One entry in the Code section.
  *
  * @param locals One entry per local *beyond the parameters* (the binary stores
  *               run-length pairs; we expand them up-front so indexing is direct).
  * @param body   Raw instruction bytes, ending in 0x0B (`end`).
  */
final case class FuncBody(locals: Vector[ValueType], body: Array[Byte])

/** A fully-parsed but not-yet-instantiated module. */
final case class WasmModule(
    types: Vector[FuncType],
    imports: Vector[FuncImport],
    functions: Vector[Int],          // type indices, one per defined function (matches `codes` 1:1)
    memories: Vector[MemoryLimits],
    exports: Vector[Export],
    codes: Vector[FuncBody],
    data: Vector[DataSegment],
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
