package io.github.edadma.wasm.wasi

import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.Comparator

/** Per-platform temp-dir helper used by [[WasiHostFsTests]] to spin up
  * real on-disk scratch directories. JVM impl — Scala Native ships an
  * identical file under `wasi/native/src/test/`. The JS impl wraps
  * Node's `fs.mkdtempSync` and friends.
  *
  * Why this isn't in `shared/`: `java.nio.file` doesn't exist on
  * Scala.js, so the test code can't reach for it directly. Putting a
  * thin platform-dispatched helper here lets the actual test logic
  * (file content / API expectations) live in `shared/`. */
object TempDir:

  /** Create a fresh empty directory under the system temp dir.
    * Returns the absolute path as a string. */
  def create(prefix: String): String =
    Files.createTempDirectory(prefix).toAbsolutePath.toString

  /** Recursively delete `path`. Best-effort: any IO failure is
    * swallowed so test cleanup never raises (a failing cleanup
    * shouldn't mask the test's real failure). */
  def remove(path: String): Unit =
    val p = Paths.get(path)
    if Files.exists(p) then
      val stream = Files.walk(p)
      try
        stream.sorted(Comparator.reverseOrder()).forEach { entry =>
          try Files.delete(entry) catch case _: Throwable => ()
        }
      finally stream.close()

  /** Write `bytes` into `dir/rel`, creating or overwriting. Parent
    * directories must already exist (call [[mkdir]] first if nested).
    * `rel` is interpreted relative to `dir` — pass platform-correct
    * separators (`/` works on Unix and most Java NIO paths). */
  def writeFile(dir: String, rel: String, bytes: Array[Byte]): Unit =
    val p = Paths.get(dir).resolve(rel)
    Files.write(p, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

  /** Read `dir/rel` as bytes. Throws if the file doesn't exist. */
  def readFile(dir: String, rel: String): Array[Byte] =
    Files.readAllBytes(Paths.get(dir).resolve(rel))

  /** Whether `dir/rel` exists as anything (file or dir). */
  def exists(dir: String, rel: String): Boolean =
    Files.exists(Paths.get(dir).resolve(rel))

  /** Whether `dir/rel` exists and is a directory. */
  def isDir(dir: String, rel: String): Boolean =
    Files.isDirectory(Paths.get(dir).resolve(rel))

  /** Whether `dir/rel` exists and is a regular file. */
  def isFile(dir: String, rel: String): Boolean =
    Files.isRegularFile(Paths.get(dir).resolve(rel))

  /** Create a sub-directory under `dir/rel`. Parents must already exist
    * (this helper is for explicit per-test seeding, not nested mkdir). */
  def mkdir(dir: String, rel: String): Unit =
    Files.createDirectory(Paths.get(dir).resolve(rel))
