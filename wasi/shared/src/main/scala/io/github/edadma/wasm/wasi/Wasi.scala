package io.github.edadma.wasm.wasi

import scala.collection.mutable.ArrayBuffer

import io.github.edadma.wasm.{HostFunc, HostModule, I32, I64, Memory, ModuleInstance, Value, WasmError}

/** WASI Preview 1 host shim for the `wasm` interpreter.
  *
  * Provides a [[HostModule]] named `"wasi_snapshot_preview1"` plus a
  * [[Wasi.run]] convenience wrapper for the canonical "invoke `_start`,
  * unwind on `proc_exit`" entry-point pattern. Through Phase 7.F the
  * shim resolves sixteen syscalls: `fd_write`, `fd_read`, `fd_close`
  * (full table — stdio, preopens, opened-file fds), `fd_seek`,
  * `fd_filestat_get`, `fd_fdstat_get`, `proc_exit`, `args_sizes_get` /
  * `args_get`, `environ_sizes_get` / `environ_get`, `clock_time_get`,
  * `random_get`, `fd_prestat_get`, `fd_prestat_dir_name`, and
  * `path_open`. 7.E.4 was the end-to-end smoke test that boots a real
  * rustc-built `wasm32-wasip1` file-reader binary through this surface;
  * `fd_fdstat_get` was the only gap the binary surfaced. Phase 7.F
  * extends the file surface to writes: `path_open` now honours
  * `OFLAGS_CREAT` and `OFLAGS_TRUNC`, `fd_write` routes to the FdTable
  * for opened-file fds, and [[Wasi.FsFile]] grew a `write` method.
  * The 7.F smoke test boots a rustc `std::fs::write` binary; no new
  * syscall gap surfaced.
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
  /** No such file or directory — `path_open`'s preopen-resolved lookup
    * didn't find the path in its FS. The conventional return when a
    * user-typed path doesn't exist. */
  val ENOENT:       Int = 44
  /** No space left on device — reserved for write impls that hit a
    * declared cap. Not currently emitted by [[WasiContext.Preopen.inMemory]],
    * but documented here so future host-backed write impls have a
    * consistent errno to surface. */
  val ENOSPC:       Int = 51
  /** Permission denied — host filesystem refused access. Surfaced by
    * host-backed preopens when a host-level read/write/stat / unlink /
    * mkdir is rejected by the OS (e.g. mode bits, ACLs, read-only
    * filesystem on Linux). The InMemoryFs never emits this. */
  val EACCES:       Int = 2
  /** I/O error — host filesystem returned an unspecified error during
    * a read / write / open / stat call. Surfaced only by host-backed
    * preopens (the InMemoryFs cannot fail this way). */
  val EIO:          Int = 29
  /** File exists — `path_open` with `OFLAGS_CREAT | OFLAGS_EXCL` was
    * asked to create a file that already exists. EXCL is the way
    * userspace asks for "atomic create" semantics. */
  val EEXIST:       Int = 20
  /** Is a directory — `path_open` (with or without OFLAGS_DIRECTORY) was
    * asked to open a path that resolves to a directory through the
    * regular-file path. The InMemoryFs doesn't yet support opening
    * directory fds beyond preopens, so any path_open on a DirEntry
    * surfaces this. */
  val EISDIR:       Int = 31
  /** Not a directory — `OFLAGS_DIRECTORY` set on `path_open` but the
    * path resolves to a regular file; also any syscall that requires
    * a directory fd but received a regular-file fd. */
  val ENOTDIR:      Int = 54
  /** Capability insufficient — the preopen advertises its name but has no
    * FS capability behind it ([[WasiContext.Preopen.named]]). A program
    * asking to `path_open` through it gets this errno rather than
    * `ENOENT`, because the distinction matters: ENOENT says "no such
    * path", ENOTCAPABLE says "you can't even ask through this preopen". */
  val ENOTCAPABLE:  Int = 76

  // === WASI Preview 1 rights bits (witx-defined) ============================
  //
  // Each bit is a capability a wasi fd may carry. Programs query the bitmap
  // through `fd_fdstat_get` (filling the `fs_rights_base` / `fs_rights_inheriting`
  // u64 fields) to learn what they can do with the fd. wasi-libc gates
  // every syscall it dispatches against the relevant bit, so getting the
  // per-filetype masks right matters: a directory fd with `FD_READ` set
  // would let `fd_read` succeed against the directory; a regular-file fd
  // missing `FD_SEEK` would force wasi-libc to refuse `lseek`, etc.
  //
  // Bit positions follow the witx canonical order (see
  // wasi-preview1's `rights` flags type). The individual `RIGHT_*` bits
  // are package-private; tests and external users want the composite
  // [[RIGHTS_CHARACTER_DEVICE]] / [[RIGHTS_DIRECTORY_BASE]] /
  // [[RIGHTS_DIRECTORY_INHERITING]] / [[RIGHTS_REGULAR_FILE]] masks
  // exposed below.

  private[wasi] val RIGHT_FD_DATASYNC:             Long = 1L << 0
  private[wasi] val RIGHT_FD_READ:                 Long = 1L << 1
  private[wasi] val RIGHT_FD_SEEK:                 Long = 1L << 2
  private[wasi] val RIGHT_FD_FDSTAT_SET_FLAGS:     Long = 1L << 3
  private[wasi] val RIGHT_FD_SYNC:                 Long = 1L << 4
  private[wasi] val RIGHT_FD_TELL:                 Long = 1L << 5
  private[wasi] val RIGHT_FD_WRITE:                Long = 1L << 6
  private[wasi] val RIGHT_FD_ADVISE:               Long = 1L << 7
  private[wasi] val RIGHT_FD_ALLOCATE:             Long = 1L << 8
  private[wasi] val RIGHT_PATH_CREATE_DIRECTORY:   Long = 1L << 9
  private[wasi] val RIGHT_PATH_CREATE_FILE:        Long = 1L << 10
  private[wasi] val RIGHT_PATH_LINK_SOURCE:        Long = 1L << 11
  private[wasi] val RIGHT_PATH_LINK_TARGET:        Long = 1L << 12
  private[wasi] val RIGHT_PATH_OPEN:               Long = 1L << 13
  private[wasi] val RIGHT_FD_READDIR:              Long = 1L << 14
  private[wasi] val RIGHT_PATH_READLINK:           Long = 1L << 15
  private[wasi] val RIGHT_PATH_RENAME_SOURCE:      Long = 1L << 16
  private[wasi] val RIGHT_PATH_RENAME_TARGET:      Long = 1L << 17
  private[wasi] val RIGHT_PATH_FILESTAT_GET:       Long = 1L << 18
  private[wasi] val RIGHT_PATH_FILESTAT_SET_SIZE:  Long = 1L << 19
  private[wasi] val RIGHT_PATH_FILESTAT_SET_TIMES: Long = 1L << 20
  private[wasi] val RIGHT_FD_FILESTAT_GET:         Long = 1L << 21
  private[wasi] val RIGHT_FD_FILESTAT_SET_SIZE:    Long = 1L << 22
  private[wasi] val RIGHT_FD_FILESTAT_SET_TIMES:   Long = 1L << 23
  private[wasi] val RIGHT_PATH_SYMLINK:            Long = 1L << 24
  private[wasi] val RIGHT_PATH_REMOVE_DIRECTORY:   Long = 1L << 25
  private[wasi] val RIGHT_PATH_UNLINK_FILE:        Long = 1L << 26
  private[wasi] val RIGHT_POLL_FD_READWRITE:       Long = 1L << 27
  private[wasi] val RIGHT_SOCK_SHUTDOWN:           Long = 1L << 28
  private[wasi] val RIGHT_SOCK_ACCEPT:             Long = 1L << 29

  /** Rights mask for a stdio fd. Stdio is read+write but not seekable, so
    * `FD_SEEK` / `FD_TELL` are absent. `FD_FDSTAT_SET_FLAGS` lets
    * userspace flip `O_NONBLOCK` on stdin/stdout (wasi-libc does this
    * during line-buffered input). `FD_FILESTAT_GET` is always on so
    * `isatty()` checks resolve. */
  val RIGHTS_CHARACTER_DEVICE: Long =
    RIGHT_FD_READ | RIGHT_FD_WRITE |
    RIGHT_FD_FDSTAT_SET_FLAGS | RIGHT_FD_FILESTAT_GET |
    RIGHT_POLL_FD_READWRITE

  /** Rights mask for a preopen directory fd's `fs_rights_base` — what the
    * directory fd ITSELF supports. Every `path_*` op (open, create,
    * unlink, rename, symlink, …) plus `fd_readdir`, `fd_filestat_get`,
    * `fd_fdstat_set_flags`. Notably missing: `FD_READ` / `FD_WRITE` /
    * `FD_SEEK` — a directory is not readable as a stream of bytes via
    * `fd_read` (wasi has `fd_readdir` for that). */
  val RIGHTS_DIRECTORY_BASE: Long =
    RIGHT_FD_FDSTAT_SET_FLAGS | RIGHT_FD_FILESTAT_GET | RIGHT_FD_READDIR |
    RIGHT_PATH_CREATE_DIRECTORY | RIGHT_PATH_CREATE_FILE |
    RIGHT_PATH_LINK_SOURCE | RIGHT_PATH_LINK_TARGET | RIGHT_PATH_OPEN |
    RIGHT_PATH_READLINK |
    RIGHT_PATH_RENAME_SOURCE | RIGHT_PATH_RENAME_TARGET |
    RIGHT_PATH_FILESTAT_GET | RIGHT_PATH_FILESTAT_SET_SIZE |
    RIGHT_PATH_FILESTAT_SET_TIMES |
    RIGHT_PATH_SYMLINK | RIGHT_PATH_REMOVE_DIRECTORY |
    RIGHT_PATH_UNLINK_FILE

  /** Rights mask for a regular file fd: full read/write/seek surface,
    * filestat get+set, allocate/advise/sync. */
  val RIGHTS_REGULAR_FILE: Long =
    RIGHT_FD_DATASYNC | RIGHT_FD_READ | RIGHT_FD_SEEK |
    RIGHT_FD_FDSTAT_SET_FLAGS | RIGHT_FD_SYNC | RIGHT_FD_TELL |
    RIGHT_FD_WRITE | RIGHT_FD_ADVISE | RIGHT_FD_ALLOCATE |
    RIGHT_FD_FILESTAT_GET | RIGHT_FD_FILESTAT_SET_SIZE |
    RIGHT_FD_FILESTAT_SET_TIMES |
    RIGHT_POLL_FD_READWRITE

  /** Rights a preopen-directory fd advertises as inheritable to children:
    * every fd opened relative to the directory carries rights drawn
    * from this mask (intersected with what the program asked for in
    * `path_open`). Union of directory + regular-file rights, since a
    * `path_open` may yield either a directory or a regular file. */
  val RIGHTS_DIRECTORY_INHERITING: Long =
    RIGHTS_DIRECTORY_BASE | RIGHTS_REGULAR_FILE

  // === File handle abstraction (Phase 7.E.2 + 7.E.3) ========================

  /** An opaque handle to an opened file, returned by
    * [[WasiContext.Preopen.open]] and stored in the per-instantiation fd
    * table that `Wasi.preview1` allocates.
    *
    * Phase 7.E.2 shipped `close()` only — the lifecycle hook. Phase 7.E.3
    * added the read/seek/stat surface: `read` (host-buffer destination,
    * returns bytes-read), `size` (total file size in bytes), `tell` (the
    * current cursor as a wasi `filesize`), and `seek` (absolute set —
    * whence math is done at the syscall layer because it doesn't depend
    * on the impl). Phase 7.F added `write` for the symmetric host-buffer-
    * source path; default impl returns 0 ("read-only file"), so existing
    * read-only impls compile unchanged. Real-FS impls in a future
    * sub-phase can plug in against the same trait without touching the
    * syscall layer. */
  trait FsFile:
    /** Release any host resources backing this handle. For the in-memory
      * test impl this is a no-op. Real-FS impls (a future
      * `java.nio.file`-backed `Preopen`) close their underlying handle
      * here. Return type is `Unit` for the slim surface — real-FS impls
      * that surface close errors can refactor to `Either[Int, Unit]`
      * later. */
    def close(): Unit

    /** Read up to `length` bytes from the current cursor into
      * `dst[offset .. offset + length)`. Returns the number of bytes
      * actually written (`0` = EOF, never negative). Advances the
      * cursor by the returned count. Impls must never read past
      * end-of-file: when `tell ≥ size` the call returns 0 cleanly. */
    def read(dst: Array[Byte], offset: Int, length: Int): Int

    /** Write up to `length` bytes from `src[offset .. offset + length)`
      * starting at the current cursor. Returns the number of bytes
      * actually consumed (`0` for a read-only impl; negative is never
      * legal). Advances the cursor by the returned count, growing the
      * file if the write extends past `size`. Default impl is `0` —
      * read-only [[FsFile]]s inherit the no-op without having to
      * override. */
    def write(src: Array[Byte], offset: Int, length: Int): Int = 0

    /** Total file size in bytes. May grow after a `write` past end. */
    def size: Long

    /** Current cursor position in bytes. `0` immediately after `open`. */
    def tell: Long

    /** Move the cursor to an absolute byte position. Caller (the
      * syscall layer) validates `pos ≥ 0`; POSIX (and wasi-preview1
      * by inheritance) allows `pos > size`, in which case a subsequent
      * `read` returns 0 without advancing further. A subsequent `write`
      * past end may grow the file (impl-defined; the InMemoryFs grows
      * and zero-fills the gap). */
    def seek(pos: Long): Unit

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
    // One fd table per HostModule (i.e. per instantiation). Lives in this
    // closure rather than on `WasiContext` so the context stays purely
    // descriptive (consistent with `Clock` and `random` being injection
    // points rather than mutable state). Each `Wasi.preview1(ctx)` call
    // gets its own fresh table; sharing a `WasiContext` across multiple
    // instantiations therefore gives each instance an isolated fd space.
    val fdTable = new FdTable(ctx.preopens.length)
    new HostModule:
      val name: String = "wasi_snapshot_preview1"
      val functions: Map[String, HostFunc] = Map(
        "fd_write"            -> ((mem, args) => fdWrite(mem, args, ctx, fdTable)),
        "fd_read"             -> ((mem, args) => fdRead(mem, args, ctx, fdTable)),
        "fd_close"            -> ((_,   args) => fdClose(args, ctx, fdTable)),
        "fd_seek"             -> ((mem, args) => fdSeek(mem, args, ctx, fdTable)),
        "fd_filestat_get"     -> ((mem, args) => fdFilestatGet(mem, args, ctx, fdTable)),
        "fd_fdstat_get"       -> ((mem, args) => fdFdstatGet(mem, args, ctx, fdTable)),
        "fd_fdstat_set_flags" -> ((_,   args) => fdFdstatSetFlags(args, ctx, fdTable)),
        "proc_exit"           -> ((_,   args) => procExit(args)),
        "args_sizes_get"      -> ((mem, args) => sizesGet(mem, args, argEntries(ctx))),
        "args_get"            -> ((mem, args) => entriesGet(mem, args, argEntries(ctx))),
        "environ_sizes_get"   -> ((mem, args) => sizesGet(mem, args, envEntries(ctx))),
        "environ_get"         -> ((mem, args) => entriesGet(mem, args, envEntries(ctx))),
        "clock_time_get"      -> ((mem, args) => clockTimeGet(mem, args, ctx)),
        "random_get"          -> ((mem, args) => randomGet(mem, args, ctx)),
        "fd_prestat_get"      -> ((mem, args) => prestatGet(mem, args, ctx)),
        "fd_prestat_dir_name" -> ((mem, args) => prestatDirName(mem, args, ctx)),
        "path_open"           -> ((mem, args) => pathOpen(mem, args, ctx, fdTable)),
        "path_filestat_get"     -> ((mem, args) => pathFilestatGet(mem, args, ctx)),
        "path_unlink_file"      -> ((mem, args) => pathUnlinkFile(mem, args, ctx)),
        "path_create_directory" -> ((mem, args) => pathCreateDirectory(mem, args, ctx)),
        "fd_readdir"            -> ((mem, args) => fdReaddir(mem, args, ctx, fdTable)),
        "fd_sync"               -> ((_,   args) => fdSync(args, ctx, fdTable)),
        "fd_datasync"           -> ((_,   args) => fdDatasync(args, ctx, fdTable)),
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
    * fd dispatch:
    *   - `fd 1 / fd 2` → `ctx.stdout` / `ctx.stderr` byte sink.
    *   - `fd 0`        → EBADF (stdin can't be written to).
    *   - `fd 3 .. 3 + N − 1` (preopens) → EBADF (directories aren't
    *     writable through `fd_write`; a future hardening pass could
    *     return `EISDIR` instead).
    *   - `fd ≥ 3 + N` (opened files) → routes to [[FsFile.write]] on
    *     the file backing the fd; EBADF if the slot is free.
    *
    * Errno discipline: out-of-bounds reads of any descriptor or buffer
    * return EFAULT (and DO NOT write `nwritten`). On a partial-success
    * scenario (where some iovecs landed in the sink but a later one is
    * bad) we still return EFAULT and don't stamp `nwritten` — wasi
    * callers treat any non-zero errno as authoritative and ignore the
    * `nwritten` slot in that case, so this choice is conservative but
    * spec-compatible. */
  private def fdWrite(memory: Memory, args: Seq[Value], ctx: WasiContext,
                      fdTable: FdTable): Seq[Value] =
    args match
      case Seq(I32(fd), I32(iovsPtr), I32(iovsLen), I32(nwrittenPtr)) =>
        // Pick the destination. Stdio gets a byte-sink callback; opened
        // files go through [[FsFile.write]] with the linear-memory slice
        // as the source. EBADF if neither matches — fd 0, preopen fds,
        // and unallocated table slots all land here.
        val stdio: Option[Int => Unit] = fd match
          case 1 => Some(ctx.stdout)
          case 2 => Some(ctx.stderr)
          case _ => None
        val entry: Option[FdEntry] = lookupFileEntry(fd, ctx, fdTable)
        val file:  Option[FsFile]  = entry.map(_.file)
        if stdio.isEmpty && file.isEmpty then return Seq(I32(EBADF))

        // APPEND semantics: before the very first iovec lands, seek to
        // end-of-file. POSIX `O_APPEND` is per-write atomic (the seek
        // and the write together can't be interleaved by another
        // writer) — single-threaded host satisfies that trivially.
        for e <- entry if (e.fdflags & FDFLAGS_APPEND) != 0 do
          e.file.seek(e.file.size)

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
          stdio match
            case Some(w) =>
              var j = 0
              while j < len do
                w(data(buf + j) & 0xff)
                j += 1
            case None =>
              // `file` is known non-empty here because the upfront
              // dispatch returned EBADF otherwise. `.get` is safe.
              file.get.write(data, buf, len)
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

  // === fd_read + fd_seek + fd_filestat_get (Phase 7.E.3) ====================
  //
  // The three remaining wasi-preview1 file syscalls a typical "read a file"
  // program reaches for after `path_open`. `fd_read` mirrors `fd_write` —
  // walk iovecs, but this time the file is the source and linear memory
  // the destination. `fd_seek` does the SET/CUR/END whence math at the
  // syscall layer (the impl just sees absolute positions via [[FsFile.seek]]).
  // `fd_filestat_get` writes the 64-byte `__wasi_filestat_t` struct.
  //
  // The fd→file lookup is shared: stdio and preopens are not [[FsFile]]s
  // (they're sinks/directories), so `fd_read` and `fd_seek` only resolve
  // fds in the [[FdTable]]. `fd_filestat_get` is different — it answers
  // for every valid fd in the system (stdio as CHARACTER_DEVICE, preopens
  // as DIRECTORY, opened files as REGULAR_FILE) because `fstat` on stdio
  // is part of any reasonable wasi-libc startup.

  /** Resolve `fd` to an [[FsFile]] for the read/seek syscalls. Returns
    * `None` for any fd in the stdio/preopen range (those aren't reading
    * surfaces at this slice) or any fd not currently allocated in the
    * fd table. `fd_filestat_get` does its own dispatch and does NOT use
    * this helper. */
  private inline def lookupFile(fd: Int, ctx: WasiContext,
                                fdTable: FdTable): Option[FsFile] =
    if fd < 3 + ctx.preopens.length then None else fdTable.lookup(fd)

  /** Same dispatch as [[lookupFile]] but returns the full [[FdEntry]]
    * so callers can read the per-fd flags (APPEND etc.). */
  private inline def lookupFileEntry(fd: Int, ctx: WasiContext,
                                     fdTable: FdTable): Option[FdEntry] =
    if fd < 3 + ctx.preopens.length then None else fdTable.lookupEntry(fd)

  /** `fd_read(fd: i32, iovs: i32, iovs_len: i32, nread: i32) -> errno`
    *
    * Walks the iovec table the same shape `fd_write` does (each entry is
    * an i32 buf pointer followed by an i32 length), but reads from the
    * [[FsFile]] backing `fd` and writes into linear memory at each
    * iovec's buffer. Stops at the first short read (`n < len`) — that's
    * EOF on the file side, and POSIX `readv` is documented to return
    * however many bytes it managed.
    *
    * Errno discipline:
    *   - `EBADF` if `fd` doesn't resolve to an opened file. stdio fds
    *     and preopen-directory fds aren't readable through this slice
    *     (a future stdin slice can lift fd 0 into the table).
    *   - `EFAULT` if the iovec table, any iovec buffer, or `nread`
    *     itself falls outside live memory. Validated up front so a
    *     bad `nread` doesn't strand the file with an advanced cursor
    *     and no way to report the count. */
  private def fdRead(memory: Memory, args: Seq[Value],
                     ctx: WasiContext, fdTable: FdTable): Seq[Value] =
    args match
      case Seq(I32(fd), I32(iovsPtr), I32(iovsLen), I32(nreadPtr)) =>
        lookupFile(fd, ctx, fdTable) match
          case None => Seq(I32(EBADF))
          case Some(file) =>
            val data    = memory.data
            val dataLen = data.length

            val tableEnd = iovsPtr.toLong + iovsLen.toLong * 8L
            if iovsPtr < 0 || iovsLen < 0 || tableEnd > dataLen then
              return Seq(I32(EFAULT))
            if nreadPtr < 0 || nreadPtr.toLong + 4L > dataLen then
              return Seq(I32(EFAULT))

            // Walk the iovec table. For each entry, bounds-check the
            // destination buffer before reading; the read itself may
            // come up short (EOF), at which point we stop walking —
            // matches POSIX `readv` semantics.
            var total = 0
            var i     = 0
            var done  = false
            while i < iovsLen && !done do
              val iovec = iovsPtr + i * 8
              val buf   = readI32LE(data, iovec)
              val len   = readI32LE(data, iovec + 4)
              val end   = buf.toLong + len.toLong
              if buf < 0 || len < 0 || end > dataLen then
                return Seq(I32(EFAULT))
              val n = file.read(data, buf, len)
              total += n
              if n < len then done = true
              i += 1
            writeI32LE(data, nreadPtr, total)
            Seq(I32(ESUCCESS))
      case _ => Seq(I32(EINVAL))

  /** `fd_seek(fd: i32, offset: i64, whence: i32, newoffset: i32) -> errno`
    *
    * Move the cursor. wasi-preview1's `whence` matches POSIX:
    *   - 0 SET — `newpos = offset`
    *   - 1 CUR — `newpos = tell + offset`
    *   - 2 END — `newpos = size + offset`
    * Any other value returns `EINVAL`. The math is done with `Long`
    * arithmetic at the syscall layer (so `FsFile` impls don't all have
    * to reimplement it), and a resulting negative position rejects with
    * `EINVAL` — POSIX `lseek` semantics. POSITIVE past-end positions
    * are accepted; subsequent reads then return 0. */
  private def fdSeek(memory: Memory, args: Seq[Value],
                     ctx: WasiContext, fdTable: FdTable): Seq[Value] =
    args match
      case Seq(I32(fd), I64(offset), I32(whence), I32(newOffsetPtr)) =>
        lookupFile(fd, ctx, fdTable) match
          case None => Seq(I32(EBADF))
          case Some(file) =>
            val data    = memory.data
            val dataLen = data.length
            if newOffsetPtr < 0 || newOffsetPtr.toLong + 8L > dataLen then
              return Seq(I32(EFAULT))
            val newPos: Long = whence match
              case 0 => offset
              case 1 => file.tell + offset
              case 2 => file.size + offset
              case _ => return Seq(I32(EINVAL))
            if newPos < 0 then return Seq(I32(EINVAL))
            file.seek(newPos)
            writeI64LE(data, newOffsetPtr, newPos)
            Seq(I32(ESUCCESS))
      case _ => Seq(I32(EINVAL))

  /** `fd_filestat_get(fd: i32, buf: i32) -> errno`
    *
    * Writes the 64-byte `__wasi_filestat_t` struct at `buf`. Layout
    * (witx canonical, 8-byte-aligned fields):
    *
    *   off  0 : u64 dev      — device id (0 — single-device shim)
    *   off  8 : u64 ino      — inode (0 — wasi-libc tolerates this)
    *   off 16 : u8  filetype — see dispatch below; bytes 17..23 padding
    *   off 24 : u64 nlink    — hard-link count (always 1 here)
    *   off 32 : u64 size     — file size in bytes
    *   off 40 : u64 atim     — last-access time, ns (0 here)
    *   off 48 : u64 mtim     — last-modify time, ns (0 here)
    *   off 56 : u64 ctim     — last-status-change time, ns (0 here)
    *
    * fd dispatch:
    *   - `fd 0/1/2`               → CHARACTER_DEVICE (2), size 0
    *   - `fd 3 .. 3 + N − 1`      → DIRECTORY        (3), size 0
    *   - `fd ≥ 3 + N` (fd table)  → REGULAR_FILE     (4), size = file.size
    *   - else                     → EBADF
    *
    * The struct is zeroed first so reserved/zero fields don't carry
    * whatever the program last wrote at this address. */
  private def fdFilestatGet(memory: Memory, args: Seq[Value],
                            ctx: WasiContext, fdTable: FdTable): Seq[Value] =
    args match
      case Seq(I32(fd), I32(bufPtr)) =>
        val data    = memory.data
        val dataLen = data.length
        if bufPtr < 0 || bufPtr.toLong + 64L > dataLen then
          return Seq(I32(EFAULT))

        // Filetype + size dispatch first — we need this before touching
        // memory so EBADF stays atomic (no half-written struct on error).
        var filetype: Byte = 0
        var fileSize: Long = 0L
        if fd < 0 then return Seq(I32(EBADF))
        else if fd <= 2 then
          filetype = 2       // CHARACTER_DEVICE — stdio
        else if fd - 3 < ctx.preopens.length then
          filetype = 3       // DIRECTORY — preopen
        else
          fdTable.lookup(fd) match
            case Some(file) =>
              filetype = 4   // REGULAR_FILE — opened file
              fileSize = file.size
            case None => return Seq(I32(EBADF))

        // Zero the whole struct, then stamp the four fields that
        // actually carry information. Padding bytes 17..23 stay zero
        // because we zeroed first.
        var i = 0
        while i < 64 do
          data(bufPtr + i) = 0
          i += 1
        data(bufPtr + 16) = filetype
        writeI64LE(data, bufPtr + 24, 1L)        // nlink
        writeI64LE(data, bufPtr + 32, fileSize)  // size
        Seq(I32(ESUCCESS))
      case _ => Seq(I32(EINVAL))

  /** `fd_fdstat_get(fd: i32, buf: i32) -> errno`
    *
    * Writes the 24-byte `__wasi_fdstat_t` struct at `buf`. wasi-libc
    * (and therefore Rust's `std::fs`) hits this immediately after
    * `path_open` to learn whether the new fd supports seek/tell, what
    * flags it carries, and how broad its rights mask is. Layout:
    *
    *   off  0 : u8  fs_filetype           — same dispatch as fd_filestat_get
    *   off  1 : padding (1 byte)
    *   off  2 : u16 fs_flags              — append / nonblock / sync (all 0 here)
    *   off  4 : padding (4 bytes)
    *   off  8 : u64 fs_rights_base        — current rights bitmask
    *   off 16 : u64 fs_rights_inheriting  — rights inheritable to children
    *
    * fd dispatch matches `fd_filestat_get`:
    *   - `fd 0/1/2`               → CHARACTER_DEVICE (2)
    *   - `fd 3 .. 3 + N − 1`      → DIRECTORY        (3)
    *   - `fd ≥ 3 + N` (fd table)  → REGULAR_FILE     (4)
    *   - else                     → EBADF
    *
    * `fs_flags` stays 0: the shim's [[FsFile]] surface has no APPEND /
    * NONBLOCK / SYNC modes wired through yet. Rights are tightened to
    * the per-filetype masks ([[RIGHTS_CHARACTER_DEVICE]] for stdio,
    * [[RIGHTS_DIRECTORY_BASE]] / [[RIGHTS_DIRECTORY_INHERITING]] for
    * preopens, [[RIGHTS_REGULAR_FILE]] for opened files) so userspace
    * gating against bitwise-anded rights matches what the syscalls
    * actually support. Stdio in particular advertises NO seek/tell
    * capability — wasi-libc uses the absence of `FD_SEEK` to skip
    * `lseek` on stdin, which a previous "full mask" let it incorrectly
    * call. */
  private def fdFdstatGet(memory: Memory, args: Seq[Value],
                          ctx: WasiContext, fdTable: FdTable): Seq[Value] =
    args match
      case Seq(I32(fd), I32(bufPtr)) =>
        val data    = memory.data
        val dataLen = data.length
        if bufPtr < 0 || bufPtr.toLong + 24L > dataLen then
          return Seq(I32(EFAULT))

        // Same EBADF-atomic discipline as fd_filestat_get: resolve the
        // filetype, rights, and fs_flags before touching `buf` so a bad
        // fd leaves the destination untouched.
        var filetype:   Byte = 0
        var rights:     Long = 0L
        var inheriting: Long = 0L
        var fsFlags:    Int  = 0
        if fd < 0 then return Seq(I32(EBADF))
        else if fd <= 2 then
          filetype   = 2                          // CHARACTER_DEVICE — stdio
          rights     = RIGHTS_CHARACTER_DEVICE
          inheriting = 0L                         // stdio has no children
        else if fd - 3 < ctx.preopens.length then
          filetype   = 3                          // DIRECTORY — preopen
          rights     = RIGHTS_DIRECTORY_BASE
          inheriting = RIGHTS_DIRECTORY_INHERITING
        else
          fdTable.lookupEntry(fd) match
            case Some(entry) =>
              filetype   = 4                      // REGULAR_FILE — opened file
              rights     = RIGHTS_REGULAR_FILE
              inheriting = 0L                     // files don't open children
              fsFlags    = entry.fdflags
            case None => return Seq(I32(EBADF))

        var i = 0
        while i < 24 do
          data(bufPtr + i) = 0
          i += 1
        data(bufPtr + 0) = filetype
        // fs_flags @ 2..3 — per-fd flags (APPEND/DSYNC/NONBLOCK/RSYNC/SYNC).
        // The high byte is currently always 0 (all defined bits live in
        // the low 5).
        data(bufPtr + 2) = (fsFlags & 0xff).toByte
        data(bufPtr + 3) = ((fsFlags >>> 8) & 0xff).toByte
        writeI64LE(data, bufPtr + 8,  rights)
        writeI64LE(data, bufPtr + 16, inheriting)
        Seq(I32(ESUCCESS))
      case _ => Seq(I32(EINVAL))

  /** `fd_fdstat_set_flags(fd: i32, flags: i32) -> errno`
    *
    * Update the per-fd `fdflags` bitmap. Only [[FDFLAGS_APPEND]]
    * currently has a behavioural effect (next write seeks to end
    * first); the other defined bits are stored and round-trip through
    * `fd_fdstat_get`.
    *
    * fd dispatch:
    *   - `fd 0/1/2` — accepted as a no-op success. wasi-libc flips
    *     `O_NONBLOCK` on stdio during line-buffered input; our shim
    *     has no blocking model so silently accepting is the right
    *     answer. Userspace can re-read the flags via `fd_fdstat_get`
    *     and will see them unchanged (still 0).
    *   - preopen-dir fds → EBADF (directories don't carry per-fd
    *     flags in our model; a future hardening pass could broaden).
    *   - FdTable entries → update `entry.fdflags`. Any bits not
    *     defined by wasi-preview1 are silently kept (we don't reject
    *     them — uvwasi does the same).
    *   - unknown fd → EBADF. */
  private def fdFdstatSetFlags(args: Seq[Value], ctx: WasiContext,
                               fdTable: FdTable): Seq[Value] =
    args match
      case Seq(I32(fd), I32(flags)) =>
        if fd < 0 then Seq(I32(EBADF))
        else if fd <= 2 then Seq(I32(ESUCCESS))
        else if fd - 3 < ctx.preopens.length then Seq(I32(EBADF))
        else
          fdTable.lookupEntry(fd) match
            case Some(entry) =>
              entry.fdflags = flags
              Seq(I32(ESUCCESS))
            case None => Seq(I32(EBADF))
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
    * Releases a file descriptor. The fd space is partitioned three ways:
    *
    *   - **fd 0 / 1 / 2** (stdin / stdout / stderr) — `ESUCCESS` no-op.
    *     Userspace stdio closes are benign; a wasi program that closes
    *     all three on shutdown shouldn't break on us.
    *   - **fd 3 .. 3 + N − 1** (preopens, where N = `ctx.preopens.length`)
    *     — `ESUCCESS` no-op. The preopen stays addressable for any later
    *     `fd_prestat_get` / `path_open` call. POSIX semantics say closing
    *     a valid fd succeeds; preopens are static for the lifetime of
    *     this `WasiContext` so we don't actually release anything.
    *   - **fd ≥ 3 + N** (path_open results) — looked up in [[FdTable]].
    *     A hit calls the [[FsFile]]'s `close()`, releases the slot, and
    *     returns `ESUCCESS`. A miss (already-closed or never-opened fd)
    *     returns `EBADF`.
    *
    * Negative fds and any non-(i32) arg shape return `EBADF` / `EINVAL`
    * respectively. */
  private def fdClose(args: Seq[Value], ctx: WasiContext,
                      fdTable: FdTable): Seq[Value] =
    args match
      case Seq(I32(fd)) =>
        if fd < 0 then Seq(I32(EBADF))
        else if fd <= 2 then Seq(I32(ESUCCESS))
        else if fd - 3 < ctx.preopens.length then Seq(I32(ESUCCESS))
        else
          fdTable.lookup(fd) match
            case Some(file) =>
              file.close()
              fdTable.release(fd)
              Seq(I32(ESUCCESS))
            case None => Seq(I32(EBADF))
      case _ => Seq(I32(EINVAL))

  // === fd_sync / fd_datasync ================================================
  //
  // POSIX `fsync(2)` / `fdatasync(2)` flush buffered writes to durable
  // storage. The InMemoryFs has no separate "buffer" — every `write`
  // updates the live `FileCell.bytes` array immediately — so sync is a
  // no-op success. We still validate the fd so a bug in userspace (e.g.
  // syncing a closed fd) surfaces as EBADF rather than silently
  // succeeding. A future host-backed FsFile impl can override these to
  // forward to the platform's real sync syscalls.

  /** `fd_sync(fd: i32) -> errno`
    *
    * Flush any buffered writes for `fd`. In the InMemoryFs that's a
    * no-op (every `write` mutates the cell directly), so we return
    * `ESUCCESS` for any valid fd. EBADF for unknown fds — same dispatch
    * partition as `fd_close` (stdio + preopens + FdTable). */
  private def fdSync(args: Seq[Value], ctx: WasiContext,
                     fdTable: FdTable): Seq[Value] =
    args match
      case Seq(I32(fd)) =>
        if isValidFd(fd, ctx, fdTable) then Seq(I32(ESUCCESS))
        else Seq(I32(EBADF))
      case _ => Seq(I32(EINVAL))

  /** `fd_datasync(fd: i32) -> errno`
    *
    * POSIX `fdatasync` — flushes data without necessarily flushing
    * metadata. In our model there's no distinction between data and
    * metadata flushes (no atime/mtime tracking yet), so this is the
    * same code path as `fd_sync`. */
  private def fdDatasync(args: Seq[Value], ctx: WasiContext,
                         fdTable: FdTable): Seq[Value] = fdSync(args, ctx, fdTable)

  /** Is `fd` claimed by either stdio, a preopen, or the FdTable? Used
    * by `fd_sync` / `fd_datasync` to partition EBADF from ESUCCESS
    * without caring which class the fd falls into. Mirrors the dispatch
    * tree in `fd_close`. */
  private inline def isValidFd(fd: Int, ctx: WasiContext,
                               fdTable: FdTable): Boolean =
    if fd < 0 then false
    else if fd <= 2 then true
    else if fd - 3 < ctx.preopens.length then true
    else fdTable.lookup(fd).isDefined

  // === path_filestat_get ====================================================

  /** `path_filestat_get(fd: i32, lookupflags: i32, path_ptr: i32,
    *                    path_len: i32, buf: i32) -> errno`
    *
    * Mirrors `fd_filestat_get`'s output shape — writes the same 64-byte
    * `__wasi_filestat_t` at `buf` — but resolves a path relative to a
    * preopen `fd` rather than statting an already-open fd. wasi-libc
    * uses this for the back end of `stat(path)` / `lstat(path)` /
    * `access(path)` calls.
    *
    * Errno discipline:
    *   - `EBADF` if `fd` isn't a preopen (the only kind of fd that
    *     accepts path-resolved lookups in the shim).
    *   - `EFAULT` if the path bytes or `buf+64` fall outside live
    *     linear memory. Validated up front so a bad pointer doesn't
    *     leave the destination half-written.
    *   - `ENOENT` if the path doesn't exist in the preopen.
    *   - `ENOTCAPABLE` if the preopen has no FS capability (i.e. it's
    *     a `Preopen.named(...)` rather than an in-memory or host-backed
    *     impl).
    *
    * `lookupflags` (bit 0 = SYMLINK_FOLLOW) is ignored — the InMemoryFs
    * has no symlinks, so `stat` and `lstat` are equivalent.
    *
    * Implementation note: leverages [[WasiContext.Preopen.statPath]] —
    * a path-keyed companion to `open` that returns either `Left(errno)`
    * or `Right(size: Long)`. We need size only (filetype is always
    * REGULAR_FILE in the InMemoryFs's flat path model). A future
    * directory-aware impl would broaden the return to include a
    * filetype byte. */
  private def pathFilestatGet(memory: Memory, args: Seq[Value],
                              ctx: WasiContext): Seq[Value] =
    args match
      case Seq(I32(fd), I32(_lookupflags), I32(pathPtr), I32(pathLen),
               I32(bufPtr)) =>
        val idx = fd - 3
        if idx < 0 || idx >= ctx.preopens.length then
          return Seq(I32(EBADF))
        val data    = memory.data
        val dataLen = data.length
        if pathPtr < 0 || pathLen < 0 ||
           pathPtr.toLong + pathLen.toLong > dataLen then
          return Seq(I32(EFAULT))
        if bufPtr < 0 || bufPtr.toLong + 64L > dataLen then
          return Seq(I32(EFAULT))

        val pathBytes = new Array[Byte](pathLen)
        System.arraycopy(data, pathPtr, pathBytes, 0, pathLen)
        val path = new String(pathBytes, "UTF-8")

        ctx.preopens(idx).statPath(path) match
          case Left(errno) => Seq(I32(errno))
          case Right(size) =>
            var i = 0
            while i < 64 do
              data(bufPtr + i) = 0
              i += 1
            // Filetype: ask the preopen — InMemoryPreopen knows whether
            // the path is a FileEntry or DirEntry. Default-trait preopens
            // can't reach this branch (statPath would have returned
            // ENOTCAPABLE).
            val ft: Byte = filetypeFor(ctx.preopens(idx), path)
            data(bufPtr + 16) = ft
            writeI64LE(data, bufPtr + 24, 1L)      // nlink
            writeI64LE(data, bufPtr + 32, size)    // size
            Seq(I32(ESUCCESS))
      case _ => Seq(I32(EINVAL))

  /** Resolve a preopen's filetype byte for `path`. Delegates to the
    * polymorphic [[WasiContext.Preopen.filetypeOf]] hook and falls back
    * to REGULAR_FILE (4) when the preopen returns `None` — preopens that
    * can only resolve files via `statPath` (e.g. a hypothetical
    * read-only-file-only impl) inherit the fallback honestly. */
  private inline def filetypeFor(preopen: WasiContext.Preopen,
                                 path:    String): Byte =
    preopen.filetypeOf(path).getOrElse(4: Byte)

  // === path_unlink_file =====================================================

  /** `path_unlink_file(fd: i32, path_ptr: i32, path_len: i32) -> errno`
    *
    * Remove the regular file at `path` (resolved against the preopen
    * directory `fd`). Returns `ESUCCESS` on success, `ENOENT` if the
    * path doesn't exist, `ENOTCAPABLE` for a `Preopen.named` (no FS
    * backing), `EBADF` if `fd` isn't a preopen-dir, or `EFAULT` if the
    * path bytes fall outside live memory.
    *
    * No `lookupflags` arg in wasi-preview1 (the syscall is always
    * lstat-style — symlinks are removed, not followed). The InMemoryFs
    * doesn't model symlinks anyway, so the distinction is moot.
    *
    * Any open file handles against the unlinked path keep their own
    * [[WasiContext.Preopen.InMemoryPreopen.FileCell]] reference — the
    * cell is only fully dropped when both the map entry is gone AND
    * all handles closed. This matches POSIX `unlink(2)` semantics
    * where open fds keep working against the now-anonymous inode. */
  private def pathUnlinkFile(memory: Memory, args: Seq[Value],
                             ctx: WasiContext): Seq[Value] =
    args match
      case Seq(I32(fd), I32(pathPtr), I32(pathLen)) =>
        val idx = fd - 3
        if idx < 0 || idx >= ctx.preopens.length then
          return Seq(I32(EBADF))
        val data    = memory.data
        val dataLen = data.length
        if pathPtr < 0 || pathLen < 0 ||
           pathPtr.toLong + pathLen.toLong > dataLen then
          return Seq(I32(EFAULT))
        val pathBytes = new Array[Byte](pathLen)
        System.arraycopy(data, pathPtr, pathBytes, 0, pathLen)
        val path = new String(pathBytes, "UTF-8")
        ctx.preopens(idx).unlinkPath(path) match
          case Right(_)    => Seq(I32(ESUCCESS))
          case Left(errno) => Seq(I32(errno))
      case _ => Seq(I32(EINVAL))

  // === path_create_directory ================================================

  /** `path_create_directory(fd: i32, path_ptr: i32, path_len: i32) -> errno`
    *
    * Add a directory entry at `path` (resolved against the preopen
    * directory `fd`). Returns `ESUCCESS` on success, `EEXIST` if a
    * file or directory already exists at that path, `ENOTCAPABLE` for
    * a non-capability preopen, `EBADF` if `fd` isn't a preopen, or
    * `EFAULT` on a bad path pointer.
    *
    * The flat-path model means a created directory has no "children"
    * yet — entries created at unrelated paths don't automatically nest
    * inside it. `fd_readdir` lists every entry in the preopen
    * regardless. */
  private def pathCreateDirectory(memory: Memory, args: Seq[Value],
                                  ctx: WasiContext): Seq[Value] =
    args match
      case Seq(I32(fd), I32(pathPtr), I32(pathLen)) =>
        val idx = fd - 3
        if idx < 0 || idx >= ctx.preopens.length then
          return Seq(I32(EBADF))
        val data    = memory.data
        val dataLen = data.length
        if pathPtr < 0 || pathLen < 0 ||
           pathPtr.toLong + pathLen.toLong > dataLen then
          return Seq(I32(EFAULT))
        val pathBytes = new Array[Byte](pathLen)
        System.arraycopy(data, pathPtr, pathBytes, 0, pathLen)
        val path = new String(pathBytes, "UTF-8")
        ctx.preopens(idx).mkdir(path) match
          case Right(_)    => Seq(I32(ESUCCESS))
          case Left(errno) => Seq(I32(errno))
      case _ => Seq(I32(EINVAL))

  // === fd_readdir ===========================================================

  /** Size of a serialised wasi `__wasi_dirent_t` header (everything
    * except the name bytes that follow). Layout:
    *
    *   off  0 : u64 d_next   (cookie of NEXT entry)
    *   off  8 : u64 d_ino    (inode — index in our flat model)
    *   off 16 : u32 d_namlen (length of the name bytes that follow)
    *   off 20 : u8  d_type   (filetype)
    *   off 21 : padding (3 bytes; matches uvwasi's 24-byte header)
    *
    * Followed immediately by `d_namlen` UTF-8 bytes of the entry name
    * (NOT NUL-terminated). The next dirent header starts at
    * `header + namlen`. uvwasi+wasmtime both use this layout. */
  private final val DIRENT_HEADER_SIZE: Int = 24

  /** `fd_readdir(fd: i32, buf: i32, buf_len: i32, cookie: i64,
    *             bufused_out: i32) -> errno`
    *
    * Stream directory entries into `buf` up to `buf_len` bytes. Each
    * entry is a 24-byte header followed by `d_namlen` bytes of name.
    * `cookie` is a u64 stream position: 0 starts at the first entry;
    * a non-zero cookie picks up at the entry whose `d_next` was that
    * value. The host writes the total bytes consumed at `bufused_out`.
    *
    * When the buffer fills mid-entry, the host writes as much of the
    * header+name as fits and stops; userspace re-invokes with the
    * cookie of the last fully-written entry. When the stream is
    * exhausted, `bufused < buf_len` signals end-of-stream — userspace
    * keeps calling with the highest cookie it saw until the host
    * returns a partial buffer.
    *
    * fd dispatch:
    *   - `fd 0/1/2` (stdio) → ENOTDIR
    *   - `fd 3 .. 3+N-1` (preopens) → list entries via preopen.readdir
    *   - `fd ≥ 3+N` (opened files) → ENOTDIR (no dir-fds in FdTable
    *     beyond preopens at this slice)
    *   - else → EBADF
    *
    * Errno discipline: EFAULT if `buf+buf_len` or `bufused_out+4` fall
    * outside live memory; ENOTDIR for a non-directory fd; EBADF for an
    * unknown fd. */
  private def fdReaddir(memory: Memory, args: Seq[Value],
                        ctx: WasiContext, fdTable: FdTable): Seq[Value] =
    args match
      case Seq(I32(fd), I32(bufPtr), I32(bufLen), I64(cookie), I32(bufusedPtr)) =>
        val data    = memory.data
        val dataLen = data.length
        if bufPtr     < 0 || bufLen < 0 ||
           bufPtr.toLong + bufLen.toLong > dataLen then
          return Seq(I32(EFAULT))
        if bufusedPtr < 0 || bufusedPtr.toLong + 4L > dataLen then
          return Seq(I32(EFAULT))

        // fd dispatch.
        if fd < 0 then return Seq(I32(EBADF))
        if fd <= 2 then return Seq(I32(ENOTDIR))
        val idx = fd - 3
        if idx < 0 || idx >= ctx.preopens.length then
          // Past the preopen range — FdTable opened-file fds are not
          // directories (only preopen-dirs are listable in this slice).
          if fdTable.lookup(fd).isDefined then return Seq(I32(ENOTDIR))
          else return Seq(I32(EBADF))

        // Collect the entry list. cookie==0 starts at the first entry;
        // cookie==k starts at the (k+1)th entry (the entry whose d_next
        // is k+1 was the last one fully written by the previous call).
        // We use index-as-cookie for simplicity; entries are 1-indexed
        // in the cookie space to leave 0 reserved for "start".
        val entries = ctx.preopens(idx).readdir
        var written = 0
        var i       = cookie.toInt
        var stopped = false
        while !stopped && i < entries.length do
          val (name, ft, ino) = entries(i)
          val nameBytes = name.getBytes("UTF-8")
          val namlen    = nameBytes.length
          val totalSize = DIRENT_HEADER_SIZE + namlen
          val remaining = bufLen - written
          if remaining <= 0 then
            stopped = true
          else
            // Even when the entry doesn't fully fit, we still write what
            // we can — userspace observes `bufused < bufLen` and knows
            // to allocate a larger buffer.
            val dirNext: Long = (i + 1).toLong   // cookie of NEXT entry
            // Header — up to 24 bytes of it.
            val headerEnd = math.min(DIRENT_HEADER_SIZE, remaining)
            writeDirentHeader(data, bufPtr + written, dirNext, ino, namlen, ft)
            // Zero out anything we don't naturally write (padding bytes
            // 21..23). writeDirentHeader handles that itself.
            // Decide how many of the 24 header bytes "land" in the
            // user buffer.
            val headerInBuf = math.min(DIRENT_HEADER_SIZE, remaining)
            // Name bytes.
            val nameRemaining = remaining - headerInBuf
            val nameLanding   = math.min(namlen, nameRemaining)
            if nameLanding > 0 then
              System.arraycopy(nameBytes, 0,
                               data, bufPtr + written + DIRENT_HEADER_SIZE,
                               nameLanding)
            // Advance the write cursor by what we attempted (header +
            // name's full length), not what landed — userspace uses
            // bufused vs bufLen to detect the truncation.
            written += totalSize
            if written >= bufLen then stopped = true
          i += 1

        // Cap at bufLen — if we wrote past it (last entry truncated),
        // bufused is bufLen.
        val bufused = math.min(written, bufLen)
        writeI32LE(data, bufusedPtr, bufused)
        Seq(I32(ESUCCESS))
      case _ => Seq(I32(EINVAL))

  /** Write up to 24 bytes of a dirent header at `data[offset]`. Bytes
    * past `data.length` are dropped — the caller has already bounds-
    * checked the dirent's expected start byte against `bufLen`; this
    * helper only ever truncates against the physical array end as a
    * belt-and-suspenders guard. The 3 padding bytes after `d_type`
    * stay zero (the buf array is fresh). */
  private def writeDirentHeader(data:    Array[Byte],
                                offset:  Int,
                                dNext:   Long,
                                dIno:    Long,
                                dNamlen: Int,
                                dType:   Byte): Unit =
    val dataLen = data.length
    if offset < dataLen then
      val end = math.min(offset + DIRENT_HEADER_SIZE, dataLen)
      val buf = new Array[Byte](DIRENT_HEADER_SIZE)
      // d_next @ 0..7
      buf(0) = (dNext         & 0xff).toByte
      buf(1) = ((dNext >>>  8)& 0xff).toByte
      buf(2) = ((dNext >>> 16)& 0xff).toByte
      buf(3) = ((dNext >>> 24)& 0xff).toByte
      buf(4) = ((dNext >>> 32)& 0xff).toByte
      buf(5) = ((dNext >>> 40)& 0xff).toByte
      buf(6) = ((dNext >>> 48)& 0xff).toByte
      buf(7) = ((dNext >>> 56)& 0xff).toByte
      // d_ino @ 8..15
      buf(8)  = (dIno         & 0xff).toByte
      buf(9)  = ((dIno >>>  8)& 0xff).toByte
      buf(10) = ((dIno >>> 16)& 0xff).toByte
      buf(11) = ((dIno >>> 24)& 0xff).toByte
      buf(12) = ((dIno >>> 32)& 0xff).toByte
      buf(13) = ((dIno >>> 40)& 0xff).toByte
      buf(14) = ((dIno >>> 48)& 0xff).toByte
      buf(15) = ((dIno >>> 56)& 0xff).toByte
      // d_namlen @ 16..19
      buf(16) = (dNamlen          & 0xff).toByte
      buf(17) = ((dNamlen >>>  8) & 0xff).toByte
      buf(18) = ((dNamlen >>> 16) & 0xff).toByte
      buf(19) = ((dNamlen >>> 24) & 0xff).toByte
      // d_type @ 20, padding @ 21..23 (already zero)
      buf(20) = dType
      val n = end - offset
      System.arraycopy(buf, 0, data, offset, n)

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

  // === path_open + fd table (Phase 7.E.2) ===================================
  //
  // `path_open` resolves a path relative to a preopen and allocates a new
  // fd backed by an [[FsFile]]. The fd table lives in the closure that
  // `Wasi.preview1` builds (one table per HostModule, i.e. per
  // instantiation). Allocation policy is smallest-free, matching what
  // POSIX programs expect — `open` always returns the smallest unused
  // descriptor.

  /** `path_open(dirfd, dirflags, path_ptr, path_len, oflags, rights_base,
    *           rights_inheriting, fdflags, opened_fd_out) -> errno`
    *
    * Nine args. The first is the directory fd to resolve against, which
    * for 7.E.2 must be a preopen (`3 .. 3 + N − 1`). `dirflags` is the
    * lookup-flag bitmask (bit 0 = SYMLINK_FOLLOW) and is ignored at this
    * slice — the InMemoryFs has no symlinks. The two rights i64s are
    * ignored too: real-fs impls in 7.E.3+ can enforce them, but at this
    * slice the test harness has no notion of capability erosion.
    *
    * Errno order: EBADF when `dirfd` isn't a preopen (we need this
    * before validating memory because we can't dispatch a bad dirfd
    * anywhere); EFAULT when the path bytes or the `opened_fd_out`
    * output i32 fall outside linear memory; then the per-preopen `open`
    * result (typically `Right(file)` → ESUCCESS + write fd, or
    * `Left(errno)` for ENOENT / ENOTCAPABLE / …).
    *
    * Paths are decoded as UTF-8. Empty paths are passed through to the
    * preopen impl, which is expected to return ENOENT (no such file)
    * rather than special-casing — keeps the test surface honest. */
  private def pathOpen(memory: Memory, args: Seq[Value],
                       ctx: WasiContext, fdTable: FdTable): Seq[Value] =
    args match
      case Seq(I32(dirfd), I32(_dirflags), I32(pathPtr), I32(pathLen),
               I32(oflags), _, _ /* rights i64s, ignored */, I32(fdflags),
               I32(openedFdOut)) =>
        val idx = dirfd - 3
        if idx < 0 || idx >= ctx.preopens.length then
          return Seq(I32(EBADF))

        val data    = memory.data
        val dataLen = data.length
        if pathPtr < 0 || pathLen < 0 ||
           pathPtr.toLong + pathLen.toLong > dataLen then
          return Seq(I32(EFAULT))
        if openedFdOut < 0 || openedFdOut.toLong + 4L > dataLen then
          return Seq(I32(EFAULT))

        val pathBytes = new Array[Byte](pathLen)
        System.arraycopy(data, pathPtr, pathBytes, 0, pathLen)
        val path = new String(pathBytes, "UTF-8")

        ctx.preopens(idx).open(path, oflags, fdflags) match
          case Right(file) =>
            // Stash the caller-requested fdflags on the fd table entry.
            // APPEND (bit 0) is what enforces "every write seeks to end
            // first" in fd_write; the other bits are accepted but
            // currently inert.
            val fd = fdTable.alloc(file, fdflags)
            writeI32LE(data, openedFdOut, fd)
            Seq(I32(ESUCCESS))
          case Left(errno) => Seq(I32(errno))

      case _ => Seq(I32(EINVAL))

  /** Per-fd entry in the [[FdTable]]: the open file handle plus the
    * `fdflags` bitmap (APPEND / NONBLOCK / SYNC). `fdflags` is `var`
    * so `fd_fdstat_set_flags` can update it without reallocating the
    * slot. Only [[FDFLAGS_APPEND]] currently has a behavioural effect
    * in the shim — APPEND seeks to end before every write — but the
    * other bits are stored and reported back through `fd_fdstat_get`
    * for userspace round-tripping. */
  private[wasi] final class FdEntry(val file: FsFile, var fdflags: Int)

  // === FDFLAGS bits (wasi-preview1) =========================================
  //
  // Each bit is a per-fd flag controlling read/write semantics.
  // wasi-libc translates POSIX O_APPEND / O_NONBLOCK / O_DSYNC / O_RSYNC
  // / O_SYNC into these. Only APPEND has an enforced behaviour today:
  // a write through an APPEND-flagged fd unconditionally seeks to end
  // first. NONBLOCK / DSYNC / RSYNC / SYNC are accepted, stored, and
  // round-tripped through `fd_fdstat_get`, but have no other effect
  // (InMemoryFs has no buffering, no blocking, no separate sync layer).

  /** Bit 0: every write seeks to end-of-file first. */
  private[wasi] val FDFLAGS_APPEND:   Int = 1 << 0
  private[wasi] val FDFLAGS_DSYNC:    Int = 1 << 1
  private[wasi] val FDFLAGS_NONBLOCK: Int = 1 << 2
  private[wasi] val FDFLAGS_RSYNC:    Int = 1 << 3
  private[wasi] val FDFLAGS_SYNC:     Int = 1 << 4

  /** Per-instantiation table of opened-file fds. Allocates monotonically
    * from `3 + preopens.length` and reuses the smallest free slot after
    * a `release`. Not thread-safe — the interpreter is single-threaded,
    * and host calls run synchronously on the same thread, so a mutable
    * `ArrayBuffer[Option[FdEntry]]` is the right shape.
    *
    * Slot semantics: `None` = free, `Some(entry)` = owned. We don't
    * track a separate "high-water mark" because growing the buffer is
    * O(1) amortised and the array stays small in practice (a typical
    * wasi program holds a handful of fds, not thousands). */
  private final class FdTable(preopenCount: Int):
    import scala.collection.mutable.ArrayBuffer
    private val baseFd: Int                       = 3 + preopenCount
    private val slots:  ArrayBuffer[Option[FdEntry]] = ArrayBuffer.empty

    /** Allocate a new fd backed by `file` with initial `fdflags`.
      * Returns the smallest free fd at or above `baseFd`. Grows the
      * table by one slot if every existing slot is occupied. */
    def alloc(file: FsFile, fdflags: Int = 0): Int =
      val entry = new FdEntry(file, fdflags)
      var i = 0
      while i < slots.length do
        if slots(i).isEmpty then
          slots(i) = Some(entry)
          return baseFd + i
        i += 1
      slots += Some(entry)
      baseFd + slots.length - 1

    /** Look up the [[FdEntry]] for `fd`. `None` for any fd below
      * `baseFd`, above the high-water mark, or in a released slot. */
    def lookupEntry(fd: Int): Option[FdEntry] =
      val i = fd - baseFd
      if i >= 0 && i < slots.length then slots(i) else None

    /** Look up the [[FsFile]] for `fd` — convenience wrapper around
      * [[lookupEntry]] that drops the flags. Kept for callers that
      * don't care about the per-fd state. */
    def lookup(fd: Int): Option[FsFile] = lookupEntry(fd).map(_.file)

    /** Release a fd's slot. `true` if it was actually occupied, `false`
      * if `fd` was out of range or already released. */
    def release(fd: Int): Boolean =
      val i = fd - baseFd
      if i >= 0 && i < slots.length && slots(i).nonEmpty then
        slots(i) = None
        true
      else false

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
    * Phase 7.E.2 extends the trait with [[open]], which `path_open`
    * dispatches to. The default impl returns `Left(Wasi.ENOTCAPABLE)`,
    * so the [[named]] factory keeps working as a "name-only" preopen
    * (advertises a directory, refuses to open anything inside it). The
    * [[inMemory]] factory returns a Preopen backed by an in-memory
    * `Map[String, Array[Byte]]`, which is what tests use.
    *
    * `name` is interpreted as UTF-8 — `fd_prestat_get`'s `pr_name_len`
    * and `fd_prestat_dir_name`'s output buffer both deal in bytes,
    * not chars, so non-ASCII preopen names cross the host/wasm
    * boundary without surprise. */
  trait Preopen:
    def name: String

    /** Open a path relative to this preopen and return an [[Wasi.FsFile]]
      * handle, or a wasi errno on failure. Called from `path_open` after
      * argument validation (the preopen never needs to bounds-check
      * pointers — that's done before dispatch).
      *
      * `oflags` and `fdflags` are passed through verbatim. Read-only
      * impls like [[inMemory]] ignore them; future write-capable impls
      * will act on `OFLAGS_CREAT` (1), `OFLAGS_DIRECTORY` (2),
      * `OFLAGS_EXCL` (4), `OFLAGS_TRUNC` (8), and the `FDFLAGS_*` bits.
      *
      * The default impl returns `Left(Wasi.ENOTCAPABLE)`: the preopen
      * advertises its name through the prestat-walk surface but has no
      * FS capability behind it. This is what [[named]] inherits — a
      * test that constructs `Preopen.named("/sandbox")` and then tries
      * to `path_open` against fd 3 gets ENOTCAPABLE rather than ENOENT,
      * because the distinction matters (ENOENT says "no such path",
      * ENOTCAPABLE says "you can't even ask through this preopen"). */
    def open(path: String, oflags: Int, fdflags: Int): Either[Int, Wasi.FsFile] =
      Left(Wasi.ENOTCAPABLE)

    /** Stat a path within this preopen WITHOUT opening it. Called by
      * `path_filestat_get`. Returns `Right(size)` for an existing file
      * (file type is REGULAR_FILE — no directories in the flat path
      * model yet) or `Left(errno)` on failure:
      * `Wasi.ENOENT` for a missing path, `Wasi.ENOTCAPABLE` for a
      * preopen with no FS capability (`Preopen.named`).
      *
      * Default impl returns `Left(Wasi.ENOTCAPABLE)` for consistency
      * with [[open]] — a name-only preopen refuses path-resolved
      * lookups the same way it refuses opens. */
    def statPath(path: String): Either[Int, Long] =
      Left(Wasi.ENOTCAPABLE)

    /** Remove `path` from this preopen. Called by `path_unlink_file`.
      * Returns `Right(())` on success, `Left(Wasi.ENOENT)` for a
      * missing path, `Left(Wasi.ENOTCAPABLE)` for a preopen with no
      * FS capability.
      *
      * Default impl returns `Left(Wasi.ENOTCAPABLE)`. The InMemory
      * impl overrides to actually drop the path from its backing
      * map; any already-open handles keep their own cell reference
      * (POSIX unlink-while-open). */
    private[wasi] def unlinkPath(path: String): Either[Int, Unit] =
      Left(Wasi.ENOTCAPABLE)

    /** Create a directory entry at `path`. Called by
      * `path_create_directory`. Returns `Right(())` on success,
      * `Left(Wasi.EEXIST)` if anything (file or dir) already exists
      * at that path, `Left(Wasi.ENOTCAPABLE)` for a preopen with no
      * FS capability. */
    private[wasi] def mkdir(path: String): Either[Int, Unit] =
      Left(Wasi.ENOTCAPABLE)

    /** Enumerate directory entries for `fd_readdir`. Each tuple is
      * `(name, filetype byte, inode)`. Default impl returns empty
      * (name-only preopens advertise no contents). The InMemory impl
      * returns all entries in insertion order. */
    private[wasi] def readdir: Seq[(String, Byte, Long)] = Seq.empty

    /** Filetype byte for `path`, used by `path_filestat_get` and
      * `fd_readdir`. Returns `None` for a missing path; otherwise one
      * of the wasi-preview1 filetype constants (`3` = DIRECTORY,
      * `4` = REGULAR_FILE, `7` = SYMBOLIC_LINK). Default impl returns
      * `None` — `path_filestat_get` then falls back to REGULAR_FILE so
      * file-only preopens stay honest without overriding. */
    private[wasi] def filetypeOf(path: String): Option[Byte] = None

  object Preopen:
    /** A name-only preopen: advertises the directory through the
      * prestat-walk surface but refuses to open anything inside it
      * (inherits the default `open` impl, which returns
      * `Left(Wasi.ENOTCAPABLE)`). Useful for tests that exercise the
      * 7.E.1 prestat surface in isolation. */
    def named(n: String): Preopen = new Preopen:
      val name: String = n

    /** A read/write preopen backed by an in-memory map. `open(path)`
      * returns the file's bytes wrapped in an [[InMemoryFile]] handle;
      * a miss returns `Left(Wasi.ENOENT)` unless `oflags & OFLAGS_CREAT`
      * is set, in which case the file is created (size 0) and a fresh
      * handle is returned. `oflags & OFLAGS_TRUNC` zeroes an existing
      * file's contents before returning the handle. `fdflags` is
      * currently ignored — `FDFLAGS_APPEND` and friends are a future
      * hardening pass. This is the test harness Phases 7.E.2–7.F were
      * designed around; the 7.F real-rustc file-write smoke test relies
      * on the CREAT+TRUNC path.
      *
      * Returns the concrete [[InMemoryPreopen]] subtype so callers can
      * reach `.bytesOf(path)` to inspect what the wasi program wrote
      * after the run. Storing the return as a bare `Preopen` is fine —
      * the prestat-walk surface still works.
      *
      * Paths are matched verbatim against the backing map's keys — no
      * canonicalisation, no `.`/`..` resolution, no leading-slash
      * normalisation. Tests construct keys to match exactly what
      * wasi-libc's `__wasilibc_find_relpath` strips a preopen path
      * down to (e.g. `hello.txt`, not `/sandbox/hello.txt`, when the
      * preopen is `/sandbox`). */
    def inMemory(n: String,
                 files: Map[String, Array[Byte]] = Map.empty): InMemoryPreopen =
      new InMemoryPreopen(n, files)

    // OFLAGS bits per wasi-preview1's `oflags` (witx-defined). EXCL is
    // honoured: `CREAT | EXCL` on an existing path returns EEXIST so
    // userspace gets the spec-correct "atomic create" semantics.
    // DIRECTORY (0x02) is honoured at the existence-check layer: if
    // set, the resolved path MUST be a DirEntry — else ENOTDIR.
    // (Opening a directory fd through path_open is still unsupported;
    // a DirEntry open returns EISDIR. fd_readdir works on preopen-dir
    // fds for now.)
    private val OFLAGS_CREAT:     Int = 0x0001
    private val OFLAGS_DIRECTORY: Int = 0x0002
    private val OFLAGS_EXCL:      Int = 0x0004
    private val OFLAGS_TRUNC:     Int = 0x0008

    /** Read/write in-memory preopen. Files seeded at construction are
      * cloned defensively; later writes mutate only this preopen's
      * backing store. Tests inspect post-state via [[bytesOf]] or
      * [[paths]] (which see both files and directories).
      *
      * Storage: each path maps to an [[Entry]] — either a [[FileEntry]]
      * wrapping a mutable [[FileCell]] or a [[DirEntry]] sentinel. Open
      * file handles ([[InMemoryFile]]) reference the same FileCell, so
      * a `write` that grows the buffer is visible to the preopen's
      * `bytesOf` immediately — and to any other handle pointing at the
      * same path. This matches POSIX behaviour where two `open` calls
      * on the same file share the underlying inode.
      *
      * Directories are represented as sentinels (no contents). They
      * surface through `path_filestat_get` (filetype = DIRECTORY) and
      * `fd_readdir`, and refuse `path_open` with `EISDIR`. The
      * flat-path model means a directory entry has no "children" —
      * `fd_readdir` lists every entry in the preopen regardless of
      * which directory the caller asked about. */
    final class InMemoryPreopen private[wasi] (
        val name: String,
        initial:  Map[String, Array[Byte]],
    ) extends Preopen:

      // LinkedHashMap so `.paths` reports insertion order — useful for
      // tests that want to assert "exactly one new file appeared in this
      // order" after a write-heavy run. Clone the initial arrays so the
      // caller's references stay independent of subsequent in-FS writes.
      private val cells = scala.collection.mutable.LinkedHashMap.from(
        initial.toSeq.map { case (k, v) =>
          k -> (FileEntry(new FileCell(v.clone())): Entry)
        }
      )

      override def open(path:    String,
                        oflags:  Int,
                        fdflags: Int): Either[Int, Wasi.FsFile] =
        val creat   = (oflags & OFLAGS_CREAT)     != 0
        val excl    = (oflags & OFLAGS_EXCL)      != 0
        val trunc   = (oflags & OFLAGS_TRUNC)     != 0
        val dirOnly = (oflags & OFLAGS_DIRECTORY) != 0
        cells.get(path) match
          case Some(FileEntry(cell)) =>
            // OFLAGS_DIRECTORY guarded against opening a regular file as
            // a directory — POSIX-style "you asked for a dir; got a file".
            if dirOnly then Left(Wasi.ENOTDIR)
            // EXCL atomic-create: if both CREAT and EXCL are set and the
            // file already exists, fail rather than reuse. Userspace uses
            // this for things like lockfiles where seeing an existing
            // file is the *signal* it's looking for.
            else if creat && excl then Left(Wasi.EEXIST)
            else
              if trunc then cell.bytes = new Array[Byte](0)
              Right(new InMemoryFile(cell))
          case Some(DirEntry) =>
            // Path resolves to a directory. Opening a directory fd
            // through path_open isn't supported in this slice — only
            // preopens carry the "directory fd" role today, and
            // fd_readdir runs against those. Userspace gets EISDIR.
            Left(Wasi.EISDIR)
          case None =>
            // Missing — CREAT-or-fail. If CREAT+DIRECTORY both set,
            // userspace is asking to create a directory through
            // path_open; that's not what path_open is for (it'd be
            // path_create_directory). ENOTDIR matches uvwasi behaviour.
            if dirOnly      then Left(Wasi.ENOTDIR)
            else if !creat  then Left(Wasi.ENOENT)
            else
              val cell = new FileCell(new Array[Byte](0))
              cells(path) = FileEntry(cell)
              Right(new InMemoryFile(cell))

      /** Remove `path` from this preopen. Used by `path_unlink_file`.
        * Returns `Right(())` on success, `Left(Wasi.ENOENT)` for a
        * missing path, `Left(Wasi.EISDIR)` for a directory entry
        * (the syscall name is `path_unlink_FILE`; a separate
        * `path_remove_directory` syscall handles directories — not yet
        * implemented). Open handles against an unlinked file keep their
        * own [[FileCell]] reference — the cell isn't freed until those
        * handles close, matching POSIX "unlink while open" semantics. */
      override private[wasi] def unlinkPath(path: String): Either[Int, Unit] =
        cells.get(path) match
          case Some(FileEntry(_)) =>
            cells.remove(path)
            Right(())
          case Some(DirEntry) => Left(Wasi.EISDIR)
          case None           => Left(Wasi.ENOENT)

      override def statPath(path: String): Either[Int, Long] =
        cells.get(path) match
          case Some(FileEntry(cell)) => Right(cell.bytes.length.toLong)
          case Some(DirEntry)        => Right(0L)
          case None                  => Left(Wasi.ENOENT)

      /** Filetype of `path`. Used by `path_filestat_get` (which needs
        * filetype + size) and `fd_readdir` (which needs filetype alone).
        * Returns one of the wasi-preview1 filetype bytes: 3=DIRECTORY,
        * 4=REGULAR_FILE; `None` for a missing path. */
      override private[wasi] def filetypeOf(path: String): Option[Byte] =
        cells.get(path).map {
          case FileEntry(_) => 4: Byte
          case DirEntry     => 3: Byte
        }

      override private[wasi] def mkdir(path: String): Either[Int, Unit] =
        if cells.contains(path) then Left(Wasi.EEXIST)
        else
          cells(path) = DirEntry
          Right(())

      /** Snapshot the directory entries for `fd_readdir`. Each tuple is
        * `(name, filetype byte, inode)`; the inode is just an index here
        * (0..N-1) since the InMemoryFs has no real inode-allocation
        * machinery. Insertion order. */
      override private[wasi] def readdir: Seq[(String, Byte, Long)] =
        cells.toSeq.zipWithIndex.map { case ((name, entry), idx) =>
          val ft: Byte = entry match
            case FileEntry(_) => 4: Byte
            case DirEntry     => 3: Byte
          (name, ft, idx.toLong)
        }

      /** Current bytes for `path` after any writes the wasi program
        * performed. `None` if no file at that key (or the key is a
        * directory). `Some(Array.empty)` is a CREAT-flagged open that
        * never wrote anything. */
      def bytesOf(path: String): Option[Array[Byte]] =
        cells.get(path).collect { case FileEntry(cell) => cell.bytes }

      /** All paths currently in this FS (files and directories), in
        * insertion order. */
      def paths: Seq[String] = cells.keys.toSeq

      /** Subset of [[paths]] that are directories. Useful for tests
        * that want to confirm `path_create_directory` lands. */
      def directoryPaths: Seq[String] =
        cells.collect { case (k, DirEntry) => k }.toSeq

    /** One entry in an [[InMemoryPreopen]]: either a file (with its
      * mutable byte cell) or a directory (a marker — no children in
      * the flat-path model). */
    private[wasi] sealed trait Entry
    private[wasi] final case class FileEntry(cell: FileCell) extends Entry
    private[wasi] case object DirEntry extends Entry

    /** Mutable byte-array cell. Open handles share the cell so a
      * cursor-advancing write on one handle is observable through the
      * preopen's `bytesOf` lookup (and through any other handle pointing
      * at the same path). */
    private[wasi] final class FileCell(var bytes: Array[Byte])

    /** In-memory [[Wasi.FsFile]] impl for [[InMemoryPreopen]].
      * `close()` is a no-op — there's no host resource to release.
      * The cursor starts at 0 and advances on `read` / `write`. `seek`
      * is an unconditional set — `pos > size` is allowed (subsequent
      * reads then return 0, matching POSIX `lseek` past EOF; subsequent
      * writes grow the file and zero-fill the gap).
      *
      * Negative cursors can't be reached through the syscall surface
      * (`fd_seek` rejects them with EINVAL before calling), but the
      * `read` and `write` impls still guard against `cursor < 0` so a
      * hand-written test that pokes the impl directly stays safe. */
    private final class InMemoryFile(cell: FileCell) extends Wasi.FsFile:
      private var cursor: Long = 0L
      def close(): Unit = ()
      def size:    Long = cell.bytes.length.toLong
      def tell:    Long = cursor
      def seek(pos: Long): Unit = cursor = pos
      def read(dst: Array[Byte], offset: Int, length: Int): Int =
        val bytes = cell.bytes
        if cursor < 0 || cursor >= bytes.length || length <= 0 then 0
        else
          val available = math.min(bytes.length - cursor, length.toLong).toInt
          System.arraycopy(bytes, cursor.toInt, dst, offset, available)
          cursor += available
          available
      override def write(src: Array[Byte], offset: Int, length: Int): Int =
        if length <= 0 then return 0
        val start = if cursor < 0 then 0L else cursor
        val end   = start + length.toLong
        if end > cell.bytes.length then
          // Grow the cell to fit. Java's `new Array[Byte]` is zero-init,
          // so any gap between the old size and `start` reads back as
          // zero — POSIX hole semantics, but materialised eagerly because
          // the InMemoryFs has no sparse-file machinery.
          val grown = new Array[Byte](end.toInt)
          System.arraycopy(cell.bytes, 0, grown, 0, cell.bytes.length)
          cell.bytes = grown
        System.arraycopy(src, offset, cell.bytes, start.toInt, length)
        cursor = end
        length

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
