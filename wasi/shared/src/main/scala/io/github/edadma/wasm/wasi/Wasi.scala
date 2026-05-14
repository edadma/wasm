package io.github.edadma.wasm.wasi

import scala.collection.mutable.ArrayBuffer

import io.github.edadma.wasm.{HostFunc, HostModule, I32, Memory, ModuleInstance, Value, WasmError}

/** WASI Preview 1 host shim for the `wasm` interpreter.
  *
  * Provides a [[HostModule]] named `"wasi_snapshot_preview1"` plus a
  * [[Wasi.run]] convenience wrapper for the canonical "invoke `_start`,
  * unwind on `proc_exit`" entry-point pattern. Through Phase 7.E.1 the
  * shim resolves eleven syscalls: `fd_write`, `fd_close` (stdio-only),
  * `proc_exit`, `args_sizes_get` / `args_get`, `environ_sizes_get` /
  * `environ_get`, `clock_time_get`, `random_get`, `fd_prestat_get`,
  * and `fd_prestat_dir_name`. Phase 7.E.2 will add the file-handle
  * surface (`path_open` / `fd_read` / `fd_seek` / general-fd `fd_close`
  * / `fd_filestat_get`).
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
  /** Name too long — `fd_prestat_dir_name` was given a buffer smaller than
    * the preopen name. wasi-libc's normal call sequence reads `name_len`
    * from `fd_prestat_get` first, so this is a caller bug rather than a
    * legitimate "ask the host to truncate" request. */
  val ENAMETOOLONG: Int = 37

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
        "fd_write"            -> ((mem, args) => fdWrite(mem, args, ctx)),
        "fd_close"            -> ((_,   args) => fdClose(args)),
        "proc_exit"           -> ((_,   args) => procExit(args)),
        "args_sizes_get"      -> ((mem, args) => sizesGet(mem, args, argEntries(ctx))),
        "args_get"            -> ((mem, args) => entriesGet(mem, args, argEntries(ctx))),
        "environ_sizes_get"   -> ((mem, args) => sizesGet(mem, args, envEntries(ctx))),
        "environ_get"         -> ((mem, args) => entriesGet(mem, args, envEntries(ctx))),
        "clock_time_get"      -> ((mem, args) => clockTimeGet(mem, args, ctx)),
        "random_get"          -> ((mem, args) => randomGet(mem, args, ctx)),
        "fd_prestat_get"      -> ((mem, args) => prestatGet(mem, args, ctx)),
        "fd_prestat_dir_name" -> ((mem, args) => prestatDirName(mem, args, ctx)),
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

  // === args + environ (Phase 7.B) ===========================================
  //
  // WASI presents process args and the environment block through two pairs
  // of syscalls with an identical shape — only the source list differs.
  // We share the implementation: `sizesGet` writes the two i32 size words
  // and `entriesGet` writes the pointer-vector + NUL-terminated buffer.
  //
  // Each entry is laid out on the wasi side as a NUL-terminated UTF-8
  // byte sequence. For environ entries we use the conventional
  // `NAME=VALUE` form. The host pre-computes each entry's bytes once per
  // call: that lets `sizes_get` and `_get` agree on byte counts even when
  // (a future caller) hands us non-ASCII strings.

  /** Compute the wasi-side byte representation of each arg as
    * `UTF-8 bytes + 0x00`. The trailing NUL is part of the entry from
    * the wasi caller's perspective — that's how `args_sizes_get` and
    * `args_get` must agree. */
  private def argEntries(ctx: WasiContext): Array[Array[Byte]] =
    ctx.args.iterator.map(s => nulTerminated(s)).toArray

  /** Compute the wasi-side byte representation of each environ entry as
    * `NAME=VALUE` UTF-8 bytes + 0x00. */
  private def envEntries(ctx: WasiContext): Array[Array[Byte]] =
    ctx.envs.iterator.map { case (k, v) => nulTerminated(s"$k=$v") }.toArray

  private def nulTerminated(s: String): Array[Byte] =
    val raw = s.getBytes("UTF-8")
    val out = new Array[Byte](raw.length + 1)
    System.arraycopy(raw, 0, out, 0, raw.length)
    out(raw.length) = 0
    out

  /** `*_sizes_get(count_ptr: i32, buf_size_ptr: i32) -> errno`
    *
    * Writes the number of entries at `count_ptr` and the total byte size
    * (sum of NUL-terminated UTF-8 lengths) at `buf_size_ptr`. Either
    * pointer being out-of-range yields EFAULT and neither slot is
    * written.
    *
    * Shared between `args_sizes_get` and `environ_sizes_get` — the
    * caller picks the entry list. */
  private def sizesGet(memory: Memory, args: Seq[Value],
                       entries: Array[Array[Byte]]): Seq[Value] =
    args match
      case Seq(I32(countPtr), I32(bufSizePtr)) =>
        val data    = memory.data
        val dataLen = data.length
        if !fits4(countPtr,   dataLen) then return Seq(I32(EFAULT))
        if !fits4(bufSizePtr, dataLen) then return Seq(I32(EFAULT))
        var totalBytes = 0
        var i          = 0
        while i < entries.length do
          totalBytes += entries(i).length
          i          += 1
        writeI32LE(data, countPtr,   entries.length)
        writeI32LE(data, bufSizePtr, totalBytes)
        Seq(I32(ESUCCESS))
      case _ => Seq(I32(EINVAL))

  /** `*_get(ptr_vec_ptr: i32, buf_ptr: i32) -> errno`
    *
    * Writes `count` 32-bit pointers at `ptr_vec_ptr` followed by the
    * NUL-terminated UTF-8 buffer at `buf_ptr`. Each pointer addresses
    * the start of the matching entry inside `buf_ptr`'s region.
    *
    * Bounds-check both regions up front using Long arithmetic so a
    * deliberately-wrapping i32 multiply can't sneak past. On EFAULT we
    * write nothing — partial writes would leak state from a malformed
    * call and the wasi spec gives us the latitude to fail atomically.
    *
    * Shared between `args_get` and `environ_get`. */
  private def entriesGet(memory: Memory, args: Seq[Value],
                         entries: Array[Array[Byte]]): Seq[Value] =
    args match
      case Seq(I32(ptrVecPtr), I32(bufPtr)) =>
        val data    = memory.data
        val dataLen = data.length

        var totalBytes = 0
        var i          = 0
        while i < entries.length do
          totalBytes += entries(i).length
          i          += 1

        val ptrVecEnd = ptrVecPtr.toLong + entries.length.toLong * 4L
        val bufEnd    = bufPtr.toLong    + totalBytes.toLong
        if ptrVecPtr < 0 || bufPtr < 0 ||
           ptrVecEnd > dataLen || bufEnd > dataLen
        then return Seq(I32(EFAULT))

        var bufCursor = bufPtr
        i = 0
        while i < entries.length do
          val entry = entries(i)
          writeI32LE(data, ptrVecPtr + i * 4, bufCursor)
          System.arraycopy(entry, 0, data, bufCursor, entry.length)
          bufCursor += entry.length
          i         += 1
        Seq(I32(ESUCCESS))
      case _ => Seq(I32(EINVAL))

  /** Does a 4-byte little-endian word fit at `ptr` in a buffer of
    * length `dataLen`? Negative `ptr` always fails. */
  private inline def fits4(ptr: Int, dataLen: Int): Boolean =
    ptr >= 0 && ptr.toLong + 4L <= dataLen

  // === clock_time_get + random_get + fd_close (Phase 7.C) ===================
  //
  // Three small slow-path syscalls that round out the "basic POSIX program
  // doesn't crash on startup" surface. The pattern from 7.A/7.B holds: each
  // syscall validates pointer bounds with Long arithmetic before touching
  // memory, returns a wasi errno, and never throws on a malformed call.

  /** `clock_time_get(clock_id: i32, precision: i64, time_ptr: i32) -> errno`
    *
    * Writes a 64-bit little-endian nanosecond timestamp at `time_ptr`.
    * `precision` is advisory — the wasi spec lets the host round to
    * whatever resolution its clock provides, and we ignore the field.
    *
    * Clock ids:
    *   - 0 = realtime  — wall clock since UNIX epoch
    *   - 1 = monotonic — arbitrary epoch, non-decreasing
    *   - 2 = process_cputime_id — folded to monotonic
    *   - 3 = thread_cputime_id  — folded to monotonic
    *   - else → EINVAL
    *
    * Folding 2/3 to monotonic is intentional. The JVM exposes per-thread
    * CPU time via `ManagementFactory.getThreadMXBean`, but Scala.js and
    * Scala Native don't have a portable equivalent. Returning monotonic
    * matches what most wasi shims do for portability and keeps every
    * backend behaving identically. Programs that absolutely need CPU
    * time can plug a different `WasiContext.Clock` in. */
  private def clockTimeGet(memory: Memory, args: Seq[Value],
                           ctx: WasiContext): Seq[Value] =
    args match
      case Seq(I32(clockId), _ /* precision i64, ignored */, I32(timePtr)) =>
        val data    = memory.data
        val dataLen = data.length
        if timePtr < 0 || timePtr.toLong + 8L > dataLen then
          return Seq(I32(EFAULT))
        val nanos: Long = clockId match
          case 0     => ctx.clock.realtimeNanos()
          case 1     => ctx.clock.monotonicNanos()
          case 2 | 3 => ctx.clock.monotonicNanos()
          case _     => return Seq(I32(EINVAL))
        writeI64LE(data, timePtr, nanos)
        Seq(I32(ESUCCESS))
      case _ => Seq(I32(EINVAL))

  /** `random_get(buf: i32, buf_len: i32) -> errno`
    *
    * Fill `buf_len` bytes at `buf` with random data drawn from
    * `ctx.random`. The default source is `scala.util.Random` — not
    * cryptographic; callers needing real entropy plug a different
    * closure in. `buf_len == 0` is a no-op success.
    *
    * Bounds-check `buf + buf_len` with Long arithmetic so a wrap-around
    * can't sneak past. */
  private def randomGet(memory: Memory, args: Seq[Value],
                        ctx: WasiContext): Seq[Value] =
    args match
      case Seq(I32(buf), I32(bufLen)) =>
        val data    = memory.data
        val dataLen = data.length
        if buf < 0 || bufLen < 0 || buf.toLong + bufLen.toLong > dataLen then
          return Seq(I32(EFAULT))
        if bufLen > 0 then
          val bytes = ctx.random(bufLen)
          System.arraycopy(bytes, 0, data, buf, bufLen)
        Seq(I32(ESUCCESS))
      case _ => Seq(I32(EINVAL))

  /** `fd_close(fd: i32) -> errno`
    *
    * For fd 0 / 1 / 2 (stdin / stdout / stderr) return ESUCCESS — userspace
    * stdio closes are benign and a wasi program that religiously closes all
    * three on shutdown shouldn't break on our shim. fd >= 3 or fd < 0
    * returns EBADF: we don't surface fs-level fds until Phase 7.E. */
  private def fdClose(args: Seq[Value]): Seq[Value] =
    args match
      case Seq(I32(fd)) =>
        if fd == 0 || fd == 1 || fd == 2 then Seq(I32(ESUCCESS))
        else Seq(I32(EBADF))
      case _ => Seq(I32(EINVAL))

  // === preopen scaffolding (Phase 7.E.1) ====================================
  //
  // wasi-libc walks fd 3 upward at startup, asking `fd_prestat_get` for
  // each, until the host returns EBADF. Each surviving fd is then
  // queried with `fd_prestat_dir_name` to learn the directory's
  // wasi-visible name; userspace builds a name→fd map and resolves all
  // relative paths through it. 7.E.1 ships just this introspection
  // surface — Phase 7.E.2 extends [[WasiContext.Preopen]] with
  // `open(path, ...)` and adds `path_open` / `fd_read` / `fd_seek` /
  // general-fd `fd_close`.
  //
  // The fd→preopen map is the index `fd - 3` into `ctx.preopens`: the
  // first preopen lives at fd 3, the second at fd 4, etc. No mutable
  // fd table is required at this slice because preopens are static for
  // the lifetime of a `WasiContext`.

  /** `fd_prestat_get(fd: i32, buf: i32) -> errno`
    *
    * Writes the 8-byte `prestat` struct at `buf`:
    *
    *   offset 0       : u8  tag (always 0 = `dir`; the only currently
    *                          defined preopentype variant)
    *   offset 1 .. 3  : u8  reserved padding (must be zero)
    *   offset 4 .. 7  : u32 `pr_name_len` in little-endian — number of
    *                        UTF-8 bytes in the directory's name
    *
    * The struct is a tagged union under the wasi witx; reserved padding
    * matters because a future variant may pack additional fields after
    * the tag, so we explicitly zero bytes 1..3 rather than leaving
    * whatever the program last wrote there.
    *
    * Errno discipline: EFAULT if `buf+8` is out of bounds; EBADF if
    * `fd < 3` or `fd - 3 >= ctx.preopens.length`. On EBADF/EFAULT no
    * bytes are written. */
  private def prestatGet(memory: Memory, args: Seq[Value],
                         ctx: WasiContext): Seq[Value] =
    args match
      case Seq(I32(fd), I32(buf)) =>
        val data    = memory.data
        val dataLen = data.length
        if buf < 0 || buf.toLong + 8L > dataLen then
          return Seq(I32(EFAULT))
        val idx = fd - 3
        if idx < 0 || idx >= ctx.preopens.length then
          return Seq(I32(EBADF))
        val nameBytes = ctx.preopens(idx).name.getBytes("UTF-8")
        data(buf    ) = 0   // tag = 0 (dir)
        data(buf + 1) = 0   // reserved
        data(buf + 2) = 0
        data(buf + 3) = 0
        writeI32LE(data, buf + 4, nameBytes.length)
        Seq(I32(ESUCCESS))
      case _ => Seq(I32(EINVAL))

  /** `fd_prestat_dir_name(fd: i32, path_ptr: i32, path_len: i32) -> errno`
    *
    * Writes exactly `path_len` UTF-8 bytes of the preopen's directory
    * name at `path_ptr`. The wasi-libc call sequence reads `pr_name_len`
    * from [[prestatGet]] and allocates a buffer of exactly that size,
    * so a `path_len` that doesn't match the name length means caller
    * bug rather than a legitimate truncation request — we return
    * `ENAMETOOLONG` rather than silently writing a partial name. This
    * matches wasmtime and uvwasi (wasmer truncates silently — we
    * deliberately don't).
    *
    * Order of checks: EBADF (we need the preopen to know `nameBytes`)
    * → ENAMETOOLONG (`path_len < nameBytes.length`) → EFAULT (memory
    * range). On any non-success errno no bytes are written. */
  private def prestatDirName(memory: Memory, args: Seq[Value],
                             ctx: WasiContext): Seq[Value] =
    args match
      case Seq(I32(fd), I32(pathPtr), I32(pathLen)) =>
        val idx = fd - 3
        if idx < 0 || idx >= ctx.preopens.length then
          return Seq(I32(EBADF))
        val nameBytes = ctx.preopens(idx).name.getBytes("UTF-8")
        if pathLen < nameBytes.length then
          return Seq(I32(ENAMETOOLONG))
        val data    = memory.data
        val dataLen = data.length
        if pathPtr < 0 || pathPtr.toLong + pathLen.toLong > dataLen then
          return Seq(I32(EFAULT))
        System.arraycopy(nameBytes, 0, data, pathPtr, nameBytes.length)
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

  private inline def writeI64LE(data: Array[Byte], offset: Int, v: Long): Unit =
    data(offset    ) =  (v         & 0xffL).toByte
    data(offset + 1) = ((v >>>  8) & 0xffL).toByte
    data(offset + 2) = ((v >>> 16) & 0xffL).toByte
    data(offset + 3) = ((v >>> 24) & 0xffL).toByte
    data(offset + 4) = ((v >>> 32) & 0xffL).toByte
    data(offset + 5) = ((v >>> 40) & 0xffL).toByte
    data(offset + 6) = ((v >>> 48) & 0xffL).toByte
    data(offset + 7) = ((v >>> 56) & 0xffL).toByte

