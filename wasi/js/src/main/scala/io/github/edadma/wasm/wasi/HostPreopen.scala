package io.github.edadma.wasm.wasi

import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}

/** Scala.js / Node.js factory for [[HostBackedPreopen]] — backs a wasi
  * preopen with a real on-disk directory through Node's `fs` module.
  *
  * `java.nio.file` is not available on Scala.js, so this implementation
  * shells out to Node's synchronous `fs.*Sync` family directly. The
  * public `HostPreopen.fromDir` surface matches the JVM/Native impls
  * exactly — code that depends on `wasi` and constructs a preopen
  * from a host path compiles unchanged across the three platforms.
  *
  * Usage (under Node only — this object is meaningless in a browser):
  *
  * {{{
  * val ctx = WasiContext.collecting(
  *   preopens = Vector(HostPreopen.fromDir("/var/data", "/data"))
  * )
  * }}}
  *
  * Error handling: every `fs.*Sync` call throws a `js.Error` with a
  * `.code` string property on failure (`ENOENT`, `EEXIST`, `EISDIR`,
  * etc.). We pattern-match on `.code` to return the matching
  * wasi-preview1 errno; unrecognised codes fall through to
  * [[Wasi.EIO]]. */
object HostPreopen:

  /** Build a [[WasiContext.Preopen]] that exposes `hostPath` to a wasi
    * program under `virtualName`. See the JVM impl's docstring for the
    * full contract — behaviour is identical except this version
    * delegates to Node's `fs` rather than `java.nio.file`. */
  def fromDir(hostPath: String, virtualName: String): WasiContext.Preopen =
    val fs   = g.require("fs")
    val path = g.require("path")
    // Resolve to an absolute, normalised path so subsequent `path.join`
    // calls produce consistent absolute paths (matches the JVM impl's
    // `toAbsolutePath.normalize()`).
    val root = path.resolve(hostPath).asInstanceOf[String]
    // Eager existence + directory check, mirroring the JVM impl. A
    // missing/non-directory hostPath is a build-time error — the wasi
    // program shouldn't need to handle "preopen wasn't actually a dir."
    val stOpt = tryStat(fs, root)
    if stOpt.isEmpty then
      throw new IllegalArgumentException(s"hostPath does not exist: $hostPath")
    if !stOpt.get.isDirectory then
      throw new IllegalArgumentException(s"hostPath is not a directory: $hostPath")
    new HostBackedPreopen(virtualName, new NodeHostFs(fs, path, root))

  // -- helpers ----------------------------------------------------------------

  /** Stat result we care about: filetype + size. Hiding `js.Dynamic`
    * behind a tiny Scala-level case class makes the [[NodeHostFs]]
    * impl read like the JVM one. */
  private final case class JsStat(isDirectory: Boolean, size: Long)

  /** Try-stat that swallows "ENOENT" specifically and rethrows other
    * codes (so genuine permission failures still surface as
    * exceptions rather than being silently treated as missing).
    * The wasi-shape `stat` in [[NodeHostFs]] catches the rethrow and
    * routes to `EIO`. */
  private def tryStat(fs: js.Dynamic, p: String): Option[JsStat] =
    try
      val s = fs.statSync(p)
      Some(JsStat(
        isDirectory = s.isDirectory().asInstanceOf[Boolean],
        size        = s.size.asInstanceOf[Double].toLong,
      ))
    catch
      case e: js.JavaScriptException =>
        val code = jsCode(e)
        if code == "ENOENT" || code == "ENOTDIR" then None
        else throw e

  /** Extract the `.code` string from a Node `fs` error, or `""` if it
    * isn't present. Used for routing errors to wasi errnos. */
  private def jsCode(e: js.JavaScriptException): String =
    val any = e.exception.asInstanceOf[js.Dynamic]
    val raw = any.code
    if js.isUndefined(raw) then "" else raw.asInstanceOf[String]

  /** Map a Node error code to a wasi errno. Anything we don't
    * recognise becomes [[Wasi.EIO]]. */
  private def errnoOf(code: String): Int = code match
    case "ENOENT"        => Wasi.ENOENT
    case "EEXIST"        => Wasi.EEXIST
    case "EISDIR"        => Wasi.EISDIR
    case "ENOTDIR"       => Wasi.ENOTDIR
    case "EACCES" | "EPERM" => Wasi.EACCES
    case _               => Wasi.EIO

  /** [[HostFs]] backed by Node's synchronous `fs` calls. The wasi
    * syscall surface is itself synchronous (the wasm interpreter
    * yields nothing between host calls), so `fs.*Sync` is the right
    * shape — async fs calls would need event-loop reentry through the
    * interpreter which the current Phase 7 design doesn't support. */
  private final class NodeHostFs(fs: js.Dynamic, path: js.Dynamic, root: String)
      extends HostFs:

    private def join(rel: String): String =
      path.join(root, rel).asInstanceOf[String]

    override def stat(rel: String): Option[HostFs.Stat] =
      try
        tryStat(fs, join(rel)).map {
          case JsStat(true, _)     => HostFs.Dir
          case JsStat(false, size) => HostFs.File(size)
        }
      catch case _: js.JavaScriptException => None

    override def open(rel: String, write: Boolean): Either[Int, HostFs.Handle] =
      try
        val flags = if write then "r+" else "r"
        val fd    = fs.openSync(join(rel), flags).asInstanceOf[Int]
        Right(new NodeHandle(fs, fd))
      catch case e: js.JavaScriptException => Left(errnoOf(jsCode(e)))

    override def createNew(rel: String): Either[Int, HostFs.Handle] =
      try
        // 'wx+' = O_RDWR | O_CREAT | O_EXCL.
        val fd = fs.openSync(join(rel), "wx+").asInstanceOf[Int]
        Right(new NodeHandle(fs, fd))
      catch case e: js.JavaScriptException => Left(errnoOf(jsCode(e)))

    override def truncate(rel: String): Either[Int, Unit] =
      try
        fs.truncateSync(join(rel), 0)
        Right(())
      catch case e: js.JavaScriptException => Left(errnoOf(jsCode(e)))

    override def listDir(): Seq[(String, HostFs.Stat)] =
      // `fs.readdirSync` returns a `js.Array[String]` of basenames; we
      // stat each to discriminate file vs dir. Sorted by name so the
      // `fd_readdir` cookie semantics stay stable across Node versions
      // (readdir order isn't promised by the spec).
      val names = fs.readdirSync(root).asInstanceOf[js.Array[String]].toSeq.sorted
      names.flatMap { nm =>
        try
          tryStat(fs, path.join(root, nm).asInstanceOf[String]).map { js =>
            val st: HostFs.Stat =
              if js.isDirectory then HostFs.Dir
              else                   HostFs.File(js.size)
            (nm, st)
          }
        catch case _: js.JavaScriptException => None
      }

    override def mkdir(rel: String): Either[Int, Unit] =
      try
        fs.mkdirSync(join(rel))
        Right(())
      catch case e: js.JavaScriptException => Left(errnoOf(jsCode(e)))

    override def unlinkFile(rel: String): Either[Int, Unit] =
      try
        fs.unlinkSync(join(rel))
        Right(())
      catch case e: js.JavaScriptException => Left(errnoOf(jsCode(e)))

  /** [[HostFs.Handle]] backed by a Node file descriptor (the `Int`
    * returned by `fs.openSync`). Read/write go through
    * `fs.readSync` / `fs.writeSync` with a position arg, mirroring
    * the JVM's `FileChannel.read(pos)` / `write(pos)` so the wasi
    * cursor stays at the [[HostBackedFsFile]] layer. */
  private final class NodeHandle(fs: js.Dynamic, fd: Int) extends HostFs.Handle:

    private var closed = false

    override def close(): Unit =
      if !closed then
        closed = true
        try fs.closeSync(fd) catch case _: js.JavaScriptException => ()

    override def read(dst: Array[Byte], off: Int, len: Int, pos: Long): Int =
      // Allocate a Buffer of exactly `len`, hand to fs.readSync, then
      // copy the bytes back into the Scala Array[Byte]. Per-call
      // allocation; fine for the wasi syscall volumes we're seeing.
      val buf = g.Buffer.alloc(len).asInstanceOf[js.Dynamic]
      val n   = fs.readSync(fd, buf, 0, len, pos.toDouble).asInstanceOf[Int]
      var i   = 0
      while i < n do
        dst(off + i) = buf.applyDynamic("readInt8")(i).asInstanceOf[Int].toByte
        i += 1
      n

    override def write(src: Array[Byte], off: Int, len: Int, pos: Long): Int =
      // Build a Buffer from the slice. Buffer.alloc + writeInt8 keeps
      // the byte-level semantics explicit; for big writes a typed
      // Uint8Array view would be cheaper, but wasi tends to chunk
      // through 4-8KB iovecs anyway.
      val buf = g.Buffer.alloc(len).asInstanceOf[js.Dynamic]
      var i   = 0
      while i < len do
        buf.applyDynamic("writeInt8")(src(off + i).toInt, i)
        i += 1
      fs.writeSync(fd, buf, 0, len, pos.toDouble).asInstanceOf[Int]

    override def size: Long =
      try fs.fstatSync(fd).size.asInstanceOf[Double].toLong
      catch case _: js.JavaScriptException => 0L
