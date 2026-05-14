package io.github.edadma.wasm.wasi

import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.typedarray.Uint8Array

/** Per-platform temp-dir helper used by [[WasiHostFsTests]] under
  * Scala.js / Node. Wraps Node's `fs.mkdtempSync` + `fs.rmSync` so the
  * shared test logic can stay platform-free.
  *
  * The JVM and Native impls live in their own platform test folders;
  * Native's javalib provides the same `java.nio.file` surface as the
  * JVM impl, so they're duplicates of each other and differ only in
  * the leading docstring. */
object TempDir:

  // Pull these once at object-init — every helper call re-uses the
  // same module handles instead of paying for require() each time.
  private val fs   = g.require("fs")
  private val path = g.require("path")
  private val os   = g.require("os")

  def create(prefix: String): String =
    val tmpRoot = os.tmpdir().asInstanceOf[String]
    val base    = path.join(tmpRoot, prefix).asInstanceOf[String]
    fs.mkdtempSync(base).asInstanceOf[String]

  def remove(path: String): Unit =
    val opts = js.Dynamic.literal(recursive = true, force = true)
    try fs.rmSync(path, opts) catch case _: Throwable => ()

  def writeFile(dir: String, rel: String, bytes: Array[Byte]): Unit =
    val p   = path.join(dir, rel).asInstanceOf[String]
    val arr = new Uint8Array(bytes.length)
    var i   = 0
    while i < bytes.length do
      arr(i) = (bytes(i) & 0xff).toShort
      i += 1
    fs.writeFileSync(p, arr)

  def readFile(dir: String, rel: String): Array[Byte] =
    val p   = path.join(dir, rel).asInstanceOf[String]
    val buf = fs.readFileSync(p).asInstanceOf[Uint8Array]
    val out = new Array[Byte](buf.length)
    var i   = 0
    while i < buf.length do
      out(i) = buf(i).toByte
      i += 1
    out

  def exists(dir: String, rel: String): Boolean =
    fs.existsSync(path.join(dir, rel)).asInstanceOf[Boolean]

  def isDir(dir: String, rel: String): Boolean =
    if !exists(dir, rel) then false
    else
      fs.statSync(path.join(dir, rel)).isDirectory().asInstanceOf[Boolean]

  def isFile(dir: String, rel: String): Boolean =
    if !exists(dir, rel) then false
    else
      fs.statSync(path.join(dir, rel)).isFile().asInstanceOf[Boolean]

  def mkdir(dir: String, rel: String): Unit =
    fs.mkdirSync(path.join(dir, rel))
