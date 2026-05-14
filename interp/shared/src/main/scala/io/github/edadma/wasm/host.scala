package io.github.edadma.wasm

/** A host function takes a reference to linear memory plus its declared
  * arguments, and returns its declared results. MVP host functions either
  * take 0/1 i32 in and return 0/1 i32 out; signatures are checked against
  * the WebAssembly type signature at instantiation.
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

trait HostModule:
  def name: String

  /** Single-memory host functions. Each receives memidx 0 implicitly. */
  def functions: Map[String, HostFunc] = Map.empty

  /** Multi-memory-aware host functions. Each receives the module's full
    * memory vector. Defaults to empty — a HostModule that only needs
    * single-memory access can ignore this surface entirely. */
  def functionsMulti: Map[String, HostFuncMulti] = Map.empty

/** The single host module the MVP interpreter ships with.
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
