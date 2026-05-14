package io.github.edadma.wasm

import TestSupport.*

/** Catch-all for the test categories that don't fit one of the
  * type/feature-focused files: parser malformed-binary handling,
  * unsupported-opcode reporting, runtime/linking errors,
  * `ModuleInstance` accessor + import surfacing smoke tests, the
  * `EnvModule.default` smoke test, and historic bug-fix regression
  * tests.
  */
object ParserAndRuntimeTests:

  def run(): Unit =
    importsAndAccessors()
    parserMalformed()
    unsupportedOpcodes()
    runtimeErrors()
    envModule()
    regressions()

  // === Import/export silent-skip + ModuleInstance accessors ===============

  private def importsAndAccessors(): Unit =

    test("parser: table import is silently skipped") {
      val inst = instantiate(Fixtures.table_import)
      check(callI32(inst, "f") == 1, "function after a skipped table import still works")
    }
    test("parser: memory import is silently skipped") {
      val inst = instantiate(Fixtures.mem_import)
      check(callI32(inst, "f") == 2, "function after a skipped memory import still works")
    }
    test("parser: global import is silently skipped") {
      val inst = instantiate(Fixtures.global_import)
      check(callI32(inst, "f") == 3, "function after a skipped global import still works")
    }
    test("parser: memory export silently ignored; global export surfaced") {
      val inst = instantiate(Fixtures.mem_export)
      check(inst.exportedFunctionNames == Seq("f"), s"function-export list should be just `f`: ${inst.exportedFunctionNames}")
      check(callI32(inst, "f") == 4, "function export still callable")
      // Phase 2: global exports are surfaced through `globalValue`.
      inst.globalValue("g") match
        case Right(I32(7)) => ()
        case other         => check(false, s"global export `g` should read I32(7): $other")
    }

    test("ModuleInstance.exportedFunctionNames: sorted, function-only") {
      val inst = instantiate(Fixtures.memory)
      check(inst.exportedFunctionNames == Seq("byte_roundtrip", "i32_roundtrip"),
        s"got ${inst.exportedFunctionNames}")
    }
    test("ModuleInstance.functionCount: imports + defined") {
      val noImports = instantiate(Fixtures.arith)
      check(noImports.functionCount == 1, s"arith has 1 function: got ${noImports.functionCount}")
      val withImport = instantiate(Fixtures.putchar)
      check(withImport.functionCount == 2, s"putchar has 1 import + 1 defined: got ${withImport.functionCount}")
    }

    test("error: invoking a non-existent export returns ExportNotFound") {
      val inst = instantiate(Fixtures.arith)
      inst.invoke("does_not_exist") match
        case Left(WasmError.ExportNotFound("does_not_exist")) => ()
        case other                                            => check(false, s"expected ExportNotFound, got $other")
    }
    test("error: instantiating with an unresolved import returns UnknownImport") {
      Runtime.instantiate(Fixtures.putchar, Seq.empty) match
        case Left(WasmError.UnknownImport("env", "putchar")) => ()
        case other                                           => check(false, s"expected UnknownImport, got $other")
    }

  // === Parser malformed-binary tests (handcrafted bytes) ==================

  private def parserMalformed(): Unit =

    test("parser: empty input returns InvalidMagic") {
      Parser.parse(b()) match
        case Left(WasmError.InvalidMagic) => ()
        case other => check(false, s"expected InvalidMagic, got $other")
    }
    test("parser: truncated header (< 8 bytes) returns InvalidMagic") {
      Parser.parse(b(0x00, 0x61, 0x73)) match
        case Left(WasmError.InvalidMagic) => ()
        case other => check(false, s"expected InvalidMagic, got $other")
    }
    test("parser: wrong magic bytes return InvalidMagic") {
      Parser.parse(b(0xde, 0xad, 0xbe, 0xef, 0x01, 0x00, 0x00, 0x00)) match
        case Left(WasmError.InvalidMagic) => ()
        case other => check(false, s"expected InvalidMagic, got $other")
    }
    test("parser: wrong version (not 1.0) returns InvalidMagic") {
      val wrong = b(0x00, 0x61, 0x73, 0x6d, 0x02, 0x00, 0x00, 0x00)
      Parser.parse(wrong) match
        case Left(WasmError.InvalidMagic) => ()
        case other => check(false, s"expected InvalidMagic, got $other")
    }
    test("parser: bad magic (3rd byte wrong) returns InvalidMagic") {
      Parser.parse(b(0x00, 0x61, 0x73, 0x00, 0x01, 0x00, 0x00, 0x00)) match
        case Left(WasmError.InvalidMagic) => ()
        case other => check(false, s"expected InvalidMagic, got $other")
    }
    test("parser: section size overflowing file returns InvalidModule") {
      val bad = Header ++ b(0x01, 0x7f)            // section 1 claims 127 bytes, content 0 bytes
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("overflows"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(overflows), got $other")
    }
    test("parser: non-0x60 functype tag returns InvalidModule") {
      val bad = patchFirst(Fixtures.arith, 0x60, 0x61)
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("functype"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(functype), got $other")
    }
    test("parser: unknown valtype returns InvalidModule with 'valtype'") {
      val bad = patchFirst(Fixtures.arith, 0x7f, 0x55)
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("valtype"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(valtype), got $other")
    }
    test("parser: unknown import kind byte returns InvalidModule") {
      val importContent =
        b(0x01) ++                                          // 1 import
        b(0x03, 'e'.toInt, 'n'.toInt, 'v'.toInt) ++         // module name "env"
        b(0x01, 'x'.toInt) ++                                // import name "x"
        b(0x04, 0x00)                                        // unknown kind + dummy idx
      val bad = Header ++ b(0x02, importContent.length) ++ importContent
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("import"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(import kind), got $other")
    }
    test("parser: unknown export kind byte returns InvalidModule") {
      val exportContent =
        b(0x01) ++                                          // 1 export
        b(0x01, 'x'.toInt) ++                                // name "x"
        b(0x04, 0x00)                                        // unknown kind + dummy idx
      val bad = Header ++ b(0x07, exportContent.length) ++ exportContent
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("export"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(export kind), got $other")
    }
    test("parser: function / code section count mismatch returns InvalidModule") {
      val typeSec = b(0x01) ++ b(0x60, 0x00, 0x00)         // 1 functype 0→0
      val funcSec = b(0x01) ++ b(0x00)                     // 1 function, typeidx 0
      val codeSec = b(0x00)                                // 0 bodies
      val bad =
        Header ++
        b(0x01, typeSec.length) ++ typeSec ++
        b(0x03, funcSec.length) ++ funcSec ++
        b(0x0a, codeSec.length) ++ codeSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("function"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(function/code mismatch), got $other")
    }
    test("parser: passive data segments (flag 1) return InvalidModule") {
      val dataSec = b(0x01, 0x01, 0x00)                    // 1 segment, flag 1, 0 bytes
      val bad = Header ++ b(0x0b, dataSec.length) ++ dataSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("passive"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(passive), got $other")
    }
    test("parser: unknown data segment flag returns InvalidModule") {
      val dataSec = b(0x01, 0x05)                          // 1 segment, flag 5 (unknown)
      val bad = Header ++ b(0x0b, dataSec.length) ++ dataSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("data"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(data flag), got $other")
    }
    test("parser: data segment with non-i32.const offset expr returns InvalidModule") {
      val dataSec = b(0x01, 0x00, 0x42, 0x00, 0x0b, 0x00)  // flag 0, i64.const 0, end, 0 bytes
      val bad = Header ++ b(0x0b, dataSec.length) ++ dataSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("i32.const"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(i32.const), got $other")
    }
    test("parser: data segment missing `end` after i32.const returns InvalidModule") {
      val dataSec = b(0x01, 0x00, 0x41, 0x00, 0x00, 0x00)  // flag 0, i32.const 0, NO end
      val bad = Header ++ b(0x0b, dataSec.length) ++ dataSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(_)) => ()
        case other => check(false, s"expected InvalidModule, got $other")
    }

    // Phase 3 — table / element section diagnostics ------------------------

    test("parser: section 4 with non-funcref reftype returns InvalidModule") {
      // 1 table, reftype 0x6F (externref — reference types), flag 0, min 0.
      val tableSec = b(0x01, 0x6f, 0x00, 0x00)
      val bad = Header ++ b(0x04, tableSec.length) ++ tableSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("reftype"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(reftype), got $other")
    }

    test("parser: section 9 with passive flag (1) returns InvalidModule") {
      // 1 element segment with flag=1 (passive) — Phase 3 only models the
      // active forms (flag 0 / flag 2).
      val elemSec = b(0x01, 0x01)
      val bad = Header ++ b(0x09, elemSec.length) ++ elemSec
      Parser.parse(bad) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("element"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(element flag), got $other")
    }

  // === Interpreter unsupported-opcode tests ===============================

  private def unsupportedOpcodes(): Unit =

    /** Patch the first i32.const opcode (0x41) in arith.wasm to a different
      * opcode that isn't in the MVP subset. The pre-scan in
      * `computeBodyMeta` runs during instantiation and surfaces the error. */
    def assertUnknownOpcode(patched: Array[Byte], opcode: Int, label: String): Unit =
      Runtime.instantiate(patched, Seq(EnvModule.default)) match
        case Left(WasmError.UnknownOpcode(b)) =>
          check(b == opcode, s"$label: expected opcode 0x${opcode.toHexString}, got 0x${b.toHexString}")
        case other => check(false, s"$label: expected UnknownOpcode(0x${opcode.toHexString}), got $other")

    // Retargeted from 0x11 (formerly call_indirect, now supported in Phase 3)
    // to 0x12 — a reserved byte immediately after call_indirect with no MVP
    // meaning. Same code path through `skipImmediates`'s default branch.
    test("interpreter: 0x12 (reserved, post-call_indirect) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0x12), 0x12, "0x12 (reserved)")
    }
    // Retargeted from 0xC4 (now i64.extend32_s in the sign-extension proposal,
    // Phase 7.D) to 0xC5 — also reserved, no MVP meaning, and not a prefix
    // byte of any instruction set we currently parse. Same code path through
    // `skipImmediates`'s default branch.
    test("interpreter: 0xC5 (unassigned) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0xc5), 0xc5, "0xC5 (reserved)")
    }
    // Retargeted from 0x3F / 0x40 (formerly memory.size / memory.grow,
    // now supported in Phase 4) to 0x06 and 0x07 — both belong to the
    // exception-handling proposal (try / catch) and are firmly post-MVP.
    // Same code path through `skipImmediates`'s default branch.
    test("interpreter: 0x06 (try, post-MVP) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0x06), 0x06, "0x06 (try)")
    }
    test("interpreter: 0x07 (catch, post-MVP) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0x07), 0x07, "0x07 (catch)")
    }
    test("interpreter: completely unused opcode (0xFF) reported as UnknownOpcode") {
      assertUnknownOpcode(patchFirst(Fixtures.arith, 0x41, 0xff), 0xff, "0xFF")
    }

  // === Runtime / linking error tests ======================================

  private def runtimeErrors(): Unit =

    test("runtime: import referencing an out-of-range type index returns InvalidModule") {
      // putchar.wasm's func import descriptor ends with kind=0x00, typeidx=0x00
      // immediately after the import-name "putchar". Bump the typeidx to 9.
      val src    = Fixtures.putchar
      val marker = src.indexOf("putchar".getBytes("UTF-8").last)
      check(marker > 0, "marker not found")
      val bad = patchByte(src, marker + 2, 0x09) // skip 'r' and kind byte
      Runtime.instantiate(bad, Seq(EnvModule.default)) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("type"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(type), got $other")
    }

    test("runtime: defined function referencing an out-of-range type index returns InvalidModule") {
      // arith.wasm section 3 bytes (function section): 03 02 01 00
      //                                                id size cnt typeidx
      val src  = Fixtures.arith
      val sec3 = src.indexOf(0x03.toByte)
      check(sec3 > 0, "section 3 not found")
      val bad = patchByte(src, sec3 + 3, 0x09)
      Runtime.instantiate(bad, Seq(EnvModule.default)) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("type"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(type), got $other")
    }

    test("runtime: call with out-of-range function index returns InvalidModule") {
      val src    = Fixtures.factorial
      val callIx = src.indexOf(0x10.toByte)
      check(callIx > 0, "call opcode not found")
      val bad  = patchByte(src, callIx + 1, 0x09)
      val inst = instantiate(bad)
      expectError(inst, "fact", Seq(I32(5))) {
        case WasmError.InvalidModule(msg) => msg.contains("function index")
      }
    }

    test("runtime: branch index out of range returns InvalidModule") {
      val src     = Fixtures.br_block
      val brIfIdx = src.indexOf(0x0d.toByte)
      check(brIfIdx > 0, "br_if opcode not found")
      val bad  = patchByte(src, brIfIdx + 1, 0x09)
      val inst = instantiate(bad)
      expectError(inst, "test_br", Seq(I32(1))) {
        case WasmError.InvalidModule(msg) => msg.contains("branch index")
      }
    }

    // === Phase 7.D: br_table (0x0E) =====================================
    //
    // Indexed jump used by `match` ladders in rustc-emitted binaries. Selector
    // in [0, count) picks vec(selector); anything outside (including negative)
    // falls through to the default label.

    test("br_table: in-range selectors pick the expected branch") {
      val inst = instantiate(Fixtures.br_table)
      check(callI32(inst, "select", 0) == 10, "sel=0 → branch 0")
      check(callI32(inst, "select", 1) == 20, "sel=1 → branch 1")
      check(callI32(inst, "select", 2) == 30, "sel=2 → branch 2")
    }

    test("br_table: out-of-range selectors hit the default branch") {
      val inst = instantiate(Fixtures.br_table)
      check(callI32(inst, "select", 3)         == 99, "sel=3 (just past vec) → default")
      check(callI32(inst, "select", 999)       == 99, "sel=999 → default")
      check(callI32(inst, "select", -1)        == 99, "sel=-1 (unsigned: huge) → default")
      check(callI32(inst, "select", Int.MinValue) == 99, "sel=Int.MinValue → default")
    }

    test("runtime: local.get with out-of-range index returns InvalidModule") {
      val src      = Fixtures.locals
      val localGet = src.indexOf(0x20.toByte)
      check(localGet > 0, "local.get not found")
      val bad  = patchByte(src, localGet + 1, 0x09)
      val inst = instantiate(bad)
      expectError(inst, "test_locals", Seq(I32(1), I32(2))) {
        case WasmError.InvalidModule(msg) => msg.contains("local.get")
      }
    }

    test("runtime: global.get with out-of-range index returns InvalidModule") {
      // globals_basic.wasm has two globals (counter at 0, seed at 1). Find the
      // first `global.get` opcode and patch its index byte to a high value.
      val src        = Fixtures.globals_basic
      val globalGet  = src.indexOf(0x23.toByte)
      check(globalGet > 0, "global.get opcode not found")
      val bad  = patchByte(src, globalGet + 1, 0x09)
      val inst = instantiate(bad)
      // The patched function may be any of the getters — every entry point
      // either reads the patched op directly or traps the same way through it.
      inst.invoke("get_count", Seq.empty) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("global.get"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(global.get …), got $other")
    }

    test("runtime: global.set with out-of-range index returns InvalidModule") {
      val src        = Fixtures.globals_basic
      val globalSet  = src.indexOf(0x24.toByte)
      check(globalSet > 0, "global.set opcode not found")
      val bad  = patchByte(src, globalSet + 1, 0x09)
      val inst = instantiate(bad)
      inst.invoke("bump", Seq.empty) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("global.set"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(global.set …), got $other")
    }

    test("parser: section 6 with init-expr not matching declared type returns InvalidModule") {
      // globals_types.wasm starts its first global as (mut i32) (i32.const ...).
      // Flip the declared valtype to i64 while leaving the init expr as
      // i32.const — readConstExpr should reject the mismatched opcode/type pair.
      val src = Fixtures.globals_types
      // Find section id 6: scan for byte 0x06 immediately followed by a u32
      // (the section size). We can rely on it being the first 0x06 after the
      // magic+version + type section header. Be conservative: walk by section.
      // Simpler approach: locate the section by searching for the unique
      // section-6 prefix "0x06 size 0x04 0x7f 0x01 0x41" (count=4, valtype=i32,
      // mut=mut, op=i32.const) which is specific to this fixture.
      // We just need to find the valtype byte (0x7f) of the first global
      // entry; that lives at offset section-6-start + 2 (skip count byte).
      // Probe for the marker subsequence "0x7f 0x01 0x41" (i32, mut, i32.const).
      var i      = 0
      var marker = -1
      while marker < 0 && i + 2 < src.length do
        if (src(i) & 0xff) == 0x7f && (src(i + 1) & 0xff) == 0x01 && (src(i + 2) & 0xff) == 0x41 then
          marker = i
        i += 1
      check(marker > 0, "section-6 first-global marker not found")
      // Swap the valtype byte from 0x7f (i32) to 0x7e (i64). The init-expr
      // op is still 0x41 (i32.const), so readConstExpr should reject the
      // mismatch with a clear diagnostic.
      val bad = patchByte(src, marker, 0x7e)
      Runtime.instantiate(bad, Seq(EnvModule.default)) match
        case Left(WasmError.InvalidModule(msg)) =>
          // Declared type was bumped to i64, init op left as 0x41 (i32.const) —
          // the message should name the expected mnemonic (i64.const).
          check(msg.contains("i64.const"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(i64.const …), got $other")
    }

    test("ModuleInstance.invoke with valid args returns Seq() for an empty function") {
      val inst = instantiate(Fixtures.empty_func)
      runRight(inst.invoke("empty")) match
        case Seq() => ()
        case other => check(false, s"expected empty Seq, got $other")
    }

  // === EnvModule.default smoke test =======================================

  private def envModule(): Unit =

    test("EnvModule.default: putchar reaches some platform output (no throw)") {
      // The exact destination is platform-dependent (we can capture stdout on
      // JVM via System.setOut; Scala.js/Native may or may not respect the
      // redirect). The test's job is to prove the default code path runs
      // without throwing — the captured-string check is a JVM-only bonus.
      val baos     = new java.io.ByteArrayOutputStream
      val savedOut = System.out
      System.setOut(new java.io.PrintStream(baos, /* autoFlush = */ true, "UTF-8"))
      try
        val inst = instantiate(Fixtures.putchar, EnvModule.default)
        runRight(inst.invoke("hello"))
      finally
        System.setOut(savedOut)
      val captured = new String(baos.toByteArray, "UTF-8")
      check(captured == "Hi!" || captured.isEmpty,
        s"expected 'Hi!' or '' (platform-dependent), got '${captured}'")
    }

  // === Bug-fix regression tests ===========================================

  private def regressions(): Unit =

    test("regression: unsupported blocktype (0x7B) returns InvalidModule, not RuntimeException") {
      // Bug: `Interpreter.readBlocktype` threw `RuntimeException` for any
      // blocktype other than 0x40 / 0x7F, bypassing the WasmError discipline.
      // Fixed to `Left(InvalidModule(...))` so the pre-scan reports it cleanly.
      //
      // The fixture's block declares an i32 result (`0x02 0x7F`); we patch the
      // blocktype byte to a still-unsupported value. The original repro bytes
      // 0x7E (i64), 0x7D (f32) and 0x7C (f64) are all valid blocktypes now,
      // so the regression test has been retargeted through each phase to the
      // latest genuinely-unsupported form. 0x7B is in the negative-s33
      // valtype range and not assigned by the MVP — same code path, same
      // expected typed error.
      val src = Fixtures.block_result
      val pat = b(0x02, 0x7f)
      var idx = -1
      var i   = 0
      while idx < 0 && i <= src.length - pat.length do
        if src(i) == pat(0) && src(i + 1) == pat(1) then idx = i
        i += 1
      check(idx >= 0, "block + i32-blocktype pattern not found")
      val bad = patchByte(src, idx + 1, 0x7b)            // blocktype byte → unsupported
      Runtime.instantiate(bad, Seq(EnvModule.default)) match
        case Left(WasmError.InvalidModule(msg)) => check(msg.contains("blocktype"), s"message: $msg")
        case other => check(false, s"expected InvalidModule(blocktype), got $other")
    }
