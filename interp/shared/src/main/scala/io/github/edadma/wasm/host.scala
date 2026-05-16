package io.github.edadma.wasm

/** A host function takes a reference to linear memory plus its declared
  * arguments, and returns its declared results. Single-memory host
  * functions typically take 0/1 i32 in and return 0/1 i32 out;
  * signatures are checked against the WebAssembly type signature at
  * instantiation.
  *
  * Receives the module's *first* memory (memidx 0). Hosts that need to
  * inspect memidx > 0 (only meaningful with multi-memory modules — the
  * multi-memory proposal landed in Phase 8.D) use [[HostFuncMulti]]
  * instead.
  */
type HostFunc = (Memory, Seq[Value]) => Seq[Value]

/** A multi-memory-aware host function: receives the module's full
  * memory vector instead of just memidx 0. The vector is always
  * length >= 1 (zero-memory modules get a synthetic placeholder at
  * index 0, so single-memory programs that happen to declare zero
  * memories still see a valid Memory at `mems(0)`).
  *
  * Single-memory modules don't need this — register single-memory
  * functions through [[HostModule.functions]]. Use this when a host
  * function genuinely needs to read from or write to a memory other
  * than memidx 0. Both maps coexist; an import name present in both
  * resolves to the multi-memory form.
  */
type HostFuncMulti = (IndexedSeq[Memory], Seq[Value]) => Seq[Value]

/** An exported global from a host module. Imported by a wasm module's
  * import section (kind 0x03). The declared `valueType` and `mutable`
  * flag must match the importing module's `(import "..." "..." (global
  * <type> <mut>))` exactly.
  *
  * Backed internally by a [[GlobalCell]]. The `apply(vt, mut, value)`
  * factory wraps a literal in a fresh cell — fine for immutable
  * globals and for mutable globals the host doesn't need to observe.
  * For sharing live state across module boundaries (the wasm spec's
  * required semantics for cross-module mutable globals), use
  * [[HostGlobal.live]] with a `GlobalCell` the host can also read /
  * write outside the wasm module. */
final class HostGlobal private[wasm] (
    val valueType: ValueType,
    val mutable:   Boolean,
    val cell:      GlobalCell,
):
  /** Current value of the underlying cell. Equivalent to `cell.value`. */
  def value: Value = cell.value

  override def toString: String =
    s"HostGlobal($valueType, mutable=$mutable, value=$value)"

object HostGlobal:
  /** Wrap a literal value in a fresh cell. Use this when the host has
    * no need to share live state with the wasm module — the common
    * case for immutable globals and for one-shot configuration. */
  def apply(valueType: ValueType, mutable: Boolean, value: Value): HostGlobal =
    new HostGlobal(valueType, mutable, new GlobalCell(value))

  /** Build a HostGlobal backed by an externally-owned cell. The host
    * keeps a reference to `cell`; writes by the wasm module via
    * `global.set` mutate the same storage, so the host sees them
    * immediately. The spec runner uses this to forward one module's
    * exported globals as another module's imports without losing the
    * shared-storage semantics the wasm-3.0 spec requires. */
  def live(valueType: ValueType, mutable: Boolean, cell: GlobalCell): HostGlobal =
    new HostGlobal(valueType, mutable, cell)

trait HostModule:
  def name: String

  /** Single-memory host functions. Each receives memidx 0 implicitly. */
  def functions: Map[String, HostFunc] = Map.empty

  /** Multi-memory-aware host functions. Each receives the module's full
    * memory vector. Defaults to empty — a HostModule that only needs
    * single-memory access can ignore this surface entirely. */
  def functionsMulti: Map[String, HostFuncMulti] = Map.empty

  /** Host-provided globals. Imported by a wasm module's import section
    * (kind 0x03). Defaults to empty — most host modules don't expose
    * globals. */
  def globals: Map[String, HostGlobal] = Map.empty

  /** Host-provided linear memories. Imported by a wasm module's import
    * section (kind 0x02). The host supplies a live [[Memory]] instance;
    * the runtime checks the host's `currentPages` / `maxPages` against
    * the importing module's declared limits and uses the live instance
    * directly so guest writes are observable to the host. Defaults to
    * empty. */
  def memories: Map[String, Memory] = Map.empty

  /** Host-provided tables. Imported by a wasm module's import section
    * (kind 0x01). The host supplies a live [[RuntimeTable]]; the runtime
    * checks the table's `refType`, current `size`, and `max` against the
    * importing module's declared shape. Defaults to empty. */
  def tables: Map[String, RuntimeTable] = Map.empty

/** The single host module the interpreter ships with.
  *
  * Exposes `env.putchar(i32) -> ()`. By default it writes the low 8 bits of
  * the argument as a character to `System.out`. Tests pass a collecting
  * `write` callback to capture output instead of touching stdout.
  */
object EnvModule:

  /** Default: prints to stdout. */
  def default: HostModule = withWriter(c => System.out.print(c.toChar))

  /** Custom sink — useful for tests and for any host that wants to redirect
    * the byte stream somewhere other than the process stdout. */
  def withWriter(write: Int => Unit): HostModule = new HostModule:
    val name: String = "env"
    override val functions: Map[String, HostFunc] = Map(
      "putchar" -> { (_, args) =>
        args match
          case Seq(I32(c)) =>
            write(c & 0xff)
            Seq.empty
          case _ =>
            // Type mismatch is caught at instantiation; this branch is unreachable
            // in well-typed modules, but we return an empty result rather than throw.
            Seq.empty
      },
    )
