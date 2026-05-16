package io.github.edadma.wasm.spec

import io.github.edadma.wasm.*

/** Codec for the value records `wast2json` emits, plus the NaN-aware
  * comparator the spec requires.
  *
  * `wast2json` encodes every wasm value as `{"type": <type>, "value":
  * <string>}`:
  *
  *   - `i32` / `i64` — decimal string of the *unsigned* bit pattern
  *     (`"4294967295"` is i32 `-1`).
  *   - `f32` / `f64` — decimal string of the raw IEEE-754 bit pattern as
  *     an unsigned integer, OR the literal `"nan:canonical"` /
  *     `"nan:arithmetic"` on the expected side. (Argument floats never use
  *     the NaN literals — they're concrete bit patterns.)
  *   - `v128` — `{"lane_type": "i32" | "i64" | "f32" | "f64", "value":
  *     [<string>, ...]}` with lane-shape determining how many entries the
  *     array carries and how each entry decodes.
  *   - `externref` / `funcref` / `exnref` — `"null"` or a decimal id
  *     (we map non-null externref to a fresh boxed `Int`).
  */
private[spec] object SpecValue:

  /** What the spec expects to see on the result stack — either a concrete
    * value or one of the two NaN equivalence classes that the f32/f64 NaN
    * rules need. */
  sealed trait Expected
  final case class Exact(value: Value)             extends Expected
  final case class NanCanonical(width: Int)        extends Expected
  final case class NanArithmetic(width: Int)       extends Expected
  /** A v128 expectation — `lanes` carry the per-lane expected values, each
    * of which may itself be a NaN class for f32x4 / f64x2 lane shapes. */
  final case class V128Expected(laneType: String, lanes: Vector[Expected]) extends Expected
  /** A null reference of either funcref or externref. */
  final case class RefNullExpected(refType: RefType) extends Expected
  /** A non-null externref carrying a specific host id (wast2json hands the
    * runtime that id via the test action — we accept it on the way out by
    * boxing into `java.lang.Long`). */
  final case class RefExternExpected(id: Long)      extends Expected
  /** A non-null funcref. wast2json reports an opaque address that doesn't
    * line up with our funcidx numbering, so we accept any non-null funcref
    * here — good enough for the spec tests that only check "did the
    * indirect call resolve to a function ref" without tracking identity. */
  case object RefFuncAnyExpected extends Expected

  // ----------------------------------------------------------------------
  // Decoding wast2json value records
  // ----------------------------------------------------------------------

  /** Decode a wast2json value record on the *argument* side (always a
    * concrete bit pattern — no NaN literals on inputs). v128 arguments
    * are packed back into a 16-byte `V128` from the per-lane bit pattern
    * record `wast2json` emits. */
  def decodeArg(rec: Map[String, Any]): Value =
    rec("type") match
      case "v128" => decodeV128Arg(rec)
      case _ =>
        decodeExpected(rec) match
          case Exact(v)             => v
          case RefNullExpected(rt)  => RefNull(rt)
          case RefExternExpected(i) => RefExtern(java.lang.Long.valueOf(i))
          case other                => sys.error(s"argument cannot be a NaN class: $other")

  /** Pack a v128 argument record's lane-shaped value array back into a
    * little-endian 16-byte block. Mirrors `unpackLanes` but in reverse
    * — and rejects NaN class literals (arguments are always concrete). */
  private def decodeV128Arg(rec: Map[String, Any]): Value =
    val laneType = rec("lane_type").asInstanceOf[String]
    val raw      = rec("value").asInstanceOf[Vector[Any]].map(_.asInstanceOf[String])
    val bytes    = new Array[Byte](16)
    laneType match
      case "i8" =>
        var i = 0
        while i < 16 do
          bytes(i) = (java.lang.Long.parseUnsignedLong(raw(i)) & 0xffL).toByte
          i += 1
      case "i16" =>
        var i = 0
        while i < 8 do
          val v = (java.lang.Long.parseUnsignedLong(raw(i)) & 0xffffL).toInt
          bytes(i * 2)     = (v & 0xff).toByte
          bytes(i * 2 + 1) = ((v >>> 8) & 0xff).toByte
          i += 1
      case "i32" =>
        var i = 0
        while i < 4 do
          val v = java.lang.Long.parseUnsignedLong(raw(i)).toInt
          val o = i * 4
          bytes(o)     = (v & 0xff).toByte
          bytes(o + 1) = ((v >>> 8) & 0xff).toByte
          bytes(o + 2) = ((v >>> 16) & 0xff).toByte
          bytes(o + 3) = ((v >>> 24) & 0xff).toByte
          i += 1
      case "i64" =>
        var i = 0
        while i < 2 do
          val v = java.lang.Long.parseUnsignedLong(raw(i))
          val o = i * 8
          var k = 0
          while k < 8 do
            bytes(o + k) = ((v >>> (k * 8)) & 0xffL).toByte
            k += 1
          i += 1
      case "f32" =>
        var i = 0
        while i < 4 do
          val bits = java.lang.Long.parseUnsignedLong(raw(i)).toInt
          val o    = i * 4
          bytes(o)     = (bits & 0xff).toByte
          bytes(o + 1) = ((bits >>> 8) & 0xff).toByte
          bytes(o + 2) = ((bits >>> 16) & 0xff).toByte
          bytes(o + 3) = ((bits >>> 24) & 0xff).toByte
          i += 1
      case "f64" =>
        var i = 0
        while i < 2 do
          val bits = java.lang.Long.parseUnsignedLong(raw(i))
          val o    = i * 8
          var k    = 0
          while k < 8 do
            bytes(o + k) = ((bits >>> (k * 8)) & 0xffL).toByte
            k += 1
          i += 1
      case other => sys.error(s"bad v128 arg lane_type '$other'")
    V128(bytes)

  /** Decode a wast2json value record into an `Expected`. */
  def decodeExpected(rec: Map[String, Any]): Expected =
    rec("type") match
      case "i32"       => decodeI32(rec)
      case "i64"       => decodeI64(rec)
      case "f32"       => decodeF32(rec)
      case "f64"       => decodeF64(rec)
      case "v128"      => decodeV128(rec)
      case "externref" => decodeExternRef(rec)
      case "funcref"   => decodeFuncRef(rec)
      case "exnref"    => decodeExnRef(rec)
      case other       => sys.error(s"unsupported value type '$other'")

  private def decodeI32(rec: Map[String, Any]): Expected =
    val s = rec("value").asInstanceOf[String]
    Exact(I32(java.lang.Long.parseUnsignedLong(s).toInt))

  private def decodeI64(rec: Map[String, Any]): Expected =
    val s = rec("value").asInstanceOf[String]
    Exact(I64(java.lang.Long.parseUnsignedLong(s)))

  private def decodeF32(rec: Map[String, Any]): Expected =
    rec("value") match
      case "nan:canonical"  => NanCanonical(32)
      case "nan:arithmetic" => NanArithmetic(32)
      case s: String        =>
        val bits = java.lang.Long.parseUnsignedLong(s).toInt
        Exact(F32(java.lang.Float.intBitsToFloat(bits)))
      case other => sys.error(s"bad f32 value record: $other")

  private def decodeF64(rec: Map[String, Any]): Expected =
    rec("value") match
      case "nan:canonical"  => NanCanonical(64)
      case "nan:arithmetic" => NanArithmetic(64)
      case s: String        =>
        val bits = java.lang.Long.parseUnsignedLong(s)
        Exact(F64(java.lang.Double.longBitsToDouble(bits)))
      case other => sys.error(s"bad f64 value record: $other")

  private def decodeV128(rec: Map[String, Any]): Expected =
    val laneType = rec("lane_type").asInstanceOf[String]
    val raw      = rec("value").asInstanceOf[Vector[Any]].map(_.asInstanceOf[String])
    val lanes    = raw.map(s => decodeLane(laneType, s))
    V128Expected(laneType, lanes)

  private def decodeLane(laneType: String, s: String): Expected = laneType match
    case "i8"  => Exact(I32((java.lang.Long.parseUnsignedLong(s) & 0xffL).toInt))
    case "i16" => Exact(I32((java.lang.Long.parseUnsignedLong(s) & 0xffffL).toInt))
    case "i32" => Exact(I32(java.lang.Long.parseUnsignedLong(s).toInt))
    case "i64" => Exact(I64(java.lang.Long.parseUnsignedLong(s)))
    case "f32" => s match
      case "nan:canonical"  => NanCanonical(32)
      case "nan:arithmetic" => NanArithmetic(32)
      case bits             => Exact(F32(java.lang.Float.intBitsToFloat(java.lang.Long.parseUnsignedLong(bits).toInt)))
    case "f64" => s match
      case "nan:canonical"  => NanCanonical(64)
      case "nan:arithmetic" => NanArithmetic(64)
      case bits             => Exact(F64(java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(bits))))
    case other => sys.error(s"bad v128 lane_type '$other'")

  private def decodeExternRef(rec: Map[String, Any]): Expected =
    rec.get("value") match
      case Some("null") | None => RefNullExpected(RefType.ExternRef)
      case Some(s: String)     => RefExternExpected(java.lang.Long.parseUnsignedLong(s))
      case other               => sys.error(s"bad externref value: $other")

  private def decodeFuncRef(rec: Map[String, Any]): Expected =
    rec.get("value") match
      case Some("null") | None => RefNullExpected(RefType.FuncRef)
      case Some(_)             => RefFuncAnyExpected

  private def decodeExnRef(rec: Map[String, Any]): Expected =
    rec.get("value") match
      case Some("null") | None => RefNullExpected(RefType.ExnRef)
      case other               => sys.error(s"non-null exnref not yet supported: $other")

  // ----------------------------------------------------------------------
  // Comparing actual results against expected
  // ----------------------------------------------------------------------

  /** True iff `actual` matches `expected` under the spec's equality rules.
    *
    *   - integers compare bitwise
    *   - floats compare bitwise except where the expected side is a
    *     `nan:canonical` / `nan:arithmetic` class
    *   - v128 compares lane-wise with the same rules
    *   - reference types compare on kind + id
    *
    * `Float.NaN == anything` is false in JVM, so we route through raw
    * bits and treat the NaN classes explicitly.
    */
  def matches(expected: Expected, actual: Value): Boolean = (expected, actual) match
    case (Exact(I32(e)), I32(a))             => e == a
    case (Exact(I64(e)), I64(a))             => e == a
    case (Exact(F32(e)), F32(a))             =>
      java.lang.Float.floatToRawIntBits(e) == java.lang.Float.floatToRawIntBits(a)
    case (Exact(F64(e)), F64(a))             =>
      java.lang.Double.doubleToRawLongBits(e) == java.lang.Double.doubleToRawLongBits(a)
    case (NanCanonical(32), F32(a))          => isCanonicalNaN32(a)
    case (NanCanonical(64), F64(a))          => isCanonicalNaN64(a)
    case (NanArithmetic(32), F32(a))         => a.isNaN
    case (NanArithmetic(64), F64(a))         => a.isNaN
    case (V128Expected(lt, lanes), V128(bs)) => matchesV128(lt, lanes, bs)
    case (RefNullExpected(rt), RefNull(rt2)) => rt == rt2
    case (RefExternExpected(id), RefExtern(v)) =>
      v match
        case l: java.lang.Long => l.longValue() == id
        case _                 => false
    case (RefFuncAnyExpected, RefFunc(_)) => true
    case _ => false

  /** A canonical f32 NaN: exponent all-1s, mantissa MSB set, all other
    * mantissa bits clear, sign bit either. Bit pattern is 0x7fc00000 (+)
    * or 0xffc00000 (−). */
  private def isCanonicalNaN32(v: Float): Boolean =
    val bits = java.lang.Float.floatToRawIntBits(v) & 0x7fffffff
    bits == 0x7fc00000

  /** Canonical f64 NaN: 0x7ff8000000000000 (sign bit irrelevant). */
  private def isCanonicalNaN64(v: Double): Boolean =
    val bits = java.lang.Double.doubleToRawLongBits(v) & 0x7fffffffffffffffL
    bits == 0x7ff8000000000000L

  private def matchesV128(laneType: String, lanes: Vector[Expected], bits: Array[Byte]): Boolean =
    val laneVals = unpackLanes(laneType, bits)
    laneVals.length == lanes.length &&
      lanes.indices.forall(i => matches(lanes(i), laneVals(i)))

  private def unpackLanes(laneType: String, bits: Array[Byte]): Vector[Value] = laneType match
    case "i8"  => (0 until 16).iterator.map(i => I32(bits(i).toInt & 0xff)).toVector
    case "i16" => (0 until 8).iterator.map { i =>
      val lo = bits(i * 2).toInt & 0xff
      val hi = bits(i * 2 + 1).toInt & 0xff
      I32((hi << 8) | lo)
    }.toVector
    case "i32" => (0 until 4).iterator.map { i =>
      val o = i * 4
      val v = (bits(o).toInt & 0xff) | ((bits(o + 1).toInt & 0xff) << 8) |
        ((bits(o + 2).toInt & 0xff) << 16) | (bits(o + 3).toInt << 24)
      I32(v)
    }.toVector
    case "i64" => (0 until 2).iterator.map { i =>
      val o = i * 8
      var v = 0L
      var k = 0
      while k < 8 do
        v |= (bits(o + k).toLong & 0xffL) << (k * 8)
        k += 1
      I64(v)
    }.toVector
    case "f32" => (0 until 4).iterator.map { i =>
      val o = i * 4
      val ib = (bits(o).toInt & 0xff) | ((bits(o + 1).toInt & 0xff) << 8) |
        ((bits(o + 2).toInt & 0xff) << 16) | (bits(o + 3).toInt << 24)
      F32(java.lang.Float.intBitsToFloat(ib))
    }.toVector
    case "f64" => (0 until 2).iterator.map { i =>
      val o = i * 8
      var v = 0L
      var k = 0
      while k < 8 do
        v |= (bits(o + k).toLong & 0xffL) << (k * 8)
        k += 1
      F64(java.lang.Double.longBitsToDouble(v))
    }.toVector
    case other => sys.error(s"bad lane_type '$other'")
