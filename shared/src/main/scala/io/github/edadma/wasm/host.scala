package io.github.edadma.wasm

/** A host function takes a reference to linear memory plus its declared
  * arguments, and returns its declared results. MVP host functions either
  * take 0/1 i32 in and return 0/1 i32 out; signatures are checked against
  * the WebAssembly type signature at instantiation.
  */
type HostFunc = (Memory, Seq[Value]) => Seq[Value]

trait HostModule:
  def name: String
  def functions: Map[String, HostFunc]

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
    val functions: Map[String, HostFunc] = Map(
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
