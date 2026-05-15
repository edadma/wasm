package io.github.edadma.wasm

/** LEB128 codec — implemented from scratch because every WebAssembly
  * length prefix and integer immediate is encoded this way.
  *
  * Both decoders return the new cursor position alongside the value so callers
  * can chain reads without managing a separate counter.
  *
  * Every WebAssembly index and immediate fits in 32 bits; the readers cap at
  * five bytes (the most a 32-bit value can occupy) to avoid runaway scans on
  * malformed input.
  */
object Leb128:

  private inline val MaxBytesU32 = 5   // ceil(32 / 7)
  private inline val MaxBytesS32 = 5
  private inline val MaxBytesS64 = 10  // ceil(64 / 7)

  /** Read an unsigned LEB128 32-bit integer.
    * Returns `Right((value, newPos))` or `Left(InvalidModule(...))` on malformed/truncated input.
    */
  def readU32(bytes: Array[Byte], startPos: Int): Either[WasmError, (Int, Int)] =
    var result = 0
    var shift  = 0
    var p      = startPos
    var n      = 0
    while p < bytes.length && n < MaxBytesU32 do
      val b = bytes(p) & 0xff
      p += 1
      n += 1
      result |= (b & 0x7f) << shift
      if (b & 0x80) == 0 then return Right((result, p))
      shift += 7
    Left(WasmError.InvalidModule(s"truncated or oversized ULEB128 at byte $startPos"))

  /** Read a signed LEB128 32-bit integer.
    * The final byte's high data bit is sign-extended into bits above `shift`.
    */
  def readS32(bytes: Array[Byte], startPos: Int): Either[WasmError, (Int, Int)] =
    var result = 0
    var shift  = 0
    var p      = startPos
    var n      = 0
    while p < bytes.length && n < MaxBytesS32 do
      val b = bytes(p) & 0xff
      p += 1
      n += 1
      result |= (b & 0x7f) << shift
      shift += 7
      if (b & 0x80) == 0 then
        if shift < 32 && (b & 0x40) != 0 then
          result |= -(1 << shift)
        return Right((result, p))
    Left(WasmError.InvalidModule(s"truncated or oversized SLEB128 at byte $startPos"))

  /** Read a signed LEB128 64-bit integer (used for `i64.const` immediates).
    * Mirrors `readS32` but accumulates into a `Long` and caps at ten bytes.
    */
  def readS64(bytes: Array[Byte], startPos: Int): Either[WasmError, (Long, Int)] =
    var result: Long = 0L
    var shift        = 0
    var p            = startPos
    var n            = 0
    while p < bytes.length && n < MaxBytesS64 do
      val b = bytes(p) & 0xff
      p += 1
      n += 1
      result |= (b.toLong & 0x7fL) << shift
      shift += 7
      if (b & 0x80) == 0 then
        if shift < 64 && (b & 0x40) != 0 then
          result |= -(1L << shift)
        return Right((result, p))
    Left(WasmError.InvalidModule(s"truncated or oversized SLEB128(64) at byte $startPos"))
