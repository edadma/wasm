package io.github.edadma.wasm

import scala.collection.mutable.ArrayBuffer

/** Shared test framework and helpers used by every category file.
  *
  * State (`passed`, `failures`) lives here in a single object so that each
  * category's `run()` writes to the same counters; `InterpreterTest.main`
  * reads them at the end to emit the summary.
  *
  * Zero external deps — runs identically on JVM, Scala.js, and Scala Native.
  */
object TestSupport:

  // === Tiny test framework ================================================

  var passed: Int                   = 0
  val failures: ArrayBuffer[String] = ArrayBuffer.empty

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
        failures += s"$name — ${e.getClass.getSimpleName}: ${e.getMessage}"
        println(s"  ERROR $name — ${e.getClass.getSimpleName}: ${e.getMessage}")

  def check(cond: Boolean, msg: => String): Unit =
    if !cond then throw new AssertionError(msg)

  def runRight[A](e: Either[WasmError, A]): A = e match
    case Right(a)  => a
    case Left(err) => throw new AssertionError(s"unexpected error: $err")

  /** Same as [[runRight]] but discards the success value. Use this when
    * a test only cares that an invocation succeeded — the Seq[Value] /
    * Unit result would otherwise trip -Wnonunit-statement at call sites
    * that just want the side-effect (global mutation, memory write). */
  def runOk[A](e: Either[WasmError, A]): Unit = e match
    case Right(_)  => ()
    case Left(err) => throw new AssertionError(s"unexpected error: $err")

  // === Instantiation + invocation helpers =================================

  def instantiate(bytes: Array[Byte], env: HostModule = EnvModule.default): ModuleInstance =
    runRight(Runtime.instantiate(bytes, Seq(env)))

  def callI32(inst: ModuleInstance, name: String, args: Int*): Int =
    val results = runRight(inst.invoke(name, args.map(I32(_))))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case I32(v) => v
      case other  => throw new AssertionError(s"$name returned $other, expected I32")

  /** Same shape as `callI32` but for functions returning an i64; arguments
    * are pre-wrapped `Value`s so the caller can mix I32 + I64 freely
    * (memory tests, mixed-type helpers, etc.). */
  def callI64(inst: ModuleInstance, name: String, args: Value*): Long =
    val results = runRight(inst.invoke(name, args))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case I64(v) => v
      case other  => throw new AssertionError(s"$name returned $other, expected I64")

  /** Same shape but for f32-returning functions. */
  def callF32(inst: ModuleInstance, name: String, args: Value*): Float =
    val results = runRight(inst.invoke(name, args))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case F32(v) => v
      case other  => throw new AssertionError(s"$name returned $other, expected F32")

  /** Same shape but for f64-returning functions. */
  def callF64(inst: ModuleInstance, name: String, args: Value*): Double =
    val results = runRight(inst.invoke(name, args))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case F64(v) => v
      case other  => throw new AssertionError(s"$name returned $other, expected F64")

  /** Convenience: invoke a function and pull out an `I32` result without
    * boxing the args into a sequence at the call site (used by the f32
    * compare tests, whose return type is i32). */
  def callI32V(inst: ModuleInstance, name: String, args: Value*): Int =
    val results = runRight(inst.invoke(name, args))
    check(results.size == 1, s"$name returned ${results.size} values, expected 1")
    results.head match
      case I32(v) => v
      case other  => throw new AssertionError(s"$name returned $other, expected I32")

  def expectError(
      inst: ModuleInstance,
      name: String,
      args: Seq[Value],
  )(matcher: PartialFunction[WasmError, Boolean]): Unit =
    inst.invoke(name, args) match
      case Right(v)  => check(false, s"$name expected error, got Right($v)")
      case Left(err) =>
        if matcher.isDefinedAt(err) then check(matcher(err), s"$name unexpected error: $err")
        else check(false, s"$name unexpected error: $err")

  /** Static-error variant of [[expectError]] — surfaced at instantiation
    * by the Phase 6 validator, not by invocation. Use this for tests
    * that hand a malformed binary to `Runtime.instantiate` and expect
    * a specific `WasmError` shape back. */
  def expectInstantiateError(
      bytes: Array[Byte],
      env:   HostModule = EnvModule.default,
  )(matcher: PartialFunction[WasmError, Boolean]): Unit =
    Runtime.instantiate(bytes, Seq(env)) match
      case Right(_)  => check(false, "expected instantiation error, got Right(_)")
      case Left(err) =>
        if matcher.isDefinedAt(err) then check(matcher(err), s"unexpected error: $err")
        else check(false, s"unexpected error: $err")

  // === SIMD helpers =======================================================
  //
  // Shared by every `Simd*Tests` suite. `bytesEq` + `callV128` were copy-
  // pasted into all 5 chunk-A..E suites; chunk F would have made it 6, and
  // the dedupe-trigger heuristic in CLAUDE.md is 3 — so they live here now.
  // `fromI{8,16,32,64}` + `fromF{32,64}` are the LE byte-array builders for
  // each lane shape; they're inlined per-suite where used, all little-
  // endian, all checking the lane-count up front.

  object simd:

    def bytesEq(actual: Array[Byte], expected: Array[Byte]): Boolean =
      if actual.length != expected.length then false
      else
        var i  = 0
        var ok = true
        while ok && i < actual.length do
          if actual(i) != expected(i) then ok = false
          i += 1
        ok

    def callV128(inst: ModuleInstance, name: String, args: Value*): Array[Byte] =
      val results = runRight(inst.invoke(name, args))
      check(results.size == 1, s"$name returned ${results.size} values, expected 1")
      results.head match
        case V128(bs) => bs
        case other    => throw new AssertionError(s"$name returned $other, expected V128")

    def fromI8(values: Int*): Array[Byte] =
      require(values.length == 16, s"fromI8 needs 16 values, got ${values.length}")
      values.iterator.map(_.toByte).toArray

    def fromI16(values: Int*): Array[Byte] =
      require(values.length == 8, s"fromI16 needs 8 values, got ${values.length}")
      val r = new Array[Byte](16)
      var i = 0
      while i < 8 do
        r(i * 2)     = (values(i) & 0xff).toByte
        r(i * 2 + 1) = ((values(i) >>> 8) & 0xff).toByte
        i += 1
      r

    def fromI32(values: Int*): Array[Byte] =
      require(values.length == 4, s"fromI32 needs 4 values, got ${values.length}")
      val r = new Array[Byte](16)
      var i = 0
      while i < 4 do
        val v = values(i)
        r(i * 4)     = (v & 0xff).toByte
        r(i * 4 + 1) = ((v >>> 8)  & 0xff).toByte
        r(i * 4 + 2) = ((v >>> 16) & 0xff).toByte
        r(i * 4 + 3) = ((v >>> 24) & 0xff).toByte
        i += 1
      r

    def fromI64(values: Long*): Array[Byte] =
      require(values.length == 2, s"fromI64 needs 2 values, got ${values.length}")
      val r = new Array[Byte](16)
      var i = 0
      while i < 2 do
        val v = values(i)
        var k = 0
        while k < 8 do
          r(i * 8 + k) = ((v >>> (k * 8)) & 0xffL).toByte
          k += 1
        i += 1
      r

    /** Build a 16-byte v128 with four f32 lanes (little-endian). Uses the
      * raw bit conversion so NaN payloads round-trip unchanged — chunk F
      * float tests rely on this. */
    def fromF32(values: Float*): Array[Byte] =
      require(values.length == 4, s"fromF32 needs 4 values, got ${values.length}")
      val r = new Array[Byte](16)
      var i = 0
      while i < 4 do
        val bits = java.lang.Float.floatToRawIntBits(values(i))
        r(i * 4)     = (bits & 0xff).toByte
        r(i * 4 + 1) = ((bits >>> 8)  & 0xff).toByte
        r(i * 4 + 2) = ((bits >>> 16) & 0xff).toByte
        r(i * 4 + 3) = ((bits >>> 24) & 0xff).toByte
        i += 1
      r

    /** Build a 16-byte v128 with two f64 lanes (little-endian). Uses the
      * raw bit conversion so NaN payloads round-trip unchanged. */
    def fromF64(values: Double*): Array[Byte] =
      require(values.length == 2, s"fromF64 needs 2 values, got ${values.length}")
      val r = new Array[Byte](16)
      var i = 0
      while i < 2 do
        val bits = java.lang.Double.doubleToRawLongBits(values(i))
        var k    = 0
        while k < 8 do
          r(i * 8 + k) = ((bits >>> (k * 8)) & 0xffL).toByte
          k += 1
        i += 1
      r

  // === Binary-patching helpers ============================================

  /** Replace one byte of a fixture copy. */
  def patchByte(src: Array[Byte], index: Int, newByte: Int): Array[Byte] =
    val out = src.clone()
    out(index) = newByte.toByte
    out

  /** Replace the first occurrence of `oldByte` with `newByte` in a copy. */
  def patchFirst(src: Array[Byte], oldByte: Int, newByte: Int): Array[Byte] =
    val idx = src.indexOf(oldByte.toByte)
    check(idx >= 0, s"byte 0x${oldByte.toHexString} not found in source")
    patchByte(src, idx, newByte)

  /** Compact byte literal helper. `b(0x00, 0x61, ...)` is shorter than the
    * `Array(0x00.toByte, ...)` form. */
  def b(xs: Int*): Array[Byte] = xs.iterator.map(_.toByte).toArray

  /** Minimal valid module header: magic + version. */
  val Header: Array[Byte] = b(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
