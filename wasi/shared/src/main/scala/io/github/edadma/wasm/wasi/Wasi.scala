package io.github.edadma.wasm.wasi

import scala.collection.mutable.ArrayBuffer

import io.github.edadma.wasm.{HostFunc, HostModule, I32, Memory, ModuleInstance, Value, WasmError}

/** WASI Preview 1 host shim for the `wasm` interpreter.
  *
  * Provides a [[HostModule]] named `"wasi_snapshot_preview1"` plus a
  * [[Wasi.run]] convenience wrapper for the canonical "invoke `_start`,
  * unwind on `proc_exit`" entry-point pattern. Phase 7.A ships two
  * syscalls — [[Wasi.preview1]] returns a `HostModule` with `fd_write`
  * and `proc_exit`; subsequent phases (7.B–7.E) add `args_*`,
  * `environ_*`, `clock_time_get`, `random_get`, `fd_close`, and real
  * filesystem access.
  *
  * The shim stays zero-dep: it leans only on `interp`'s [[HostFunc]] /
  * [[HostModule]] / [[Memory]] surface, which is itself zero-dep. So
  * the wasi module publishes cleanly to all three Scala 3 backends
  * (JVM, Scala.js, Scala Native) just like `interp`.
  *
  * Two design notes worth pinning here:
  *
  *   1. [[HostFunc]] already receives the module's linear [[Memory]] as
  *      its first argument, so reading/writing a wasi struct that lives
  *      in linear memory works out of the box. We do NOT need to surface
  *      `memory` as an export to make `fd_write` work — the host gets
  *      the memory either way. (A real wasi binary will export it
  *      anyway, because the WASI ABI requires it.)
  *
  *   2. `proc_exit` is a noreturn syscall. The shim signals it by
  *      throwing [[WasiExit]] — a private [[RuntimeException]] with
  *      stack-trace fill disabled (cheap, like the interpreter's own
  *      `ExecFail`). The interpreter's `invoke()` only catches
  *      `ExecFail` / `ArrayIndexOutOfBoundsException`, so `WasiExit`
  *      passes through cleanly. [[Wasi.run]] then catches it and folds
  *      the exit code into a `Right(code)`. The public API stays
  *      exception-free in the spirit of `Either[WasmError, T]`.
  */
