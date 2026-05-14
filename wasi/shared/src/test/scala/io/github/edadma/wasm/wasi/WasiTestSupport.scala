package io.github.edadma.wasm.wasi

import scala.collection.mutable.ArrayBuffer

import io.github.edadma.wasm.{I32, ModuleInstance, Runtime}

/** Shared framework + helpers for the wasi test suite. Mirrors `interp`'s
  * `TestSupport`: a single object that holds the `passed` / `failures`
  * counters every category file mutates, plus the `test` / `check`
  * primitives and the `instantiate` / `readCString` helpers most tests
  * reach for.
  *
  * Zero external deps; runs identically on JVM, Scala.js, and Scala Native.
  * Categories (`WasiFdTests`, `WasiArgsTests`, `WasiClockTests`) call
  * these helpers from their own `run()` methods. `WasiTest` is the
  * orchestrator.
  */
object WasiTestSupport:

  // === Tiny test framework ================================================

  var passed: Int                       = 0
  val failures: ArrayBuffer[String]     = ArrayBuffer.empty

  def test(name: String)(body: => Unit): Unit =
    try
      body
      passed += 1
      println(s"  OK    $name")
    catch
      case e: AssertionError =>
        failures += s"$name — ${e.getMessage}"
        println(s"  FAIL  $name — ${e.getMessage}")
      case e: Throwable =>
        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        failures += s"$name — ${e.getClass.getSimpleName}: $msg"
        println(s"  ERROR $name — ${e.getClass.getSimpleName}: $msg")

  def check(cond: Boolean, msg: => String): Unit =
    if !cond then throw new AssertionError(msg)

  // === Instantiation helpers =============================================

  /** Instantiate a wasi fixture against a fresh [[WasiContext.Collecting]]
    * and return both. Most tests want to invoke something and then read
    * back the captured stdout/stderr. */
  def instantiate(
      bytes:    Array[Byte],
      args:     Seq[String]                  = Seq.empty,
      envs:     Seq[(String, String)]        = Seq.empty,
      clock:    WasiContext.Clock            = WasiContext.systemClock,
      random:   Int => Array[Byte]           = WasiContext.defaultRandom,
      preopens: Seq[WasiContext.Preopen]     = Seq.empty,
  ): (ModuleInstance, WasiContext.Collecting) =
    val collecting = WasiContext.collecting(args, envs, clock, random, preopens)
    Runtime.instantiate(bytes, Seq(Wasi.preview1(collecting.context))) match
      case Right(inst) => (inst, collecting)
      case Left(err)   => throw new AssertionError(s"instantiate failed: $err")

  /** Read a NUL-terminated UTF-8 string starting at `addr` by polling
    * `load_byte` on the instance. Used by args/environ tests to read
    * back what `args_get` / `environ_get` planted into linear memory. */
  def readCString(inst: ModuleInstance, addr: Int): String =
    val sb   = new StringBuilder
    var p    = addr
    var loop = true
    while loop do
      inst.invoke("load_byte", Seq(I32(p))) match
        case Right(Seq(I32(b))) =>
          if b == 0 then loop = false
          else
            sb += b.toChar
            p  += 1
        case other =>
          throw new AssertionError(s"load_byte($p) result: $other")
    sb.toString
