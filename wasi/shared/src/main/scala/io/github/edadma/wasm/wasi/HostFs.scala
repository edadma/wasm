package io.github.edadma.wasm.wasi

/** Host-filesystem primitives a platform plugs in to back a
  * [[HostBackedPreopen]] with a real on-disk directory.
  *
  * Each implementation supplies positional-I/O file handles and a small
  * set of metadata queries; everything that is wasi-shape (the OFLAGS
  * dispatch in `path_open`, errno translation, sandboxing, cursor
  * arithmetic) lives in [[HostBackedPreopen]] / [[HostBackedFsFile]] so
  * the three platforms can share it.
  *
  * Path arguments are always preopen-relative — the implementation is
  * given the path AFTER [[HostBackedPreopen]] has sanitised it
  * (rejecting absolute paths, NUL bytes, and `..` segments that would
  * escape the preopen root). Implementations may join with their root
  * verbatim and don't need to re-validate.
  *
  * Return shape: every operation that can fail at the host level
  * returns `Either[Int, T]` where the `Int` is a wasi-preview1 errno
  * — typically [[Wasi.ENOENT]], [[Wasi.EISDIR]], [[Wasi.ENOTDIR]],
  * [[Wasi.EEXIST]], [[Wasi.EACCES]], [[Wasi.EIO]]. Stat-style queries
  * surface "missing" as `None` rather than `Left(ENOENT)` because that
  * distinction is load-bearing in `path_open` — a miss with `CREAT`
  * set is a successful create, not a failure. */
private[wasi] trait HostFs:

  /** Stat the preopen-relative `rel` path. `None` = no such entry;
    * `Some(stat)` resolves to a [[HostFs.File]] (with size) or
    * [[HostFs.Dir]]. Symbolic links follow the platform default; the
    * 7.E.4 surface is flat so a sym-link to a regular file resolves
    * as a file and a sym-link to a directory as a directory. */
  def stat(rel: String): Option[HostFs.Stat]

  /** Open an existing regular file. Caller has already confirmed the
    * path exists and is not a directory (the wasi-shape dispatch in
    * [[HostBackedPreopen.open]] runs `stat` first). `write=true` opens
    * for read+write; `false` for read-only. Streaming I/O happens
    * through the returned [[HostFs.Handle]]. */
  def open(rel: String, write: Boolean): Either[Int, HostFs.Handle]

  /** Create a brand-new empty file at `rel`. Returns `Left(EEXIST)`
    * if anything already exists at the path; the caller's responsibility
    * to have checked first when CREAT+!EXCL is set. */
  def createNew(rel: String): Either[Int, HostFs.Handle]

  /** Truncate an existing file to zero length WITHOUT opening a fresh
    * handle. Called by `path_open` when `OFLAGS_TRUNC` lands on an
    * existing file; the subsequent [[open]] call returns a fresh
    * handle on the truncated file. */
  def truncate(rel: String): Either[Int, Unit]

  /** Enumerate the top-level entries in the preopen root.
    *
    * Each tuple is `(name, stat)` where `name` is the basename only
    * (not joined with the root). The flat-path model means
    * `fd_readdir` ignores sub-directory contents anyway, so a
    * single-level listing is enough. Order is implementation-defined
    * (the wasi spec allows any stable enumeration). */
  def listDir(): Seq[(String, HostFs.Stat)]

  /** Create a directory at `rel`. Returns `Left(EEXIST)` if anything
    * exists at the path. The parent is assumed to be the preopen
    * root — nested mkdir on a missing parent returns `Left(ENOENT)`. */
  def mkdir(rel: String): Either[Int, Unit]

  /** Unlink a regular file. `Left(ENOENT)` if missing, `Left(EISDIR)`
    * if the path resolves to a directory (`path_unlink_file` is
    * file-only; `path_remove_directory` is a separate syscall, not
    * yet implemented). */
  def unlinkFile(rel: String): Either[Int, Unit]