object Wasi:

  // === WASI Preview 1 errno values ==========================================
  //
  // Only the ones we currently surface or that documentation points readers
  // at. Full list lives in the wasi-preview1 witx file. We keep these as
  // bare `Int` constants because the interpreter's `Value` boundary is
  // `I32` — no need for a richer enum here yet.

  /** Success. */
  val ESUCCESS: Int = 0
  /** Bad file descriptor (the fd is not open for write / does not exist). */
  val EBADF:    Int = 8
  /** Bad address — a pointer / length tuple in a wasi struct points outside
    * the live linear-memory range. */
  val EFAULT:   Int = 21
  /** Invalid argument — passed for shape-of-args failures (e.g. wrong arity)
    * before we surface a more-specific code. */
  val EINVAL:   Int = 28

  // === proc_exit unwind exception ===========================================

  /** Thrown by the `proc_exit` host function. Caught by [[Wasi.run]] and
    * folded into a `Right(code)` return.
    *
    * Disables stack-trace capture and suppression the same way the
    * interpreter's internal `ExecFail` does — control-flow exceptions in
    * a hot loop must not pay for `Throwable.fillInStackTrace` on every
    * exit. Callers outside this package shouldn't need to catch this
    * directly; if you DO bypass `Wasi.run` (e.g. you're invoking a
    * specific exported function on a wasi-imports module), catch it
    * yourself. */
  final class WasiExit private[wasi] (val code: Int)
      extends RuntimeException(null, null, false, false)

  // === Public entry points ==================================================

  /** Build a [[HostModule]] that resolves the `wasi_snapshot_preview1`
    * imports. Pass it to `Runtime.instantiate(bytes, Seq(Wasi.preview1(...)))`
    * alongside any other host modules the binary references.
    *
    * `ctx` controls the side-effecting surface: where bytes written to
    * fd 1 / fd 2 go, what process args and environ entries the program
    * sees, etc. The default writes to `System.out` / `System.err`; tests
    * almost always want [[WasiContext.collecting]] instead. */
  def preview1(ctx: WasiContext = WasiContext.default): HostModule =
    new HostModule:
      val name: String = "wasi_snapshot_preview1"
      val functions: Map[String, HostFunc] = Map(
        "fd_write"  -> ((mem, args) => fdWrite(mem, args, ctx)),
        "proc_exit" -> ((_,   args) => procExit(args)),
      )

  /** Invoke `entry` on a wasi-imports module and translate a
    * `proc_exit(code)` into a clean `Right(code)`. Returns `Right(0)` if
    * the program returned normally without ever calling `proc_exit`.
    *
    * Any other failure surfaces as `Left(WasmError.*)` — the runner
    * doesn't fold those into an exit code because they're genuinely
    * interpreter-level errors, not the program's reported exit state. */
  def run(inst: ModuleInstance, entry: String = "_start"): Either[WasmError, Int] =
    try
      inst.invoke(entry, Seq.empty) match
        case Right(_)  => Right(0)
        case Left(err) => Left(err)
    catch case e: WasiExit => Right(e.code)

  // === Syscalls =============================================================

  /** `fd_write(fd: i32, iovs: i32, iovs_len: i32, nwritten: i32) -> errno`
    *
    * The iovec table at `iovs` is a packed array of `iovs_len` entries,
    * each 8 bytes: an i32 buffer pointer followed by an i32 length, both
    * little-endian (every WASM scalar is). The function walks the
    * vectors in order, writes each buffer's bytes to the sink chosen
    * by `fd`, and stores the total byte count at `nwritten`.
    *
    * Errno discipline: out-of-bounds reads of any descriptor or buffer
    * return EFAULT (and DO NOT write `nwritten`). An unknown fd returns
    * EBADF. On a partial-success scenario (where some iovecs landed in
    * the sink but a later one is bad) we still return EFAULT and don't
    * stamp `nwritten` — wasi callers treat any non-zero errno as
    * authoritative and ignore the `nwritten` slot in that case, so this
    * choice is conservative but spec-compatible. */
  private def fdWrite(memory: Memory, args: Seq[Value], ctx: WasiContext): Seq[Value] =
    args match
      case Seq(I32(fd), I32(iovsPtr), I32(iovsLen), I32(nwrittenPtr)) =>
        val sink: Option[Int => Unit] = fd match
          case 1 => Some(ctx.stdout)
          case 2 => Some(ctx.stderr)
          case _ => None

        sink match
          case None => Seq(I32(EBADF))

          case Some(write) =>
            // Chase through `memory.data` once — the interpreter can't
            // grow memory while a host call is in flight (no wasm code
            // runs during the call), so caching the array reference for
            // the duration of this call is safe.
            val data    = memory.data
            val dataLen = data.length

            // Validate iovec table bounds up front. `iovs_len` is the
            // declared element count, so the total table size is
            // `iovs_len * 8` bytes; we use Long arithmetic for the
            // bounds check so a malicious i32 multiply can't wrap.
            val tableEnd = iovsPtr.toLong + iovsLen.toLong * 8L
            if iovsPtr < 0 || iovsLen < 0 || tableEnd > dataLen then
              return Seq(I32(EFAULT))

            var total = 0
            var i     = 0
            while i < iovsLen do
              val iovec = iovsPtr + i * 8
              val buf   = readI32LE(data, iovec)
              val len   = readI32LE(data, iovec + 4)
              val end   = buf.toLong + len.toLong
              if buf < 0 || len < 0 || end > dataLen then
                return Seq(I32(EFAULT))
              var j = 0
              while j < len do
                write(data(buf + j) & 0xff)
                j += 1
              total += len
              i     += 1

            // Stash the total count into `nwritten`. Same bounds rule:
            // if the destination 4 bytes don't fit in memory, the
            // syscall is malformed and we return EFAULT.
            if nwrittenPtr < 0 || nwrittenPtr.toLong + 4L > dataLen then
              return Seq(I32(EFAULT))
            writeI32LE(data, nwrittenPtr, total)
            Seq(I32(ESUCCESS))

      case _ => Seq(I32(EINVAL))

  /** `proc_exit(rval: i32) -> noreturn`
    *
    * The unwind that lets a wasi program signal its exit code. Throws
    * [[WasiExit]]; [[Wasi.run]] catches it and folds the code into a
    * `Right(code)` return. If the caller bypassed `Wasi.run` and is
    * invoking some other exported function directly, the exception
    * bubbles out of `invoke()` (the interpreter does NOT catch
    * `WasiExit`), so they need their own try/catch.
    *
    * The trailing `Seq.empty` is unreachable but keeps the result type
    * structural — the `throw` is sufficient to satisfy the compiler. */
  private def procExit(args: Seq[Value]): Seq[Value] =
    args match
      case Seq(I32(code)) => throw new WasiExit(code)
      case _              => throw new WasiExit(-1)

  // === little-endian i32 helpers ============================================
  //
  // WASM's linear memory is little-endian by spec, and the wasi struct
  // layouts follow suit. Hand-roll these so we stay independent of any
  // host `ByteBuffer` API (ByteBuffer.LITTLE_ENDIAN works on JVM and
  // Native but on Scala.js the underlying TypedArray semantics can
  // differ — staying in pure scalars sidesteps the question entirely).

  private inline def readI32LE(data: Array[Byte], offset: Int): Int =
    (data(offset    ) & 0xff)        |
    ((data(offset + 1) & 0xff) << 8) |
    ((data(offset + 2) & 0xff) << 16) |
    ((data(offset + 3) & 0xff) << 24)

  private inline def writeI32LE(data: Array[Byte], offset: Int, v: Int): Unit =
    data(offset    ) =  (v         & 0xff).toByte
    data(offset + 1) = ((v >>>  8) & 0xff).toByte
    data(offset + 2) = ((v >>> 16) & 0xff).toByte
    data(offset + 3) = ((v >>> 24) & 0xff).toByte

