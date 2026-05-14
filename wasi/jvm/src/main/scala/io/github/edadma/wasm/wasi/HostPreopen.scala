package io.github.edadma.wasm.wasi

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{
  DirectoryNotEmptyException,
  FileAlreadyExistsException,
  Files,
  NoSuchFileException,
  Path,
  Paths,
  StandardOpenOption,
}
import java.nio.file.attribute.BasicFileAttributes

/** JVM factory for [[HostBackedPreopen]] — backs a wasi preopen with a
  * real on-disk directory through `java.nio.file`.
  *
  * Usage:
  *
  * {{{
  * val ctx = WasiContext.collecting(
  *   preopens = Vector(HostPreopen.fromDir("/var/data", "/data"))
  * )
  * }}}
  *
  * The Scala Native build supplies an identical object (Native's
  * javalib provides `java.nio.file`); the JS build supplies a Node
  * `fs`-backed object. Code that depends on `wasi` and constructs a
  * preopen from a host path therefore compiles unchanged across the
  * three platforms. */
object HostPreopen:

  /** Build a [[WasiContext.Preopen]] that exposes `hostPath` (a directory
    * on the host filesystem) to a wasi program under `virtualName`.
    *
    * `hostPath` must exist and be a directory at construction time; a
    * missing or non-directory host path throws `IllegalArgumentException`
    * eagerly (matching the spirit of `Files.newByteChannel`'s eager
    * resolution — failing here is friendlier than failing at the first
    * `path_open` inside the wasi program).
    *
    * `virtualName` is the string the wasi program sees through
    * `fd_prestat_dir_name` (typically a leading-slash path the program's
    * libc passes to `__wasilibc_register_preopened_fd`, e.g. `/data`).
    *
    * All wasi-relative paths the program asks for are joined with
    * `hostPath` after sanitisation by [[HostBackedPreopen]]; absolute
    * paths, NUL bytes, and `..` escape attempts are rejected with
    * `ENOTCAPABLE` / `ENOENT` before reaching this object. */
  def fromDir(hostPath: String, virtualName: String): WasiContext.Preopen =
    val root = Paths.get(hostPath).toAbsolutePath.normalize()
    if !Files.exists(root) then
      throw new IllegalArgumentException(s"hostPath does not exist: $hostPath")
    if !Files.isDirectory(root) then
      throw new IllegalArgumentException(s"hostPath is not a directory: $hostPath")
    new HostBackedPreopen(virtualName, new JvmHostFs(root))

  /** `java.nio.file`-backed [[HostFs]]. Joins preopen-relative paths
    * with the constructor `root` via `Path.resolve` (the path has
    * already been sanitised at the [[HostBackedPreopen]] layer, so no
    * additional traversal check is required here — `..` segments have
    * been normalised out and absolute paths rejected).
    *
    * Errno translation is intentionally narrow: NIO's exception
    * hierarchy is rich, but most failures the wasi syscall layer cares
    * about are "missing" / "already exists" / "is a directory" — those
    * map to `ENOENT` / `EEXIST` / `EISDIR`. Anything else surfaces as
    * `EIO`. */
  private final class JvmHostFs(root: Path) extends HostFs:

    override def stat(rel: String): Option[HostFs.Stat] =
      val p = root.resolve(rel)
      try
        val attrs = Files.readAttributes(p, classOf[BasicFileAttributes])
        if attrs.isDirectory then Some(HostFs.Dir)
        else                      Some(HostFs.File(attrs.size))
      catch
        case _: NoSuchFileException => None
        case _: IOException         => None

    override def open(rel: String, write: Boolean): Either[Int, HostFs.Handle] =
      val p = root.resolve(rel)
      try
        val opts =
          if write then
            java.util.EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
          else
            java.util.EnumSet.of(StandardOpenOption.READ)
        val ch = FileChannel.open(p, opts)
        Right(new ChannelHandle(ch))
      catch
        case _: NoSuchFileException => Left(Wasi.ENOENT)
        case _: IOException         => Left(Wasi.EIO)

    override def createNew(rel: String): Either[Int, HostFs.Handle] =
      val p = root.resolve(rel)
      try
        val opts = java.util.EnumSet.of(
          StandardOpenOption.READ,
          StandardOpenOption.WRITE,
          StandardOpenOption.CREATE_NEW,
        )
        val ch = FileChannel.open(p, opts)
        Right(new ChannelHandle(ch))
      catch
        case _: FileAlreadyExistsException => Left(Wasi.EEXIST)
        case _: NoSuchFileException        => Left(Wasi.ENOENT)
        case _: IOException                => Left(Wasi.EIO)

    override def truncate(rel: String): Either[Int, Unit] =
      val p = root.resolve(rel)
      try
        val ch = FileChannel.open(p, StandardOpenOption.WRITE)
        try ch.truncate(0L) finally ch.close()
        Right(())
      catch
        case _: NoSuchFileException => Left(Wasi.ENOENT)
        case _: IOException         => Left(Wasi.EIO)

    override def listDir(): Seq[(String, HostFs.Stat)] =
      val ds = Files.newDirectoryStream(root)
      try
        val it     = ds.iterator
        val buf    = scala.collection.mutable.ArrayBuffer.empty[(String, HostFs.Stat)]
        while it.hasNext do
          val p     = it.next()
          val attrs = Files.readAttributes(p, classOf[BasicFileAttributes])
          val st: HostFs.Stat =
            if attrs.isDirectory then HostFs.Dir
            else                      HostFs.File(attrs.size)
          buf += ((p.getFileName.toString, st))
        // Sort by name for stable cookie semantics in `fd_readdir` — `Files
        // .newDirectoryStream` doesn't promise an order.
        buf.sortBy(_._1).toSeq
      finally ds.close()

    override def mkdir(rel: String): Either[Int, Unit] =
      val p = root.resolve(rel)
      try
        Files.createDirectory(p)
        Right(())
      catch
        case _: FileAlreadyExistsException => Left(Wasi.EEXIST)
        case _: NoSuchFileException        => Left(Wasi.ENOENT)
        case _: IOException                => Left(Wasi.EIO)

    override def unlinkFile(rel: String): Either[Int, Unit] =
      val p = root.resolve(rel)
      try
        Files.delete(p)
        Right(())
      catch
        case _: NoSuchFileException        => Left(Wasi.ENOENT)
        case _: DirectoryNotEmptyException => Left(Wasi.EISDIR)
        case _: IOException                => Left(Wasi.EIO)

  /** [[HostFs.Handle]] backed by a `java.nio.channels.FileChannel`.
    * Uses the positional `read(ByteBuffer, position)` /
    * `write(ByteBuffer, position)` overloads so the wasi-shape cursor
    * sits entirely above this handle (in [[HostBackedFsFile]]) — the
    * channel's own internal position is left alone. */
  private final class ChannelHandle(ch: FileChannel) extends HostFs.Handle:

    private var closed = false

    override def close(): Unit =
      if !closed then
        closed = true
        try ch.close() catch case _: IOException => ()

    override def read(dst: Array[Byte], off: Int, len: Int, pos: Long): Int =
      val buf = ByteBuffer.wrap(dst, off, len)
      val n   = ch.read(buf, pos)
      // NIO returns -1 at EOF; the wasi shim wants 0.
      if n < 0 then 0 else n

    override def write(src: Array[Byte], off: Int, len: Int, pos: Long): Int =
      val buf = ByteBuffer.wrap(src, off, len)
      val n   = ch.write(buf, pos)
      if n < 0 then 0 else n

    override def size: Long =
      try ch.size catch case _: IOException => 0L