end Wasi

/** Side-effecting context for a WASI program: where its stdout / stderr
  * bytes land, what its `args` and `environ` look like, what its clock
  * sources read, and how `random_get` is fulfilled.
  *
  * `args` and `envs` are advertised through `args_*` / `environ_*`
  * (Phase 7.B). `clock` powers `clock_time_get`, `random` powers
  * `random_get` (Phase 7.C). The defaults write each byte to the
  * process's real stdout / stderr, fold realtime/monotonic clocks onto
  * `System.currentTimeMillis * 1e6` and `System.nanoTime` respectively
  * (all three backends support both), and draw bytes from a process-
  * local `scala.util.Random`. Tests almost always want
  * [[WasiContext.collecting]] instead, which captures stdout/stderr to
  * memory and accepts injected `Clock` / `random` for deterministic
  * runs.
  *
  * @param args     Argv as seen by the wasi program.
  * @param envs     Environment entries as `(NAME, value)` pairs.
  * @param stdout   Byte writer for fd 1.
  * @param stderr   Byte writer for fd 2.
  * @param clock    Realtime + monotonic clock sources (nanoseconds).
  * @param random   `n => Array[Byte]` of length `n` that fills
  *                 `random_get` buffers.
  * @param preopens Preopened directories the wasi program inherits.
  *                 The i-th preopen is exposed at fd `3 + i`. Default
  *                 is empty — userspace then sees just stdin/stdout/
  *                 stderr and any `path_open` of a relative path fails.
  */