end Wasi

/** Side-effecting context for a WASI program: where its stdout / stderr
  * bytes land, what its `args` and `environ` look like, and (later
  * phases) its clock + random sources.
  *
  * `args` and `envs` are advertised through `args_*` / `environ_*` —
  * Phase 7.B wires them. Phase 7.A only reads `stdout` and `stderr`,
  * but the data class is final-cased now so subsequent phases don't
  * break the binary surface. The defaults write each byte to the
  * process's real stdout / stderr; tests almost always want
  * [[WasiContext.collecting]] instead.
  *
  * @param args  Argv as seen by the wasi program. Phase 7.B-only.
  * @param envs  Environment entries as `(NAME, value)` pairs. Phase
  *              7.B-only.
  * @param stdout Byte writer for fd 1.
  * @param stderr Byte writer for fd 2.
  */
final case class WasiContext(
    args:   Seq[String]         = Seq.empty,
    envs:   Seq[(String, String)] = Seq.empty,
    stdout: Int => Unit         = WasiContext.defaultStdout,
    stderr: Int => Unit         = WasiContext.defaultStderr,
)

object WasiContext:

  /** Default stdout sink — writes each byte to the JVM's `System.out`.
    * Behaviour on Scala.js / Native is "what `System.out.write(int)`
    * does on that platform" — JVM/Native both honour the byte as-is;
    * Scala.js routes through `console.log` with buffering. Tests should
    * not depend on this default; use [[collecting]]. */
  def defaultStdout(b: Int): Unit = System.out.write(b)

  /** Default stderr sink — same shape as [[defaultStdout]], but to
    * `System.err`. */
  def defaultStderr(b: Int): Unit = System.err.write(b)

  /** A `WasiContext()` with defaults — the value, not the constructor,
    * so `WasiContext.default` reads more like "the canonical empty
    * context" at call sites. */
  def default: WasiContext = WasiContext()

  /** Build a context with collecting stdout/stderr sinks. Returns
    * a [[Collecting]] that exposes the captured byte arrays + UTF-8
    * decoded strings; the `.context` field is the `WasiContext` you
    * pass to [[Wasi.preview1]]. Tests use this to assert on the
    * program's output without touching the real stdout/stderr. */
  def collecting(args: Seq[String] = Seq.empty,
                 envs: Seq[(String, String)] = Seq.empty): Collecting =
    new Collecting(args, envs)

  /** Captures stdout/stderr bytes from a wasi program. Threading-wise
    * this is single-threaded — the interpreter is single-threaded, so
    * we don't synchronize the underlying buffers. */
  final class Collecting private[wasi] (args: Seq[String], envs: Seq[(String, String)]):
    private val stdoutBuf = ArrayBuffer.empty[Byte]
    private val stderrBuf = ArrayBuffer.empty[Byte]
    val context: WasiContext = WasiContext(
      args   = args,
      envs   = envs,
      stdout = b => stdoutBuf += b.toByte,
      stderr = b => stderrBuf += b.toByte,
    )
    def stdoutBytes:  Array[Byte] = stdoutBuf.toArray
    def stderrBytes:  Array[Byte] = stderrBuf.toArray
    def stdoutString: String      = new String(stdoutBytes, "UTF-8")
    def stderrString: String      = new String(stderrBytes, "UTF-8")