private[wasi] object HostFs:

  /** Stat result discriminator. The trait surface exposes filetype +
    * size without leaking host-specific metadata (mtime, perms, etc.)
    * — those become relevant when `path_filestat_get` grows beyond
    * size+filetype, which it does not in this slice. */
  sealed trait Stat:
    /** wasi-preview1 filetype byte (3 = DIRECTORY, 4 = REGULAR_FILE). */
    def filetype: Byte
    /** File size in bytes; `0` for directories. */
    def size:     Long

  /** Regular file with a known size. */
  final case class File(size: Long) extends Stat:
    val filetype: Byte = 4

  /** Directory entry — no size, no children enumerated at this layer
    * (a separate [[HostFs.listDir]] call does that). */
  case object Dir extends Stat:
    val filetype: Byte = 3
    val size:     Long = 0L

  /** Positional file-I/O handle. The wasi-shape [[HostBackedFsFile]]
    * owns the cursor; this handle just exposes `read` / `write` at an
    * arbitrary byte position. Lets the platform back the handle with
    * a `FileChannel` (JVM/Native) or a numeric `fd` from
    * `fs.openSync` (Node) without leaking cursor semantics across
    * the abstraction.
    *
    * `read` returns the byte count actually read (`0` = at-EOF; never
    * negative). `write` returns the byte count actually written (also
    * never negative). Both methods may emit fewer bytes than the
    * caller asked for; the cursor advance in [[HostBackedFsFile]]
    * uses the returned count.
    *
    * `close()` releases the host resource. Implementations should
    * tolerate double-close cleanly (no-op on the second call) so the
    * wasi `fd_close` path stays simple. */
  trait Handle:
    def close(): Unit
    def read(dst:  Array[Byte], off: Int, len: Int, pos: Long): Int
    def write(src: Array[Byte], off: Int, len: Int, pos: Long): Int
    def size: Long


/** A [[WasiContext.Preopen]] backed by a real host directory via a
  * platform-supplied [[HostFs]]. Construct through the platform-specific
  * `HostPreopen.fromDir(hostPath, virtualName)` factory rather than
  * directly — `fromDir` is what binds the right [[HostFs]] impl.
  *
  * The class does all the wasi-shape coordination on top of the bare
  * host primitives: it owns the [[WasiContext.Preopen]] trait surface
  * (`open` / `statPath` / `unlinkPath` / `mkdir` / `readdir` /
  * `filetypeOf`), translates OFLAGS into the right [[HostFs]] calls,
  * maps host errors to wasi errnos, and constructs
  * [[HostBackedFsFile]] handles with their own cursors.
  *
  * Sandboxing: every preopen-relative path the wasi program sends is
  * fed through `sanitisePath` before reaching [[HostFs]]. Absolute
  * paths, NUL bytes, and `..` segments that would escape the preopen
  * root are rejected with [[Wasi.ENOTCAPABLE]] — the preopen wraps a
  * specific host directory and refuses to look anywhere else. */
