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
    * Returns `Right((value, newPos))` or `Left(InvalidModule(...))` on
    * malformed/truncated input. Enforces u32 range: a 5-byte encoding's
    * final byte must have its upper 3 data bits clear, since 4×7 + 4 = 32
    * is the most that fits in u32. Anything past that is "integer too
    * large" per the spec.
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
      val terminator = (b & 0x80) == 0
      // On the 5th (final) byte, only the low 4 data bits are valid; the
      // upper 3 would overflow into bits 32-34 of a u32 if accepted.
      if n == MaxBytesU32 && (b & 0x70) != 0 then
        return Left(WasmError.InvalidModule(s"integer too large at byte $startPos"))
      result |= (b & 0x7f) << shift
      if terminator then return Right((result, p))
      shift += 7
    Left(WasmError.InvalidModule(s"integer representation too long at byte $startPos"))

  /** Read a signed LEB128 32-bit integer.
    * The final byte's high data bit is sign-extended into bits above `shift`.
    * Range check: a 5-byte encoding's final byte must be either 0x00..0x07
    * (positive, upper 3 data bits 0) or 0x78..0x7F + sign bit (negative,
    * upper 3 data bits 1 — sign extension of bit 3).
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
      val terminator = (b & 0x80) == 0
      // On the 5th (final) byte, bits 4-6 of the data must equal the
      // sign-extension of bit 3 (the in-range high bit). Valid data
      // values: 0x00..0x07 (positive, bits 4-6 = 0) or 0x78..0x7F
      // (negative, bits 4-6 = 1).
      if n == MaxBytesS32 then
        val data = b & 0x7f
        val signExtBits = if (data & 0x08) != 0 then 0x70 else 0x00
        if (data & 0x70) != signExtBits then
          return Left(WasmError.InvalidModule(s"integer too large at byte $startPos"))
      result |= (b & 0x7f) << shift
      shift += 7
      if terminator then
        if shift < 32 && (b & 0x40) != 0 then
          result |= -(1 << shift)
        return Right((result, p))
    Left(WasmError.InvalidModule(s"integer representation too long at byte $startPos"))

  /** Read a signed LEB128 64-bit integer (used for `i64.const` immediates).
    * Mirrors `readS32` but accumulates into a `Long` and caps at ten bytes.
    * Range check: on the 10th byte only bit 0 of the data carries the sign;
    * bits 1-6 must equal the sign-extension of bit 0 (all 0 or all 1).
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
      val terminator = (b & 0x80) == 0
      if n == MaxBytesS64 then
        val data = b & 0x7f
        val signExtBits = if (data & 0x01) != 0 then 0x7e else 0x00
        if (data & 0x7e) != signExtBits then
          return Left(WasmError.InvalidModule(s"integer too large at byte $startPos"))
      result |= (b.toLong & 0x7fL) << shift
      shift += 7
      if terminator then
        if shift < 64 && (b & 0x40) != 0 then
          result |= -(1L << shift)
        return Right((result, p))
    Left(WasmError.InvalidModule(s"integer representation too long at byte $startPos"))
