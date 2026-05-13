package io.github.edadma.wasm

import scala.collection.mutable.ArrayBuffer

/** Parser for the WebAssembly binary format (MVP subset).
  *
  * Recognised sections: Type (1), Import (2), Function (3), Table (4),
  * Memory (5), Global (6), Export (7), Element (9), Code (10), Data (11).
  * All other sections are skipped silently so we can be fed real-world
  * modules that include Custom, Start, DataCount, etc. without choking.
  *
  * Style note: internally the parser uses a private `ParseFail` exception for
  * control flow because the section/instruction stream has many nested reads
  * and threading `Either` through every one is needlessly verbose. The
  * exception never escapes — `parse` catches it at the boundary and returns
  * `Left(WasmError)`.
  */
object Parser:

  private val Magic   = Array[Byte](0x00, 0x61, 0x73, 0x6d) // "\0asm"
  private val Version = Array[Byte](0x01, 0x00, 0x00, 0x00) // version 1

  private final class ParseFail(val err: WasmError) extends RuntimeException(null, null, false, false)

  def parse(bytes: Array[Byte]): Either[WasmError, WasmModule] =
    try Right(parseInternal(bytes))
    catch
      case e: ParseFail                       => Left(e.err)
      case _: ArrayIndexOutOfBoundsException  => Left(WasmError.InvalidModule("unexpected end of input"))

  // === internals ===

  private def fail(err: WasmError): Nothing = throw new ParseFail(err)

  /** Mutable cursor — keeps the read code linear. All `read*` helpers
    * advance `pos`. Out-of-range or malformed reads `fail` with InvalidModule.
    */
  private final class Cursor(val bytes: Array[Byte]):
    var pos: Int = 0

    inline def remaining: Int = bytes.length - pos
    inline def hasMore:   Boolean = pos < bytes.length

    def readByte(): Int =
      if pos >= bytes.length then fail(WasmError.InvalidModule("EOF in readByte"))
      val b = bytes(pos) & 0xff
      pos += 1
      b

    def readU32(): Int =
      Leb128.readU32(bytes, pos) match
        case Right((v, p)) => pos = p; v
        case Left(e)       => fail(e)

    def readS32(): Int =
      Leb128.readS32(bytes, pos) match
        case Right((v, p)) => pos = p; v
        case Left(e)       => fail(e)

    def readBytes(n: Int): Array[Byte] =
      if n < 0 || pos + n > bytes.length then fail(WasmError.InvalidModule(s"truncated read of $n bytes"))
      val out = new Array[Byte](n)
      System.arraycopy(bytes, pos, out, 0, n)
      pos += n
      out

    def readName(): String =
      val n = readU32()
      // The MVP encoding is UTF-8; new String(bytes, "UTF-8") works on all three backends.
      new String(readBytes(n), "UTF-8")

  private def parseInternal(bytes: Array[Byte]): WasmModule =
    if bytes.length < 8 then fail(WasmError.InvalidMagic)
    var i = 0
    while i < 4 do
      if bytes(i) != Magic(i) then fail(WasmError.InvalidMagic)
      i += 1
    while i < 8 do
      if bytes(i) != Version(i - 4) then fail(WasmError.InvalidMagic)
      i += 1

    val c = new Cursor(bytes)
    c.pos = 8

    var types     = Vector.empty[FuncType]
    var imports   = Vector.empty[FuncImport]
    var functions = Vector.empty[Int]
    var tables    = Vector.empty[Table]
    var memories  = Vector.empty[MemoryLimits]
    var globals   = Vector.empty[Global]
    var exports   = Vector.empty[Export]
    var elements  = Vector.empty[ElementSegment]
    var codes     = Vector.empty[FuncBody]
    var data      = Vector.empty[DataSegment]

    while c.hasMore do
      val id      = c.readByte()
      val size    = c.readU32()
      val secEnd  = c.pos + size
      if secEnd > c.bytes.length then fail(WasmError.InvalidModule(s"section $id overflows file"))

      id match
        case 1  => types     = parseTypeSection(c)
        case 2  => imports   = parseImportSection(c)
        case 3  => functions = parseFunctionSection(c)
        case 4  => tables    = parseTableSection(c)
        case 5  => memories  = parseMemorySection(c)
        case 6  => globals   = parseGlobalSection(c)
        case 7  => exports   = parseExportSection(c)
        case 9  => elements  = parseElementSection(c)
        case 10 => codes     = parseCodeSection(c)
        case 11 => data      = parseDataSection(c)
        case _  => () // ignore Custom (0), Start (8), DataCount (12)
      c.pos = secEnd

    if codes.size != functions.size then
      fail(WasmError.InvalidModule(
        s"function/code section length mismatch: ${functions.size} types vs ${codes.size} bodies"))

    WasmModule(types, imports, functions, tables, memories, globals, exports, elements, codes, data)

  // === Type section ===

  private def parseTypeSection(c: Cursor): Vector[FuncType] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val tag = c.readByte()
      if tag != 0x60 then fail(WasmError.InvalidModule(s"expected functype tag 0x60, got 0x${tag.toHexString}"))
      FuncType(readValTypeVec(c), readValTypeVec(c))
    }

  private def readValTypeVec(c: Cursor): Vector[ValueType] =
    val n = c.readU32()
    Vector.tabulate(n)(_ => readValType(c))

  private def readValType(c: Cursor): ValueType =
    c.readByte() match
      case 0x7f => ValueType.I32Type
      case 0x7e => ValueType.I64Type
      case 0x7d => ValueType.F32Type
      case 0x7c => ValueType.F64Type
      case b    => fail(WasmError.InvalidModule(s"unknown valtype 0x${b.toHexString}"))

  // === Import section ===

  private def parseImportSection(c: Cursor): Vector[FuncImport] =
    val n   = c.readU32()
    val out = ArrayBuffer.empty[FuncImport]
    var i   = 0
    while i < n do
      val mod  = c.readName()
      val name = c.readName()
      c.readByte() match
        case 0x00 =>                                     // func
          out += FuncImport(mod, name, c.readU32())
        case 0x01 =>                                     // table — silently skipped.
          // NOTE: when imported tables are eventually surfaced (Phase 5),
          // they will occupy table indices 0..k-1 in the wasm namespace
          // ahead of any defined tables. Until then, a module that mixes
          // imported and defined tables would see its `call_indirect`
          // tableidx immediates misalign against our `tables` array. The
          // MVP allows at most one table, so single-defined-table modules
          // remain correct.
          c.readByte()                                   // elem reftype
          skipLimits(c)
        case 0x02 =>                                     // memory — skip
          skipLimits(c)
        case 0x03 =>                                     // global — skip (Phase 5)
          c.readByte()                                   // valtype
          c.readByte()                                   // mut
        case other =>
          fail(WasmError.InvalidModule(s"unknown import kind 0x${other.toHexString}"))
      i += 1
    out.toVector

  private def skipLimits(c: Cursor): Unit =
    val flag = c.readByte()
    c.readU32() // min
    if (flag & 0x01) != 0 then c.readU32() // max

  // === Function section ===

  private def parseFunctionSection(c: Cursor): Vector[Int] =
    val n = c.readU32()
    Vector.tabulate(n)(_ => c.readU32())

  // === Table section ===

  /** Parse Section 4. Per table: reftype byte (MVP funcref 0x70 only — the
    * future externref form 0x6F is rejected here with a clear diagnostic
    * until reference types are supported) followed by limits. */
  private def parseTableSection(c: Cursor): Vector[Table] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val refType = c.readByte()
      if refType != 0x70 then
        fail(WasmError.InvalidModule(
          s"unsupported table reftype 0x${refType.toHexString} (MVP supports only funcref 0x70)"))
      val lim = readLimits(c)
      Table(refType, lim.min, lim.max)
    }

  // === Memory section ===

  private def parseMemorySection(c: Cursor): Vector[MemoryLimits] =
    val n = c.readU32()
    Vector.tabulate(n)(_ => readLimits(c))

  private def readLimits(c: Cursor): MemoryLimits =
    val flag = c.readByte()
    val min  = c.readU32()
    val max  = if (flag & 0x01) != 0 then Some(c.readU32()) else None
    MemoryLimits(min, max)

  // === Global section ===

  /** Parse Section 6. Per global: valtype byte, mutability byte, init-expr.
    *
    * In MVP the init-expr is a single `*.const` instruction followed by the
    * `end` byte. `global.get` against an imported global is also legal here
    * per the spec, but we don't surface global imports yet (Phase 5), so the
    * `global.get` form is rejected with a clear diagnostic rather than
    * silently accepted with no live binding.
    */
  private def parseGlobalSection(c: Cursor): Vector[Global] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val vt  = readValType(c)
      val mut = c.readByte() match
        case 0x00 => false
        case 0x01 => true
        case b    => fail(WasmError.InvalidModule(s"unknown global mutability byte 0x${b.toHexString}"))
      Global(vt, mut, readConstExpr(c, vt))
    }

  // === Export section ===

  private def parseExportSection(c: Cursor): Vector[Export] =
    val n   = c.readU32()
    val out = ArrayBuffer.empty[Export]
    var i   = 0
    while i < n do
      val name = c.readName()
      val kind = c.readByte()
      val idx  = c.readU32()
      kind match
        case 0x00 => out += FuncExport(name, idx)
        case 0x01 => out += TableExport(name, idx)
        case 0x03 => out += GlobalExport(name, idx)
        case 0x02 => () // memory — silently ignored until Phase 4 surfaces it
        case other => fail(WasmError.InvalidModule(s"unknown export kind 0x${other.toHexString}"))
      i += 1
    out.toVector

  // === Element section ===

  /** Parse Section 9. The encoding has seven historical flag forms; Phase 3
    * implements the two active ones (flag 0 and flag 2). Flag 0 is the
    * common case wat2wasm emits for a top-level `(elem ...)`; flag 2
    * appears once a non-default tableidx is given. The other flags
    * (passive, declarative, active-with-elem-expr) are rejected explicitly
    * with a diagnostic naming the flag so a future implementation knows
    * exactly which form surfaced. */
  private def parseElementSection(c: Cursor): Vector[ElementSegment] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val flag = c.readU32()
      flag match
        case 0 =>
          // active, table 0, offset = i32.const expr, vec(funcidx)
          val offset = readConstI32Expr(c)
          val cnt    = c.readU32()
          val idxs   = Vector.tabulate(cnt)(_ => c.readU32())
          ElementSegment(0, offset, idxs)
        case 2 =>
          // active, explicit tableidx, offset = i32.const expr, elemkind byte
          // (must be 0x00 = funcref), vec(funcidx)
          val tableIdx = c.readU32()
          val offset   = readConstI32Expr(c)
          val ek       = c.readByte()
          if ek != 0x00 then
            fail(WasmError.InvalidModule(
              s"element segment elemkind 0x${ek.toHexString} not supported (MVP funcref only)"))
          val cnt  = c.readU32()
          val idxs = Vector.tabulate(cnt)(_ => c.readU32())
          ElementSegment(tableIdx, offset, idxs)
        case other =>
          fail(WasmError.InvalidModule(
            s"element segment flag $other not supported (MVP: flag 0 / flag 2 — active only)"))
    }

  // === Code section ===

  private def parseCodeSection(c: Cursor): Vector[FuncBody] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val bodySize = c.readU32()
      val bodyEnd  = c.pos + bodySize
      val nLocals  = c.readU32()
      val locals   = ArrayBuffer.empty[ValueType]
      var i = 0
      while i < nLocals do
        val count = c.readU32()
        val t     = readValType(c)
        var k     = 0
        while k < count do { locals += t; k += 1 }
        i += 1
      // Whatever is left in the body slot is the instruction stream (terminated by 0x0B).
      val bodyLen = bodyEnd - c.pos
      if bodyLen < 0 then fail(WasmError.InvalidModule("negative function body length"))
      val raw = c.readBytes(bodyLen)
      FuncBody(locals.toVector, raw)
    }

  // === Data section ===

  private def parseDataSection(c: Cursor): Vector[DataSegment] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val flag = c.readU32()
      flag match
        case 0 => // active, memory 0, offset = const expr
          val offset = readConstI32Expr(c)
          val len    = c.readU32()
          DataSegment(offset, c.readBytes(len))
        case 1 => // passive — ignored content
          val len = c.readU32()
          c.readBytes(len)
          fail(WasmError.InvalidModule("passive data segments not supported in MVP"))
        case 2 =>
          c.readU32() // memidx
          val offset = readConstI32Expr(c)
          val len    = c.readU32()
          DataSegment(offset, c.readBytes(len))
        case other =>
          fail(WasmError.InvalidModule(s"unknown data segment flag $other"))
    }

  /** Data-segment offsets are constrained to be i32 const exprs. Phase 1's
    * narrow reader stays as a thin wrapper around the type-checked
    * `readConstExpr` so the active-data parse keeps its old signature.
    */
  private def readConstI32Expr(c: Cursor): Int =
    readConstExpr(c, ValueType.I32Type) match
      case I32(v) => v
      case other  => fail(WasmError.InvalidModule(s"expected i32 const expr, got $other"))

  /** Read a constant initializer expression: a single `*.const` of the
    * expected value type, followed by `end`. The `global.get`-on-imported-
    * global form is also valid per spec, but until imports surface globals
    * (Phase 5) we reject it explicitly. f32/f64 immediates are 4 / 8 raw
    * little-endian IEEE-754 bytes (NOT LEB), the same encoding the
    * interpreter uses for `0x43` / `0x44` in-body.
    */
  private def readConstExpr(c: Cursor, expected: ValueType): Value =
    val op  = c.readByte()
    val v   = (op, expected) match
      case (0x41, ValueType.I32Type) => I32(c.readS32())
      case (0x42, ValueType.I64Type) =>
        Leb128.readS64(c.bytes, c.pos) match
          case Right((x, p)) => c.pos = p; I64(x)
          case Left(e)       => fail(e)
      case (0x43, ValueType.F32Type) =>
        val b   = c.readBytes(4)
        val bits = (b(0) & 0xff)        |
                   ((b(1) & 0xff) <<  8) |
                   ((b(2) & 0xff) << 16) |
                   ((b(3) & 0xff) << 24)
        F32(java.lang.Float.intBitsToFloat(bits))
      case (0x44, ValueType.F64Type) =>
        val b   = c.readBytes(8)
        val bits =
          (b(0) & 0xffL)        |
          ((b(1) & 0xffL) <<  8) |
          ((b(2) & 0xffL) << 16) |
          ((b(3) & 0xffL) << 24) |
          ((b(4) & 0xffL) << 32) |
          ((b(5) & 0xffL) << 40) |
          ((b(6) & 0xffL) << 48) |
          ((b(7) & 0xffL) << 56)
        F64(java.lang.Double.longBitsToDouble(bits))
      case (0x23, _) =>
        fail(WasmError.InvalidModule(
          "global.get in const expr requires an imported global, which isn't supported yet"))
      case (other, _) =>
        // Phrase the diagnostic in terms of the *.const mnemonic that the
        // declared type would have required, so it reads the same way the
        // wat source does.
        val mnemonic = expected match
          case ValueType.I32Type => "i32.const"
          case ValueType.I64Type => "i64.const"
          case ValueType.F32Type => "f32.const"
          case ValueType.F64Type => "f64.const"
        fail(WasmError.InvalidModule(
          s"expected $mnemonic in const expr, got 0x${other.toHexString}"))
    val end = c.readByte()
    if end != 0x0b then fail(WasmError.InvalidModule(s"expected end after const expr, got 0x${end.toHexString}"))
    v
