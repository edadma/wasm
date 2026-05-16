package io.github.edadma.wasm

import scala.collection.mutable.ArrayBuffer

/** Parser for the WebAssembly binary format.
  *
  * Recognised sections: Type (1), Import (2), Function (3), Table (4),
  * Memory (5), Global (6), Export (7), Start (8), Element (9), Code (10),
  * Data (11). Custom (0) and DataCount (12) are skipped silently so we
  * can be fed real-world modules that include them without choking.
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
      val n     = readU32()
      val bs    = readBytes(n)
      // Spec: every byte sequence in a `name` (import module/field, export
      // name, custom section id, etc.) is the UTF-8 encoding of a sequence
      // of code points. Java's `new String(bytes, "UTF-8")` silently maps
      // invalid bytes to U+FFFD; we have to walk the bytes ourselves to
      // surface RFC 3629 violations as `InvalidModule`. After validation
      // the String construction round-trips byte-for-byte.
      validateUtf8(bs) match
        case Some(err) => fail(WasmError.InvalidModule(err))
        case None      => new String(bs, "UTF-8")

  /** RFC 3629 strict UTF-8 walker. Returns `None` if the byte sequence is
    * valid UTF-8, or `Some(diagnostic)` naming the offending byte and the
    * rule it violated. Rejects: stray continuation bytes, lead bytes
    * 0xC0/0xC1 (overlong 2-byte forms), surrogate codepoints (U+D800..
    * U+DFFF, lead 0xED with continuation >= 0xA0), values past U+10FFFF
    * (lead 0xF4 with continuation >= 0x90, or any lead 0xF5..0xFF), and
    * truncated multi-byte sequences. */
  private def validateUtf8(bs: Array[Byte]): Option[String] =
    var i = 0
    while i < bs.length do
      val b0 = bs(i) & 0xff
      if b0 < 0x80 then
        i += 1
      else if b0 < 0xC2 then
        return Some(s"name: invalid utf8 lead byte 0x${b0.toHexString} at offset $i")
      else if b0 < 0xE0 then
        if i + 1 >= bs.length then
          return Some(s"name: truncated utf8 sequence at offset $i")
        val b1 = bs(i + 1) & 0xff
        if (b1 & 0xC0) != 0x80 then
          return Some(s"name: invalid utf8 continuation 0x${b1.toHexString} at offset ${i + 1}")
        i += 2
      else if b0 < 0xF0 then
        if i + 2 >= bs.length then
          return Some(s"name: truncated utf8 sequence at offset $i")
        val b1 = bs(i + 1) & 0xff
        val b2 = bs(i + 2) & 0xff
        if b0 == 0xE0 && b1 < 0xA0 then
          return Some(s"name: overlong utf8 3-byte sequence at offset $i")
        if b0 == 0xED && b1 >= 0xA0 then
          return Some(s"name: surrogate codepoint in utf8 at offset $i")
        if (b1 & 0xC0) != 0x80 then
          return Some(s"name: invalid utf8 continuation 0x${b1.toHexString} at offset ${i + 1}")
        if (b2 & 0xC0) != 0x80 then
          return Some(s"name: invalid utf8 continuation 0x${b2.toHexString} at offset ${i + 2}")
        i += 3
      else if b0 < 0xF5 then
        if i + 3 >= bs.length then
          return Some(s"name: truncated utf8 sequence at offset $i")
        val b1 = bs(i + 1) & 0xff
        val b2 = bs(i + 2) & 0xff
        val b3 = bs(i + 3) & 0xff
        if b0 == 0xF0 && b1 < 0x90 then
          return Some(s"name: overlong utf8 4-byte sequence at offset $i")
        if b0 == 0xF4 && b1 >= 0x90 then
          return Some(s"name: utf8 codepoint past U+10FFFF at offset $i")
        if (b1 & 0xC0) != 0x80 then
          return Some(s"name: invalid utf8 continuation 0x${b1.toHexString} at offset ${i + 1}")
        if (b2 & 0xC0) != 0x80 then
          return Some(s"name: invalid utf8 continuation 0x${b2.toHexString} at offset ${i + 2}")
        if (b3 & 0xC0) != 0x80 then
          return Some(s"name: invalid utf8 continuation 0x${b3.toHexString} at offset ${i + 3}")
        i += 4
      else
        return Some(s"name: invalid utf8 lead byte 0x${b0.toHexString} at offset $i")
    None

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
    var start     = Option.empty[Int]
    // Section 12 (Data Count). Required by spec for any module that uses
    // `memory.init` / `data.drop`. We capture it on parse; the validator
    // gates those ops on its presence + agreement with `data.length`.
    var dataCount  = Option.empty[Int]
    var funcNames     = Map.empty[Int, String]
    var tagImports    = Vector.empty[TagImport]
    var tags          = Vector.empty[Tag]
    var globalImports = Vector.empty[GlobalImport]

    // Spec: known section IDs are 0..13. Anything else is "malformed
    // section id". Non-custom sections must appear at most once and in
    // canonical *logical* order — the numeric IDs are NOT monotonically
    // ascending because the EH proposal slotted Tag (13) between Memory
    // (5) and Global (6), and bulk-memory slotted DataCount (12) between
    // Element (9) and Code (10). `sectionOrder` maps id → logical
    // position so we can enforce ascending order across both insertions.
    val sectionOrder: Array[Int] = Array(
      /*  0 Custom    */ -1,  // handled separately
      /*  1 Type      */ 1,
      /*  2 Import    */ 2,
      /*  3 Function  */ 3,
      /*  4 Table     */ 4,
      /*  5 Memory    */ 5,
      /*  6 Global    */ 7,
      /*  7 Export    */ 8,
      /*  8 Start     */ 9,
      /*  9 Element   */ 10,
      /* 10 Code      */ 12,
      /* 11 Data      */ 13,
      /* 12 DataCount */ 11,
      /* 13 Tag       */ 6,
    )
    var lastNonCustomPos = 0
    while c.hasMore do
      val id      = c.readByte()
      val size    = c.readU32()
      val secEnd  = c.pos + size
      if secEnd > c.bytes.length then fail(WasmError.InvalidModule(s"section $id: length out of bounds"))
      if id < 0 || id > 13 then
        fail(WasmError.InvalidModule(s"malformed section id 0x${id.toHexString}"))
      if id != 0 then
        val pos = sectionOrder(id)
        if pos <= lastNonCustomPos then
          fail(WasmError.InvalidModule(s"unexpected content after last section: duplicate or out-of-order section id $id"))
        lastNonCustomPos = pos

      id match
        case 0  => funcNames = parseCustomSection(c, secEnd, funcNames)       // section 0 is "custom" — `name` is one of these
        case 1  => types     = parseTypeSection(c)
        case 2  =>
          val (funcImps, tagImps, globImps) = parseImportSection(c)
          imports       = funcImps
          tagImports    = tagImps
          globalImports = globImps
        case 3  => functions = parseFunctionSection(c)
        case 4  => tables    = parseTableSection(c)
        case 5  => memories  = parseMemorySection(c)
        case 6  => globals   = parseGlobalSection(c)
        case 7  => exports   = parseExportSection(c)
        case 8  => start     = Some(parseStartSection(c))
        case 9  => elements  = parseElementSection(c)
        case 10 => codes     = parseCodeSection(c)
        case 11 => data      = parseDataSection(c)
        case 12 => dataCount = Some(c.readU32())                              // Section 12 (Data Count)
        case 13 => tags      = parseTagSection(c)                             // Section 13 (Tag) — EH proposal
      // Spec: after parsing a section, the cursor must land exactly on
      // the declared section end — under-consumed bytes are "section
      // size mismatch" (the declared size was wrong) and over-consumed
      // bytes mean we read past where the section header said we should.
      // Custom sections are allowed to leave trailing payload (any
      // bytes after the recognised content are arbitrary debug info),
      // so the position is forced to secEnd for id == 0.
      if id == 0 then
        c.pos = secEnd
      else if c.pos != secEnd then
        fail(WasmError.InvalidModule(s"section $id: section size mismatch"))

    if codes.size != functions.size then
      fail(WasmError.InvalidModule(
        s"function/code section length mismatch: ${functions.size} types vs ${codes.size} bodies"))

    // Per spec: if Section 12 (DataCount) is present, its value MUST equal
    // the number of segments declared by Section 11 (Data). The validator
    // separately gates `memory.init` / `data.drop` on DataCount presence.
    dataCount.foreach { n =>
      if n != data.size then
        fail(WasmError.InvalidModule(
          s"DataCount section value $n disagrees with data section size ${data.size}"))
    }

    WasmModule(
      types       = types,
      imports     = imports,
      functions   = functions,
      tables      = tables,
      memories    = memories,
      globals     = globals,
      exports     = exports,
      elements    = elements,
      codes       = codes,
      data        = data,
      startFunction = start,
      dataCount   = dataCount,
      funcNames     = funcNames,
      tagImports    = tagImports,
      tags          = tags,
      globalImports = globalImports,
    )

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
      // Reference-types proposal (Phase 8.C): funcref / externref now
      // appear as valtypes alongside the four scalar types — they're
      // legal in function signatures, local declarations, and global
      // value types.
      case 0x70 => ValueType.FuncRefType
      case 0x6f => ValueType.ExternRefType
      // SIMD proposal (Phase 8.E): v128 is a first-class valtype with wire
      // byte 0x7B. Legal anywhere the scalar types are — params, results,
      // locals, globals, blocktypes.
      case 0x7b => ValueType.V128Type
      // try_table proposal (modern EH): exnref valtype, wire byte 0x69.
      // Legal as param/result/local/global/blocktype. Carries a captured
      // wasm exception via `catch_ref` / `throw_ref`.
      case 0x69 => ValueType.ExnRefType
      case b    => fail(WasmError.InvalidModule(s"unknown valtype 0x${b.toHexString}"))

  /** Read a [[RefType]] byte (0x70 funcref / 0x6F externref). Used by
    * Section 4 (tables), Section 9 (element segments), and `ref.null`'s
    * immediate. Anything else is an `InvalidModule`. */
  private def readRefType(c: Cursor, context: String): RefType =
    val b = c.readByte()
    RefType.fromByte(b).getOrElse(
      fail(WasmError.InvalidModule(s"$context: unknown reftype 0x${b.toHexString} (expected 0x70 funcref or 0x6F externref)")))

  // === Import section ===

  private def parseImportSection(c: Cursor): (Vector[FuncImport], Vector[TagImport], Vector[GlobalImport]) =
    val n     = c.readU32()
    val out   = ArrayBuffer.empty[FuncImport]
    val tags  = ArrayBuffer.empty[TagImport]
    val globs = ArrayBuffer.empty[GlobalImport]
    var i     = 0
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
          // Core spec allows at most one table per module, so single-
          // defined-table modules remain correct.
          val _ = c.readByte()                           // elem reftype
          skipLimits(c)
        case 0x02 =>                                     // memory — skip
          skipLimits(c)
        case 0x03 =>                                     // global
          val vt  = readValType(c)
          val mut = c.readByte()
          if mut != 0x00 && mut != 0x01 then
            fail(WasmError.InvalidModule(s"global import ${mod}.${name}: invalid mutability byte 0x${mut.toHexString}"))
          globs += GlobalImport(mod, name, vt, mut == 0x01)
        case 0x04 =>                                     // tag (EH proposal)
          // Wire shape: attribute byte (must be 0x00 = exception) + typeidx u32.
          val attr = c.readByte()
          if attr != 0x00 then
            fail(WasmError.InvalidModule(s"tag import ${mod}.${name}: unknown attribute 0x${attr.toHexString}"))
          tags += TagImport(mod, name, c.readU32())
        case other =>
          fail(WasmError.InvalidModule(s"unknown import kind 0x${other.toHexString}"))
      i += 1
    (out.toVector, tags.toVector, globs.toVector)

  private def skipLimits(c: Cursor): Unit =
    val flag = c.readByte()
    val _ = c.readU32() // min
    if (flag & 0x01) != 0 then
      val _ = c.readU32() // max

  // === Function section ===

  private def parseFunctionSection(c: Cursor): Vector[Int] =
    val n = c.readU32()
    Vector.tabulate(n)(_ => c.readU32())

  // === Table section ===

  /** Parse Section 4. Per table: reftype byte (Phase 8.C accepts funcref
    * 0x70 + externref 0x6F) followed by limits. */
  private def parseTableSection(c: Cursor): Vector[Table] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val rt  = readRefType(c, "table section")
      val lim = readTableLimits(c)
      Table(rt, lim.min, lim.max)
    }

  // === Memory section ===

  private def parseMemorySection(c: Cursor): Vector[MemoryLimits] =
    val n = c.readU32()
    Vector.tabulate(n)(_ => readMemLimits(c))

  /** Memory limits flag byte:
    *   bit 0x01 — has-max
    *   bit 0x02 — shared (threads proposal)
    * Memory64 (bit 0x04) isn't surfaced yet — when it lands it'll join here.
    *
    * The threads proposal pins shared memories to a known maximum so a host
    * can size its bookkeeping up front; we enforce `shared ⇒ has-max` at
    * parse time, with a clear diagnostic on violation. */
  private def readMemLimits(c: Cursor): MemoryLimits =
    val flag    = c.readByte()
    val hasMax  = (flag & 0x01) != 0
    val shared  = (flag & 0x02) != 0
    val unknown = (flag & ~0x03) != 0
    if unknown then fail(WasmError.InvalidModule(s"unknown memory limits flag 0x${flag.toHexString}"))
    if shared && !hasMax then
      fail(WasmError.InvalidModule("shared memory requires a maximum (limits flag 0x03)"))
    val min = c.readU32()
    val max = if hasMax then Some(c.readU32()) else None
    MemoryLimits(min, max, shared)

  /** Table limits flag byte — only bit 0x01 (has-max). The `shared` bit is
    * memory-only per the threads proposal; reject it explicitly so a binary
    * trying to declare a shared table fails with a clear diagnostic instead
    * of silently parsing as a regular table. */
  private def readTableLimits(c: Cursor): MemoryLimits =
    val flag    = c.readByte()
    val hasMax  = (flag & 0x01) != 0
    val unknown = (flag & ~0x01) != 0
    if unknown then fail(WasmError.InvalidModule(s"unknown table limits flag 0x${flag.toHexString}"))
    val min = c.readU32()
    val max = if hasMax then Some(c.readU32()) else None
    MemoryLimits(min, max, shared = false)

  // === Global section ===

  /** Parse Section 6. Per global: valtype byte, mutability byte, init-expr.
    *
    * The init-expr is a `ConstInit` — either a folded `*.const` /
    * `v128.const` / `ref.null` / `ref.func` literal, or a `global.get`
    * referencing an imported, immutable global of matching type. Type
    * agreement for the `global.get` form is checked at validation time
    * (the parser doesn't have the imported globals' typing yet).
    */
  private def parseGlobalSection(c: Cursor): Vector[Global] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val vt  = readValType(c)
      val mut = c.readByte() match
        case 0x00 => false
        case 0x01 => true
        case b    => fail(WasmError.InvalidModule(s"unknown global mutability byte 0x${b.toHexString}"))
      Global(vt, mut, readConstInitExpr(c))
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
        case 0x02 => out += MemoryExport(name, idx)
        case 0x03 => out += GlobalExport(name, idx)
        case 0x04 => out += TagExport(name, idx)           // EH proposal
        case other => fail(WasmError.InvalidModule(s"unknown export kind 0x${other.toHexString}"))
      i += 1
    out.toVector

  // === Start section ===

  /** Parse Section 8: a single LEB u32 funcidx. The funcidx is validated
    * at instantiation (against `funcs.size` and the function's signature
    * — Start requires `() -> ()`) rather than here, because parsing
    * doesn't have visibility into resolved imports' types. */
  private def parseStartSection(c: Cursor): Int = c.readU32()

  // === Custom sections ===

  /** Parse a Section 0 custom section. The section's name comes first as a
    * UTF-8 length-prefixed string; the rest of the bytes are the section's
    * payload, format chosen per name.
    *
    * We currently recognise one name: `name`, subsection 1 (function
    * names). The "name" custom section was originally specified for
    * scoped debug info; subsection 1 is the only piece that helps user-
    * facing diagnostics — function names show up in our `function <N>
    * (foo): byte offset …` error format when present. Subsection 0
    * (module name) and 2 (local names) and any later subsections are
    * skipped silently. Any unknown custom-section name is also skipped.
    *
    * The parser is best-effort: a malformed payload doesn't trap the
    * whole module load, it just leaves the function-name map alone. This
    * matches wasmtime / wabt behaviour — `name` is debug info, and a
    * busted debug section shouldn't prevent the program from running. */
  private def parseCustomSection(c: Cursor, secEnd: Int, current: Map[Int, String]): Map[Int, String] =
    // Per the spec, a custom section's name is a `name` field — must be
    // valid UTF-8. We let `readName`'s UTF-8 / truncation diagnostics
    // propagate as `InvalidModule` so utf8-custom-section-id rejects.
    // The inner subsection-1 parse below remains best-effort (truncated
    // debug info shouldn't take down a binary that's otherwise fine).
    val sectionName = c.readName()
    if c.pos > secEnd then
      fail(WasmError.InvalidModule("unexpected end: custom section name reads past declared section size"))
    if sectionName != "name" then return current
    var out = current
    while c.pos < secEnd do
      // Each subsection: 1-byte kind, u32 size, payload.
      val subKind = c.readByte() & 0xff
      val subSize = c.readU32()
      val subEnd  = c.pos + subSize
      if subEnd > secEnd then return out  // truncated; abandon what we have so far
      if subKind == 1 then
        // Function names: `vec<(funcidx, name)>`, sorted by funcidx in the
        // wire format but we don't rely on that — just consume each pair.
        try
          val n = c.readU32()
          var i = 0
          while i < n do
            val idx  = c.readU32()
            val name = c.readName()
            out = out.updated(idx, name)
            i += 1
        catch case _: Throwable => return out
      // else: subsection 0 (module name), 2 (local names), 3+ (future) — skip.
      c.pos = subEnd
    out

  // === Element section ===

  /** Parse Section 9. The encoding has seven historical flag forms; Phase 3
    * implements the two active ones (flag 0 and flag 2). Flag 0 is the
    * common case wat2wasm emits for a top-level `(elem ...)`; flag 2
    * appears once a non-default tableidx is given. The other flags
    * (passive, declarative, active-with-elem-expr) are rejected explicitly
    * with a diagnostic naming the flag so a future implementation knows
    * exactly which form surfaced. */
  /** Phase 8.C completes the element-section flag matrix. Flags 0/2/4/6
    * are active forms; 1/5 are passive; 3/7 are declarative. Flags 0..3
    * payload-encode as funcidx LEB vectors (implicit funcref); 4..7 use
    * elemexpr — a constant ref expression per slot (`ref.null reftype`
    * or `ref.func funcidx`, each terminated by `end`).
    *
    *   flag 0: active, table 0,    offset = i32.const,                  funcidx vec
    *   flag 1: passive,            elemkind byte (0x00 = funcref),      funcidx vec
    *   flag 2: active, tableidx,   offset, elemkind byte,               funcidx vec
    *   flag 3: declarative,        elemkind byte,                       funcidx vec
    *   flag 4: active, table 0,    offset = i32.const,                  elemexpr vec  (implicit funcref)
    *   flag 5: passive,            reftype byte,                        elemexpr vec
    *   flag 6: active, tableidx,   offset, reftype byte,                elemexpr vec
    *   flag 7: declarative,        reftype byte,                        elemexpr vec
    *
    * All seven forms normalise into the same `refType: RefType` +
    * `refs: Vector[Value]` shape — funcidxs are lifted to `RefFunc(idx)`
    * at parse time so the runtime never has to know which flag the
    * segment originally used. */
  private def parseElementSection(c: Cursor): Vector[ElementSegment] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val flag = c.readU32()
      flag match
        case 0 =>
          val offset = readConstI32InitExpr(c)
          val cnt    = c.readU32()
          val refs   = Vector.tabulate(cnt)(_ => RefFunc(c.readU32()))
          ElementSegment.Active(0, offset, RefType.FuncRef, refs)
        case 1 =>
          val ek = c.readByte()
          if ek != 0x00 then
            fail(WasmError.InvalidModule(
              s"passive element segment elemkind 0x${ek.toHexString} not supported (funcref only)"))
          val cnt  = c.readU32()
          val refs = Vector.tabulate(cnt)(_ => RefFunc(c.readU32()))
          ElementSegment.Passive(RefType.FuncRef, refs)
        case 2 =>
          val tableIdx = c.readU32()
          val offset   = readConstI32InitExpr(c)
          val ek       = c.readByte()
          if ek != 0x00 then
            fail(WasmError.InvalidModule(
              s"element segment elemkind 0x${ek.toHexString} not supported (funcref only)"))
          val cnt  = c.readU32()
          val refs = Vector.tabulate(cnt)(_ => RefFunc(c.readU32()))
          ElementSegment.Active(tableIdx, offset, RefType.FuncRef, refs)
        case 3 =>
          val ek = c.readByte()
          if ek != 0x00 then
            fail(WasmError.InvalidModule(
              s"declarative element segment elemkind 0x${ek.toHexString} not supported (funcref only)"))
          val cnt  = c.readU32()
          val refs = Vector.tabulate(cnt)(_ => RefFunc(c.readU32()))
          ElementSegment.Declarative(RefType.FuncRef, refs)
        case 4 =>
          val offset = readConstI32InitExpr(c)
          val cnt    = c.readU32()
          val refs   = Vector.tabulate(cnt)(_ => readElemExpr(c, RefType.FuncRef))
          ElementSegment.Active(0, offset, RefType.FuncRef, refs)
        case 5 =>
          val rt   = readRefType(c, "passive element segment")
          val cnt  = c.readU32()
          val refs = Vector.tabulate(cnt)(_ => readElemExpr(c, rt))
          ElementSegment.Passive(rt, refs)
        case 6 =>
          val tableIdx = c.readU32()
          val offset   = readConstI32InitExpr(c)
          val rt       = readRefType(c, "active element segment")
          val cnt      = c.readU32()
          val refs     = Vector.tabulate(cnt)(_ => readElemExpr(c, rt))
          ElementSegment.Active(tableIdx, offset, rt, refs)
        case 7 =>
          val rt   = readRefType(c, "declarative element segment")
          val cnt  = c.readU32()
          val refs = Vector.tabulate(cnt)(_ => readElemExpr(c, rt))
          ElementSegment.Declarative(rt, refs)
        case other =>
          fail(WasmError.InvalidModule(
            s"element segment flag $other not supported (Phase 8.C accepts 0..7)"))
    }

  /** Read one elemexpr — a constant reference expression terminated by
    * `end`. Per the wasm-3.0 spec, the legal constant forms producing a
    * reference value are `ref.null reftype` and `ref.func funcidx` (plus
    * `global.get` over an imported reftype global, which we don't surface
    * yet). Each elemexpr also has an expected [[RefType]]; ref.null's
    * inline reftype byte must match.
    *
    * The single-byte opcode + immediates + `end` shape mirrors the
    * `readConstExpr` helper for scalar constant exprs. */
  private def readElemExpr(c: Cursor, expected: RefType): Value =
    val op = c.readByte()
    val v: Value = op match
      case 0xd0 =>                                    // ref.null reftype
        val rt = readRefType(c, "ref.null")
        if rt != expected then
          fail(WasmError.InvalidModule(
            s"elemexpr ref.null reftype mismatch: expected ${expected}, got $rt"))
        RefNull(rt)
      case 0xd2 =>                                    // ref.func funcidx
        // Funcidx range is checked at validation; here we only accept
        // funcref-typed segments — a ref.func cannot inhabit an externref
        // segment.
        if expected != RefType.FuncRef then
          fail(WasmError.InvalidModule(
            s"elemexpr ref.func in $expected segment (only legal in funcref segments)"))
        RefFunc(c.readU32())
      case 0x23 =>
        fail(WasmError.InvalidModule(
          "global.get in elemexpr requires an imported reftype global, which isn't supported yet"))
      case other =>
        fail(WasmError.InvalidModule(
          s"elemexpr: unsupported opcode 0x${other.toHexString} (expected ref.null or ref.func)"))
    val end = c.readByte()
    if end != 0x0b then fail(WasmError.InvalidModule(s"expected end after elemexpr, got 0x${end.toHexString}"))
    v

  // === Code section ===

  private def parseCodeSection(c: Cursor): Vector[FuncBody] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val bodySize = c.readU32()
      val bodyEnd  = c.pos + bodySize
      val nLocals  = c.readU32()
      // Spec: the sum of all group counts must fit in a u32. We can't just
      // accumulate as we expand because a single huge count (e.g.
      // 0x40000000) would OOM the allocation loop before a later group
      // tips the sum past 2^32 - 1. Read all the (count, type) pairs into
      // a small array first, sum-check, *then* expand.
      val groups: Array[(Int, ValueType)] = new Array(nLocals)
      var localsTotal: Long = 0L
      var i = 0
      while i < nLocals do
        val count = c.readU32()
        val t     = readValType(c)
        groups(i) = (count, t)
        localsTotal += (count.toLong & 0xffffffffL)
        if localsTotal > 0xffffffffL then
          fail(WasmError.InvalidModule(s"too many locals: sum exceeds u32"))
        i += 1
      val locals = ArrayBuffer.empty[ValueType]
      i = 0
      while i < nLocals do
        val (count, t) = groups(i)
        var k = 0
        while k < count do { locals += t; k += 1 }
        i += 1
      // Whatever is left in the body slot is the instruction stream (terminated by 0x0B).
      val bodyLen = bodyEnd - c.pos
      if bodyLen < 0 then fail(WasmError.InvalidModule("negative function body length"))
      val raw = c.readBytes(bodyLen)
      FuncBody(locals.toVector, raw)
    }

  // === Tag section ===
  //
  // Exception Handling proposal (legacy form), Section 13. Each tag is one
  // `attribute` byte (must be 0x00 = exception) followed by a `typeidx` u32
  // that names a functype in section 1. The validator enforces the
  // empty-results invariant on the named functype; here we just parse the
  // wire shape.
  private def parseTagSection(c: Cursor): Vector[Tag] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val attr = c.readByte()
      if attr != 0x00 then
        fail(WasmError.InvalidModule(s"tag section: unknown attribute 0x${attr.toHexString}"))
      Tag(c.readU32())
    }

  // === Data section ===

  /** Phase 8.B promotes flag 1 (passive) from "rejected" to a real
    * `DataSegment.Passive` carrying the bytes for `memory.init` /
    * `data.drop`. Flags 0 / 2 keep the active shape and now carry an
    * explicit memIdx (always 0 for single-memory modules, but plumbed
    * through so multi-memory Phase 8.D doesn't have to re-touch the
    * type).
    *
    *   flag 0: active, memory 0,     offset = i32.const, byte vec
    *   flag 1: passive,                                  byte vec
    *   flag 2: active, explicit memidx, offset,          byte vec
    */
  private def parseDataSection(c: Cursor): Vector[DataSegment] =
    val n = c.readU32()
    Vector.tabulate(n) { _ =>
      val flag = c.readU32()
      flag match
        case 0 =>
          val offset = readConstI32InitExpr(c)
          val len    = c.readU32()
          DataSegment.Active(0, offset, c.readBytes(len))
        case 1 =>
          val len = c.readU32()
          DataSegment.Passive(c.readBytes(len))
        case 2 =>
          val memIdx = c.readU32()
          val offset = readConstI32InitExpr(c)
          val len    = c.readU32()
          DataSegment.Active(memIdx, offset, c.readBytes(len))
        case other =>
          fail(WasmError.InvalidModule(s"unknown data segment flag $other"))
    }

  /** Data-segment offsets are constrained to be i32 const exprs. Returns a
    * [[ConstInit]] so the active-segment offset can be a folded
    * `i32.const` literal, a `global.get` over an earlier immutable i32
    * global, or an extended-const arithmetic tree — type-checked by the
    * validator and resolved by the runtime. */
  private def readConstI32InitExpr(c: Cursor): ConstInit = readConstInitExpr(c)

  /** Read a constant initializer expression terminated by `end`. Per the
    * wasm-3.0 spec const-exprs are stack-machine instruction sequences
    * containing:
    *   - `*.const` / `v128.const` / `ref.null` / `ref.func`
    *   - `global.get globalidx` (any earlier immutable global; validator
    *     enforces the rule because the typing/mutability of imports
    *     isn't visible at parse time)
    *   - `iN.add` / `iN.sub` / `iN.mul` (extended-const proposal)
    *
    * The parser maintains a small expression-tree stack so the resulting
    * [[ConstInit]] is the operator tree rather than a raw op list — the
    * validator and runtime can then walk it recursively. All type
    * checks are deferred to the validator, which has full visibility
    * into the unified globalidx space.
    */
  private def readConstInitExpr(c: Cursor): ConstInit =
    val stack = ArrayBuffer.empty[ConstInit]

    def fail2(msg: String): Nothing = fail(WasmError.InvalidModule(msg))

    var done = false
    while !done do
      val op = c.readByte()
      op match
        case 0x0b => done = true                                                  // end
        case 0x41 =>                                                              // i32.const
          stack += ConstInit.Literal(I32(c.readS32()))
        case 0x42 =>                                                              // i64.const
          Leb128.readS64(c.bytes, c.pos) match
            case Right((x, p)) => c.pos = p; stack += ConstInit.Literal(I64(x))
            case Left(e)       => fail(e)
        case 0x43 =>                                                              // f32.const
          val b   = c.readBytes(4)
          val bits = (b(0) & 0xff)        |
                     ((b(1) & 0xff) <<  8) |
                     ((b(2) & 0xff) << 16) |
                     ((b(3) & 0xff) << 24)
          stack += ConstInit.Literal(F32(java.lang.Float.intBitsToFloat(bits)))
        case 0x44 =>                                                              // f64.const
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
          stack += ConstInit.Literal(F64(java.lang.Double.longBitsToDouble(bits)))
        case 0xfd =>                                                              // v128.const (prefix + sub-opcode 12 + 16 raw bytes)
          val sub = c.readU32()
          if sub != 12 then
            fail2(s"const expr: unsupported SIMD sub-opcode $sub (only v128.const allowed)")
          stack += ConstInit.Literal(V128(c.readBytes(16)))
        case 0xd0 =>                                                              // ref.null reftype
          val rt = readRefType(c, "ref.null")
          stack += ConstInit.Literal(RefNull(rt))
        case 0xd2 =>                                                              // ref.func funcidx
          stack += ConstInit.Literal(RefFunc(c.readU32()))
        case 0x23 =>                                                              // global.get globalidx
          stack += ConstInit.GlobalGet(c.readU32())
        case 0x6a =>                                                              // i32.add
          val (rhs, lhs) = popPair(stack, "i32.add")
          stack += ConstInit.BinOp(ConstBinOp.Add, lhs, rhs, ValueType.I32Type)
        case 0x6b =>                                                              // i32.sub
          val (rhs, lhs) = popPair(stack, "i32.sub")
          stack += ConstInit.BinOp(ConstBinOp.Sub, lhs, rhs, ValueType.I32Type)
        case 0x6c =>                                                              // i32.mul
          val (rhs, lhs) = popPair(stack, "i32.mul")
          stack += ConstInit.BinOp(ConstBinOp.Mul, lhs, rhs, ValueType.I32Type)
        case 0x7c =>                                                              // i64.add
          val (rhs, lhs) = popPair(stack, "i64.add")
          stack += ConstInit.BinOp(ConstBinOp.Add, lhs, rhs, ValueType.I64Type)
        case 0x7d =>                                                              // i64.sub
          val (rhs, lhs) = popPair(stack, "i64.sub")
          stack += ConstInit.BinOp(ConstBinOp.Sub, lhs, rhs, ValueType.I64Type)
        case 0x7e =>                                                              // i64.mul
          val (rhs, lhs) = popPair(stack, "i64.mul")
          stack += ConstInit.BinOp(ConstBinOp.Mul, lhs, rhs, ValueType.I64Type)
        case other =>
          fail2(s"unsupported opcode 0x${other.toHexString} in const expr")

    if stack.length != 1 then
      fail2(s"const expr: expected exactly one value at end, got ${stack.length}")
    stack(0)

  /** Pop two values from a const-expr expression-tree stack; the second
    * pop is the left operand (since wasm is stack-machine: lhs pushed
    * first, rhs pushed second, op pops rhs then lhs). */
  private def popPair(stack: ArrayBuffer[ConstInit], opName: String): (ConstInit, ConstInit) =
    if stack.length < 2 then
      fail(WasmError.InvalidModule(s"const expr: $opName needs 2 operands, stack has ${stack.length}"))
    val rhs = stack.remove(stack.length - 1)
    val lhs = stack.remove(stack.length - 1)
    (rhs, lhs)