private[wasi] final class HostBackedPreopen(
    val name: String,
    fs:       HostFs,
) extends WasiContext.Preopen:

  import HostFs.{File => HostFile, Dir => HostDir}

  // OFLAGS bit positions per wasi-preview1. Mirrors the constants in
  // `WasiContext.Preopen` — duplicated here because those are private
  // to that companion.
  private final val OFLAGS_CREAT:     Int = 0x0001
  private final val OFLAGS_DIRECTORY: Int = 0x0002
  private final val OFLAGS_EXCL:      Int = 0x0004
  private final val OFLAGS_TRUNC:     Int = 0x0008

  override def open(path:    String,
                    oflags:  Int,
                    fdflags: Int): Either[Int, Wasi.FsFile] =
    sanitisePath(path).flatMap { rel =>
      val creat   = (oflags & OFLAGS_CREAT)     != 0
      val excl    = (oflags & OFLAGS_EXCL)      != 0
      val trunc   = (oflags & OFLAGS_TRUNC)     != 0
      val dirOnly = (oflags & OFLAGS_DIRECTORY) != 0

      fs.stat(rel) match
        case Some(HostFile(_)) =>
          if dirOnly             then Left(Wasi.ENOTDIR)
          else if creat && excl  then Left(Wasi.EEXIST)
          else
            val truncOk = if trunc then fs.truncate(rel) else Right(())
            truncOk.flatMap(_ => fs.open(rel, write = true))
              .map(new HostBackedFsFile(_))
        case Some(HostDir) =>
          // `path_open` on a directory entry is EISDIR — opening
          // directory fds is not supported in this slice (only
          // preopen fds carry the directory role today, and
          // `fd_readdir` runs against those).
          Left(Wasi.EISDIR)
        case None =>
          // Missing — CREAT-or-fail. CREAT+DIRECTORY through
          // `path_open` is a misuse (use `path_create_directory`
          // instead); returning ENOTDIR matches uvwasi behaviour.
          if dirOnly      then Left(Wasi.ENOTDIR)
          else if !creat  then Left(Wasi.ENOENT)
          else fs.createNew(rel).map(new HostBackedFsFile(_))
    }

  override def statPath(path: String): Either[Int, Long] =
    sanitisePath(path).flatMap { rel =>
      fs.stat(rel) match
        case Some(s) => Right(s.size)
        case None    => Left(Wasi.ENOENT)
    }

  override private[wasi] def filetypeOf(path: String): Option[Byte] =
    sanitisePath(path).toOption.flatMap(fs.stat).map(_.filetype)

  override private[wasi] def unlinkPath(path: String): Either[Int, Unit] =
    sanitisePath(path).flatMap { rel =>
      fs.stat(rel) match
        case None              => Left(Wasi.ENOENT)
        case Some(HostDir)     => Left(Wasi.EISDIR)
        case Some(HostFile(_)) => fs.unlinkFile(rel)
    }

  override private[wasi] def mkdir(path: String): Either[Int, Unit] =
    sanitisePath(path).flatMap { rel =>
      if fs.stat(rel).isDefined then Left(Wasi.EEXIST)
      else fs.mkdir(rel)
    }

  override private[wasi] def readdir: Seq[(String, Byte, Long)] =
    fs.listDir().zipWithIndex.map { case ((nm, st), idx) =>
      (nm, st.filetype, idx.toLong)
    }

  /** Reject preopen-relative paths that escape the sandbox. Returns
    * `Right(rel)` on success — the same string if the input was
    * already a clean relative path, or a `..`-resolved equivalent.
    * Failures:
    *
    *   - empty path → ENOENT (matches wasi-libc / POSIX `open` of an
    *     empty pathname)
    *   - absolute path (starts with `/`) → ENOTCAPABLE (the preopen
    *     refuses to look outside its root)
    *   - any NUL byte → ENOENT (NUL in a path is a userspace bug;
    *     wasi's currently-surfaced errno set has no EILSEQ slot)
    *   - `..` that would escape root → ENOTCAPABLE
    *
    * Sandbox check: split on `/`, walk the components keeping a depth
    * counter. `.` and empty components don't change depth (so `a//b`
    * and `a/./b` collapse). `..` decrements (rejects on negative).
    * Anything else (regular name) increments. */
  private def sanitisePath(path: String): Either[Int, String] =
    val nul = path.indexOf(0)
    if path.isEmpty                then Left(Wasi.ENOENT)
    else if path.charAt(0) == 47   then Left(Wasi.ENOTCAPABLE) // '/'
    else if nul >= 0               then Left(Wasi.ENOENT)
    else
      val parts = path.split(47.toChar)   // '/'
      var depth = 0
      var ok    = true
      val out   = new StringBuilder
      var i     = 0
      while ok && i < parts.length do
        val p = parts(i)
        if p.isEmpty || p == "." then () // skip; `a//b` and `a/./b` collapse
        else if p == ".." then
          if depth == 0 then ok = false
          else
            depth -= 1
            val slash = out.lastIndexOf("/")
            if slash < 0 then out.setLength(0)
            else out.setLength(slash)
        else
          if depth > 0 then out.append('/')
          out.append(p)
          depth += 1
        i += 1
      if !ok then Left(Wasi.ENOTCAPABLE)
      else Right(out.toString)


/** [[Wasi.FsFile]] backed by a positional [[HostFs.Handle]]. Owns the
  * cursor; delegates byte-level I/O to the handle. Mirrors
  * [[WasiContext.Preopen.InMemoryPreopen]]'s shape so the rest of the
  * wasi syscall layer sees a uniform FsFile surface regardless of
  * which preopen backed the open. */
private[wasi] final class HostBackedFsFile(
    handle: HostFs.Handle,
) extends Wasi.FsFile:

  private var cursor: Long    = 0L
  private var closed: Boolean = false

  override def close(): Unit =
    if !closed then
      closed = true
      handle.close()

  override def read(dst: Array[Byte], offset: Int, length: Int): Int =
    val n = handle.read(dst, offset, length, cursor)
    if n > 0 then cursor += n
    n

  override def write(src: Array[Byte], offset: Int, length: Int): Int =
    val n = handle.write(src, offset, length, cursor)
    if n > 0 then cursor += n
    n

  override def size: Long = handle.size
  override def tell: Long = cursor
  override def seek(pos: Long): Unit = cursor = pos