final case class WasiContext(
    args:     Seq[String]                  = Seq.empty,
    envs:     Seq[(String, String)]        = Seq.empty,
    stdout:   Int => Unit                  = WasiContext.defaultStdout,
    stderr:   Int => Unit                  = WasiContext.defaultStderr,
    clock:    WasiContext.Clock            = WasiContext.systemClock,
    random:   Int => Array[Byte]           = WasiContext.defaultRandom,
    preopens: Seq[WasiContext.Preopen]     = Seq.empty,
)

object WasiContext:

  /** A preopened directory exposed to the wasi program. wasi-libc walks
    * fds 3, 4, … at startup, asking `fd_prestat_get` for each, and
    * stops when the host returns EBADF; it then builds a name→fd map
    * from `fd_prestat_dir_name` and resolves all relative paths
    * through it. The wasi-visible directory `name` is what userspace
    * matches against.
    *
    * Phase 7.E.1 ships just the name surface — the `Preopen` trait is
    * deliberately minimal so the prestat-walk syscalls can land
    * standalone. Phase 7.E.2 will extend this with `open(path, ...)`
    * returning an `FsFile` handle, at which point `path_open`,
    * `fd_read`, `fd_seek`, and general-fd `fd_close` arrive together
    * with the in-memory `Fs` impl tests need.
    *
    * `name` is interpreted as UTF-8 — `fd_prestat_get`'s `pr_name_len`
    * and `fd_prestat_dir_name`'s output buffer both deal in bytes,
    * not chars, so non-ASCII preopen names cross the host/wasm
    * boundary without surprise. */
  trait Preopen:
    def name: String

  object Preopen:
    /** A trivial preopen carrying just a wasi-visible directory name.
      * Useful at Phase 7.E.1, where no FS operations exist yet — once
      * 7.E.2 lands `path_open` the trait gains additional methods and
      * tests will reach for richer factories. */
    def named(n: String): Preopen = new Preopen:
      val name: String = n

  /** A pair of clocks — wall clock and a non-decreasing monotonic source.
    * Both surfaced as nanoseconds because that's the wasi-preview1 ABI
    * shape. Tests inject a deterministic impl; the default uses
    * `System.currentTimeMillis * 1e6` and `System.nanoTime`, both of
    * which Scala.js (`Date.now` / `performance.now`) and Scala Native
    * support — so the shim stays cross-platform without conditional
    * code. */
  trait Clock:
    def realtimeNanos():  Long
    def monotonicNanos(): Long

  /** Default `Clock` — `System.currentTimeMillis() * 1_000_000` for
    * realtime, `System.nanoTime()` for monotonic. The realtime read
    * loses sub-millisecond resolution; in practice that's fine because
    * the wasi `precision` arg is advisory and most programs only read
    * realtime for "what time is it" not "how long did this take". */
  val systemClock: Clock = new Clock:
    def realtimeNanos():  Long = System.currentTimeMillis() * 1_000_000L
    def monotonicNanos(): Long = System.nanoTime()

  /** Default `random_get` source: a process-local `scala.util.Random`.
    * Not cryptographically strong; programs that need real entropy
    * plug a different closure in via the `random` constructor param.
    * Tests inject a fixed-seed Random (or a stub returning a known
    * byte string) for deterministic assertions. */
  def defaultRandom(n: Int): Array[Byte] =
    val out = new Array[Byte](n)
    scala.util.Random.nextBytes(out)
    out

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
    * program's output without touching the real stdout/stderr; the
    * `clock` / `random` / `preopens` overrides let them assert on
    * deterministic clock, random, and filesystem-introspection reads
    * as well. */
  def collecting(args:     Seq[String]              = Seq.empty,
                 envs:     Seq[(String, String)]    = Seq.empty,
                 clock:    Clock                    = systemClock,
                 random:   Int => Array[Byte]       = defaultRandom,
                 preopens: Seq[Preopen]             = Seq.empty): Collecting =
    new Collecting(args, envs, clock, random, preopens)

  /** Captures stdout/stderr bytes from a wasi program. Threading-wise
    * this is single-threaded — the interpreter is single-threaded, so
    * we don't synchronize the underlying buffers. */
  final class Collecting private[wasi] (args:     Seq[String],
                                        envs:     Seq[(String, String)],
                                        clock:    Clock,
                                        random:   Int => Array[Byte],
                                        preopens: Seq[Preopen]):
    private val stdoutBuf = ArrayBuffer.empty[Byte]
    private val stderrBuf = ArrayBuffer.empty[Byte]
    val context: WasiContext = WasiContext(
      args     = args,
      envs     = envs,
      stdout   = b => stdoutBuf += b.toByte,
      stderr   = b => stderrBuf += b.toByte,
      clock    = clock,
      random   = random,
      preopens = preopens,
    )
    def stdoutBytes:  Array[Byte] = stdoutBuf.toArray
    def stderrBytes:  Array[Byte] = stderrBuf.toArray
    def stdoutString: String      = new String(stdoutBytes, "UTF-8")
    def stderrString: String      = new String(stderrBytes, "UTF-8")
