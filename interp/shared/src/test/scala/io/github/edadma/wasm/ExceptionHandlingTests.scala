package io.github.edadma.wasm

import TestSupport.*

/** Exception Handling proposal (legacy form) — try / catch / catch_all /
  * throw / rethrow / delegate plus Section 13 (Tag) and the 0x04 tag
  * import / export kind.
  *
  * Each test hand-builds a tiny module so the test stays free of any
  * `wat2wasm` build-time dependency. The byte-layout pattern mirrors the
  * inline-bytes modules used in `NumericTests` and `TypedSelectTests`.
  *
  * Opcode bytes covered here:
  *   - 0x06 try blocktype
  *   - 0x07 catch tagidx
  *   - 0x08 throw tagidx
  *   - 0x09 rethrow labelidx
  *   - 0x18 delegate labelidx
  *   - 0x19 catch_all
  *   - section 13 tag declarations
  */
object ExceptionHandlingTests:

  // === Helpers ============================================================

  /** Wrap raw section content with the section id + LEB length prefix. */
  private def sec(id: Int, content: Array[Byte]): Array[Byte] =
    b(id, content.length) ++ content

  /** Type section: each entry is `0x60` + paramvec + resultvec. We pass
    * pre-built entries since most modules in this file have 1–2 types. */
  private def typeSec(entries: Array[Byte]*): Array[Byte] =
    sec(0x01, b(entries.length) ++ entries.flatten)

  /** A single functype entry: `0x60 nparams paramTypes... nresults resultTypes...`. */
  private def ft(params: Seq[Int], results: Seq[Int]): Array[Byte] =
    b(0x60, params.length) ++ b(params*) ++ b(results.length) ++ b(results*)

  /** Function section: one entry per defined function, each a typeidx LEB. */
  private def funcSec(typeIdxs: Int*): Array[Byte] =
    sec(0x03, b(typeIdxs.length) ++ b(typeIdxs*))

  /** Tag section (Section 13): one entry per defined tag, each = attribute(0x00) + typeidx. */
  private def tagSec(typeIdxs: Int*): Array[Byte] =
    val body = b(typeIdxs.length) ++ typeIdxs.flatMap(ti => Seq(0x00, ti)).toArray.map(_.toByte)
    sec(13, body)

  /** Export section: each name (1 char) -> (kind, idx). */
  private def expSec(entries: (String, Int, Int)*): Array[Byte] =
    val body = b(entries.length) ++ entries.toArray.flatMap { case (name, kind, idx) =>
      val nb = name.getBytes("UTF-8")
      b(nb.length) ++ nb ++ b(kind, idx)
    }
    sec(0x07, body)

  /** Code section: each entry is `bodyLen, 0x00 (zero locals), bodyBytes`. */
  private def codeSec(bodies: Array[Byte]*): Array[Byte] =
    val payload = b(bodies.length) ++ bodies.flatMap { body =>
      val withLocals = b(0x00) ++ body
      b(withLocals.length) ++ withLocals
    }
    sec(0x0a, payload)

  /** Same as codeSec but each body's locals + bytes are passed pre-baked. */
  private def codeSecRaw(bodies: Array[Byte]*): Array[Byte] =
    val payload = b(bodies.length) ++ bodies.flatMap { body =>
      b(body.length) ++ body
    }
    sec(0x0a, payload)

  // Common blocktype encodings.
  private val BT_VOID:   Int = 0x40
  private val BT_I32:    Int = 0x7f

  // === Test runner =========================================================

  def run(): Unit =
    section1Parser()
    catchRoundTrip()
    catchAllAndRethrow()
    delegateAndUncaught()

  // === Parser / type-table tests ==========================================

  private def section1Parser(): Unit =

    test("EH parser: section 13 (Tag) round-trip — defined tag with empty params") {
      val bytes =
        Header ++
        typeSec(ft(Nil, Nil)) ++    // type 0: () -> ()
        tagSec(0)                   // one tag, typeidx 0
      val mod = runRight(Parser.parse(bytes))
      check(mod.tags.size == 1, s"expected 1 defined tag, got ${mod.tags.size}")
      check(mod.tags.head.typeIdx == 0, s"expected typeidx 0, got ${mod.tags.head.typeIdx}")
      check(mod.tagImports.isEmpty, "no tag imports expected")
    }

    test("EH parser: section 13 with payload — i32 + i64 params") {
      val bytes =
        Header ++
        typeSec(ft(Seq(0x7f, 0x7e), Nil)) ++   // type 0: (i32, i64) -> ()
        tagSec(0)
      val mod = runRight(Parser.parse(bytes))
      check(mod.tags.size == 1, "tag count")
      check(mod.types.head.params == Vector(ValueType.I32Type, ValueType.I64Type),
            s"tag payload types: ${mod.types.head.params}")
    }

    test("EH parser: tag import (kind 0x04) surfaces on tagImports") {
      val importBody = b(0x01) ++                                          // 1 import
        b(0x03, 'e'.toInt, 'n'.toInt, 'v'.toInt) ++                        // module "env"
        b(0x03, 't'.toInt, 'a'.toInt, 'g'.toInt) ++                        // name "tag"
        b(0x04, 0x00, 0x00)                                                // kind=tag, attr=0x00, typeidx=0
      val bytes =
        Header ++
        typeSec(ft(Seq(0x7f), Nil)) ++                                     // type 0: (i32) -> ()
        sec(0x02, importBody)
      val mod = runRight(Parser.parse(bytes))
      check(mod.tagImports.size == 1, s"expected 1 tag import, got ${mod.tagImports.size}")
      check(mod.tagImports.head.module == "env", s"module: ${mod.tagImports.head.module}")
      check(mod.tagImports.head.name == "tag",   s"name: ${mod.tagImports.head.name}")
      check(mod.tagImports.head.typeIdx == 0, "typeidx")
    }

    test("EH parser: tag export (kind 0x04) surfaces on exports") {
      val bytes =
        Header ++
        typeSec(ft(Nil, Nil)) ++
        tagSec(0) ++
        expSec(("e", 0x04, 0))
      val mod = runRight(Parser.parse(bytes))
      mod.exports.head match
        case TagExport(name, idx) =>
          check(name == "e", s"name: $name")
          check(idx == 0,    s"idx:  $idx")
        case other =>
          check(false, s"expected TagExport, got $other")
    }

    test("EH validator: tag whose functype has non-empty results is rejected") {
      val bytes =
        Header ++
        typeSec(ft(Nil, Seq(0x7f))) ++          // () -> (i32) — illegal for a tag
        tagSec(0)
      Parser.parse(bytes).flatMap(m => Validator.validate(m).map(_ => m)) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("empty results"), s"unexpected message: $msg")
        case other =>
          check(false, s"expected InvalidModule(empty results), got $other")
    }

    test("EH validator: tagidx out of range surfaces from validate") {
      // One defined tag whose typeidx is 9 — out of range. Plus a tiny
      // dummy function so validate has something to walk.
      val tagBody = b(0x01) ++ b(0x00, 0x09)  // 1 tag, attr=0, typeidx=9
      val bytes =
        Header ++
        typeSec(ft(Nil, Nil)) ++
        funcSec(0) ++
        sec(13, tagBody) ++
        codeSec(b(0x0b))                       // one function body: just `end`
      Parser.parse(bytes).flatMap(m => Validator.validate(m).map(_ => m)) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("type index 9"), s"unexpected message: $msg")
        case other =>
          check(false, s"expected InvalidModule(type index 9), got $other")
    }

  // === try / catch round-trip ============================================

  private def catchRoundTrip(): Unit =

    test("EH runtime: try { throw $t } catch $t end — catches own throw, no payload") {
      // exports `f: () -> i32`. The try-body throws tag 0 immediately; the
      // catch handler runs and pushes 42.
      //
      //   func f() -> i32:
      //     (try (result i32)
      //       (i32.const 1) (drop)             ;; live code so try has a body
      //       (throw $t)
      //       (i32.const 99)                    ;; unreachable
      //       (catch $t)
      //       (i32.const 42)
      //     end)
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),     // type 0: () -> i32  (also the try blocktype)
        ft(Nil, Nil),             // type 1: () -> () (tag type)
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)
      val expS  = expSec(("f", 0x00, 0))
      // try uses an inline blocktype (i32 result = 0x7F).
      val body = b(
        0x06, BT_I32,                // try (result i32)
        0x41, 0x01,                  // i32.const 1
        0x1a,                        // drop
        0x08, 0x00,                  // throw $t (tag 0)
        0x41, 0xe3, 0x00,            // i32.const 99 (SLEB: bit 6 set → 2 bytes)
        0x07, 0x00,                  // catch $t
        0x41, 0x2a,                  // i32.const 42
        0x0b,                        // end (of try)
        0x0b,                        // end (of function)
      )
      val codeS = codeSec(body)
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeS
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 42, s"expected 42, got $v")
    }

    test("EH runtime: try { throw $t with i32 payload 7 } catch $t (i32) end — payload visible to handler") {
      // tag type: (i32) -> ()
      // func returns i32 = the payload + 100.
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),     // type 0: () -> i32  (used by try result + func)
        ft(Seq(BT_I32), Nil),     // type 1: (i32) -> () — tag type
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)
      val expS  = expSec(("f", 0x00, 0))
      val body = b(
        0x06, BT_I32,                // try (result i32)
        0x41, 0x07,                  // i32.const 7
        0x08, 0x00,                  // throw $t  — pops i32, propagates
        0x41, 0x00,                  // i32.const 0 (unreachable)
        0x07, 0x00,                  // catch $t  — payload pushed as i32
        0x41, 0xe4, 0x00,            // i32.const 100  (LEB: 100 = 0xE4, 0x00)
        0x6a,                        // i32.add
        0x0b, 0x0b,                  // end (try), end (func)
      )
      val codeS = codeSec(body)
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeS
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 107, s"expected 107, got $v")
    }

    test("EH runtime: try without throw falls through normally, catch is dead code") {
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),
        ft(Nil, Nil),
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)
      val expS  = expSec(("f", 0x00, 0))
      val body = b(
        0x06, BT_I32,                // try (result i32)
        0x41, 0x05,                  // i32.const 5
        // no throw — body produces 5 and falls through
        0x07, 0x00,                  // catch $t (dead on this path)
        0x41, 0x00,                  // i32.const 0 (handler result if invoked)
        0x0b, 0x0b,                  // end (try), end (func)
      )
      val codeS = codeSec(body)
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeS
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 5, s"expected 5, got $v")
    }

  // === catch_all + rethrow ===============================================

  private def catchAllAndRethrow(): Unit =

    test("EH runtime: catch_all catches a throw with non-matching tagidx") {
      // Two tags. throw $t1; catch_all handler returns 11.
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),
        ft(Nil, Nil),                 // tag types: both () -> ()
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1, 1)         // tags 0 and 1, both () -> ()
      val expS  = expSec(("f", 0x00, 0))
      val body = b(
        0x06, BT_I32,
        0x08, 0x01,                    // throw $t1
        0x41, 0x00,                    // unreachable filler
        0x07, 0x00,                    // catch $t0  (won't fire)
        0x41, 0x09,                    // i32.const 9
        0x19,                          // catch_all  (will fire)
        0x41, 0x0b,                    // i32.const 11
        0x0b, 0x0b,
      )
      val codeS = codeSec(body)
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeS
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 11, s"expected 11, got $v")
    }

    test("EH runtime: rethrow N — outer try catches inner catch's rethrow") {
      // Tag $t with empty payload. Outer try catches; inner try catches
      // and rethrows. Outer handler returns 22.
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),     // 0: () -> i32
        ft(Nil, Nil),             // 1: () -> () (tag)
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)
      val expS  = expSec(("f", 0x00, 0))
      //   try (result i32)
      //     try (result i32)
      //       throw $t
      //       i32.const 0
      //     catch $t
      //       rethrow 0       ;; relabel: 0 == this catch frame
      //     end
      //   catch $t
      //     i32.const 22
      //   end
      val body = b(
        0x06, BT_I32,                       // outer try (result i32)
          0x06, BT_I32,                     //   inner try (result i32)
            0x08, 0x00,                     //     throw $t
            0x41, 0x00,                     //     i32.const 0 (unreachable)
          0x07, 0x00,                       //   catch $t
            0x09, 0x00,                     //     rethrow 0 — refers to this catch
          0x0b,                             //   end inner try
        0x07, 0x00,                         // outer catch $t
        0x41, 0x16,                         //   i32.const 22
        0x0b,                               // end outer try
        0x0b,                               // end func
      )
      val codeS = codeSec(body)
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeS
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 22, s"expected 22, got $v")
    }

    test("EH validator: rethrow outside any catch frame is rejected") {
      // tagS isn't strictly needed; rethrow's labelidx check fails first.
      val typeS = typeSec(ft(Nil, Nil))
      val funcS = funcSec(0)
      val expS  = expSec(("f", 0x00, 0))
      val body = b(
        0x09, 0x00,                    // rethrow 0 — function frame isn't a catch
        0x0b,
      )
      val codeS = codeSec(body)
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeS
      Parser.parse(bytes).flatMap(m => Validator.validate(m).map(_ => m)) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("rethrow"), s"expected rethrow rejection, got: $msg")
        case other =>
          check(false, s"expected validation failure for rethrow at fn level, got $other")
    }

  // === delegate + uncaught propagation ===================================

  private def delegateAndUncaught(): Unit =

    test("EH runtime: uncaught throw surfaces as WasmError.UncaughtException(tag, args)") {
      // No try at all — throw escapes the function entirely.
      val typeS = typeSec(
        ft(Nil, Nil),               // 0: () -> () (func + tag share)
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(0)
      val expS  = expSec(("f", 0x00, 0))
      val body = b(0x08, 0x00, 0x0b)   // throw $t; end
      val codeS = codeSec(body)
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeS
      val inst  = instantiate(bytes)
      inst.invoke("f", Seq.empty) match
        case Left(WasmError.UncaughtException(tagIdx, args)) =>
          check(tagIdx == 0, s"tagIdx: $tagIdx")
          check(args.isEmpty, s"args: $args")
        case other =>
          check(false, s"expected UncaughtException(0, []), got $other")
    }

    test("EH runtime: uncaught throw carries i32 payload through to host") {
      val typeS = typeSec(
        ft(Nil, Nil),               // 0: () -> () — function shape
        ft(Seq(BT_I32), Nil),       // 1: (i32) -> () — tag shape
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)
      val expS  = expSec(("f", 0x00, 0))
      val body = b(
        0x41, 0x2a,                  // i32.const 42
        0x08, 0x00,                  // throw $t
        0x0b,
      )
      val codeS = codeSec(body)
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeS
      val inst  = instantiate(bytes)
      inst.invoke("f", Seq.empty) match
        case Left(WasmError.UncaughtException(0, Seq(I32(42)))) => ()
        case other =>
          check(false, s"expected UncaughtException(0, [I32(42)]), got $other")
    }

    test("EH runtime: try-delegate to outer try forwards the throw to the outer catch") {
      // outer try { inner try { throw $t } delegate 0 } catch $t { 33 }
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),
        ft(Nil, Nil),
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)
      val expS  = expSec(("f", 0x00, 0))
      val body = b(
        0x06, BT_I32,                   // outer try (result i32)
          0x06, BT_VOID,                //   inner try (no result)
            0x08, 0x00,                 //     throw $t
          0x18, 0x00,                   //   delegate 0 — re-fire at the outer try
          0x41, 0x07,                   //   i32.const 7 — fall-through (dead path; throw goes outer)
        0x07, 0x00,                     // outer catch $t
        0x41, 0x21,                     //   i32.const 33
        0x0b,                           // end outer try
        0x0b,                           // end func
      )
      val codeS = codeSec(body)
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeS
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 33, s"expected 33, got $v")
    }

    test("EH runtime: throw propagates across `call` boundary into caller's try") {
      // Two functions:
      //   $thrower: () -> ()         throws $t
      //   $caller : () -> i32        try { call $thrower; i32.const 0 } catch $t { i32.const 77 }
      val typeS = typeSec(
        ft(Nil, Nil),                   // 0: () -> () (thrower + tag)
        ft(Nil, Seq(BT_I32)),           // 1: () -> i32 (caller)
      )
      val funcS = funcSec(0, 1)         // thrower=type0, caller=type1
      val tagS  = tagSec(0)
      // exports: thrower as f0 (for sanity), caller as "f"
      val expS  = expSec(("f", 0x00, 1))
      // thrower body
      val throwerBody = b(0x08, 0x00, 0x0b)   // throw $t; end
      // caller body
      val callerBody  = b(
        0x06, BT_I32,                         // try (result i32)
          0x10, 0x00,                         //   call $thrower
          0x41, 0x00,                         //   i32.const 0 (unreachable)
        0x07, 0x00,                           // catch $t
          0x41, 0xcd, 0x00,                   //   i32.const 77 (SLEB: bit 6 set → 2 bytes)
        0x0b, 0x0b,
      )
      val codeS = codeSec(throwerBody, callerBody)
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeS
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 77, s"expected 77, got $v")
    }
