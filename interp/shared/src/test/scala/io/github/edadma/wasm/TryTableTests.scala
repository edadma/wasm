package io.github.edadma.wasm

import TestSupport.*

/** Exception Handling, modern (`try_table`) form. Single opcode `0x1F`
  * replaces the legacy `try / catch / catch_all / delegate / rethrow`
  * combo with a catch-clause vector parsed up-front. A new opcode
  * `0x0A throw_ref` re-raises an exception held in an `exnref` value.
  *
  * Each test hand-builds a tiny module so the suite stays dependency-free.
  * Byte-layout pattern mirrors `ExceptionHandlingTests` / `NumericTests`.
  *
  * Wire encoding recap:
  *   - `0x1F blocktype vec(catch-clause)`
  *   - clause byte 0x00 = catch tagidx labelidx
  *   - clause byte 0x01 = catch_ref tagidx labelidx
  *   - clause byte 0x02 = catch_all labelidx
  *   - clause byte 0x03 = catch_all_ref labelidx
  *   - `exnref` valtype byte = `0x69`
  *   - `throw_ref` opcode = `0x0A` (no immediates)
  */
object TryTableTests:

  // === Helpers ============================================================
  //
  // Same lightweight section-builder helpers as ExceptionHandlingTests —
  // duplicated rather than imported so each test file is self-contained.

  private def sec(id: Int, content: Array[Byte]): Array[Byte] =
    b(id, content.length) ++ content

  private def typeSec(entries: Array[Byte]*): Array[Byte] =
    sec(0x01, b(entries.length) ++ entries.flatten)

  private def ft(params: Seq[Int], results: Seq[Int]): Array[Byte] =
    b(0x60, params.length) ++ b(params*) ++ b(results.length) ++ b(results*)

  private def funcSec(typeIdxs: Int*): Array[Byte] =
    sec(0x03, b(typeIdxs.length) ++ b(typeIdxs*))

  private def tagSec(typeIdxs: Int*): Array[Byte] =
    val body = b(typeIdxs.length) ++ typeIdxs.flatMap(ti => Seq(0x00, ti)).toArray.map(_.toByte)
    sec(13, body)

  private def expSec(entries: (String, Int, Int)*): Array[Byte] =
    val body = b(entries.length) ++ entries.toArray.flatMap { case (name, kind, idx) =>
      val nb = name.getBytes("UTF-8")
      b(nb.length) ++ nb ++ b(kind, idx)
    }
    sec(0x07, body)

  private def codeSec(bodies: Array[Byte]*): Array[Byte] =
    val payload = b(bodies.length) ++ bodies.flatMap { body =>
      val withLocals = b(0x00) ++ body
      b(withLocals.length) ++ withLocals
    }
    sec(0x0a, payload)

  // Blocktype byte aliases.
  private val BT_VOID:   Int = 0x40
  private val BT_I32:    Int = 0x7f

  // === Test runner =========================================================

  def run(): Unit =
    parser()
    runtime()
    validator()

  // === Parser =============================================================

  private def parser(): Unit =

    test("try_table parser: handler vector stored on BlockInfo by pre-scan") {
      // Build a single body: try_table with two clauses (catch tag=0 label=0
      // and catch_all label=0), inside a () -> () function. Compile via the
      // public Runtime path and confirm the function instantiates.
      val typeS = typeSec(ft(Nil, Nil))         // type 0: () -> () for both func and tag
      val funcS = funcSec(0)
      val tagS  = tagSec(0)
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(
        0x1f, BT_VOID,                          // try_table (no result)
          0x02,                                  // 2 catch clauses
            0x00, 0x00, 0x00,                    // catch tag=0 label=0 (the try_table itself)
            0x02, 0x00,                          // catch_all label=0
        0x0b, 0x0b,                              // end try_table, end func
      )
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeSec(body)
      runOk(Runtime.instantiate(bytes, Seq(EnvModule.default)))
    }

  // === Runtime ============================================================

  private def runtime(): Unit =

    test("try_table runtime: catch — throw inside try_table branches to outer block with i32 payload") {
      // Tag t = (i32) -> (). Function f = () -> i32. Layout:
      //   (block $B (result i32)
      //     (try_table (result i32)
      //       (catch $t $B)        ;; labelidx 1 (try_table=0, $B=1)
      //       i32.const 42
      //       throw $t
      //       i32.const 0))         ;; unreachable
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),     // 0: () -> i32   (func + try_table + block result)
        ft(Seq(BT_I32), Nil),     // 1: (i32) -> () (tag — payload pushed on catch)
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(
        0x02, BT_I32,                   // block (result i32)
          0x1f, BT_I32,                 //   try_table (result i32)
            0x01,                       //     1 catch clause
              0x00, 0x00, 0x01,         //     catch tag=0 label=1 (the outer block)
          0x41, 0x2a,                   //     i32.const 42  (becomes tag payload)
          0x08, 0x00,                   //     throw $t
          0x41, 0x00,                   //     i32.const 0   (unreachable)
          0x0b,                         //   end try_table  (fall-through: 0 on stack)
        0x0b,                           // end block        (either path leaves [i32] on stack)
        0x0b,                           // end func
      )
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeSec(body)
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 42, s"expected 42, got $v")
    }

    test("try_table runtime: catch_all — matches any tag, branches with no payload") {
      // Tag $t0 with no payload. Function returns i32. Outer block carries
      // no result; catch_all branches to it with no carry, then we push
      // the answer after the block end.
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),     // 0: () -> i32 (func)
        ft(Nil, Nil),             // 1: () -> () (tag — empty results required)
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)         // tag references type 1 (() -> ())
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(
        0x02, BT_VOID,                  // block $B (no result)
          0x1f, BT_VOID,                //   try_table (no result)
            0x01,                       //     1 catch clause
              0x02, 0x01,               //     catch_all label=1 ($B)
          0x08, 0x00,                   //     throw $t0
          0x0b,                         //   end try_table
        0x0b,                           // end block
        0x41, 0x07,                     // i32.const 7  (produced after $B)
        0x0b,                           // end func
      )
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeSec(body)
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 7, s"expected 7, got $v")
    }

    test("try_table runtime: catch_all_ref — exnref payload bound, ref.is_null returns 0") {
      // catch_all_ref binds the caught exnref. We use ref.is_null to
      // assert it's non-null (since we just threw the exception), and
      // return 1 if so. Drops the exnref before the answer is produced.
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),     // 0: () -> i32 (func)
        ft(Nil, Nil),             // 1: () -> () (tag)
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)         // typeidx 1 = () -> ()
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(
        0x02, BT_I32,                   // block $B (result i32)
          0x1f, BT_VOID,                //   try_table (no result; catch_all_ref pushes exnref outside)
            0x01,                       //     1 catch clause
              0x03, 0x01,               //     catch_all_ref label=1 ($B)
          0x08, 0x00,                   //     throw $t0
          0x0b,                         //   end try_table
        0x0b,                           // end block — at this point top of stack is exnref (from catch_all_ref)
        // After the block end, the carry is whatever was pushed at branch.
        // Hmm: catch_all_ref pushes an exnref; the OUTER block's branchArity
        // must equal that. So $B's result is exnref, not i32 — refactor:
        0x0b,                           // end func — but this won't typecheck. Test below uses block-of-exnref.
      )
      // The above design doesn't type-check ($B result mismatches func
      // return). Let me redo: use a block of exnref result, then convert
      // via ref.is_null to i32.
      val body2 = b(
        0x02, 0x69,                     // block $B (result exnref)
          0x1f, BT_VOID,                //   try_table (no result)
            0x01,                       //     1 catch clause
              0x03, 0x01,               //     catch_all_ref label=1 ($B)
          0x08, 0x00,                   //     throw $t0   (catch fires, branches to $B with exnref)
          0x0b,                         //   end try_table  (fall-through: $B needs an exnref result on this path too — won't be taken)
          0xd0, 0x69,                   //   ref.null exn   (unreachable filler so fall-through is typed)
        0x0b,                           // end block — exnref on stack
        0xd1,                           // ref.is_null    (i32 = 0 if non-null, 1 if null)
        0x41, 0x01,                     // i32.const 1
        0x73,                           // i32.xor       (so non-null → 1, null → 0)
        0x0b,                           // end func
      )
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeSec(body2)
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 1, s"expected 1 (caught exnref was non-null), got $v")
    }

    test("try_table runtime: throw_ref re-raises a caught exnref to an outer try_table") {
      // Outer try_table catches via catch_all; inner try_table catches
      // via catch_all_ref and re-throws via throw_ref. The outer
      // try_table's catch_all then receives.
      //
      //   (block $OUTER (result i32)
      //     (try_table
      //       (catch_all $OUTER)         ;; outer-catch branches to $OUTER (no carry)
      //       (block $INNER (result exnref)
      //         (try_table
      //           (catch_all_ref $INNER) ;; inner-catch branches to $INNER with exnref carry
      //           throw $t0)
      //         (ref.null exn))           ;; unreachable filler
      //       throw_ref))                  ;; re-throw — escapes the outer try_table normally,
      //                                    ;;   but the outer catch_all catches it
      //   (i32.const 99)                   ;; runs after $OUTER's end
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),
        ft(Nil, Nil),
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)
      val expS  = expSec(("f", 0x00, 0))
      val body2 = b(
        0x02, BT_VOID,
          0x1f, BT_VOID,
            0x01,
              0x02, 0x01,
            0x02, 0x69,
              0x1f, BT_VOID,
                0x01,
                  0x03, 0x01,
                0x08, 0x00,
              0x0b,
              0xd0, 0x69,
            0x0b,
            0x0a,
          0x0b,
        0x0b,
        0x41, 0x05,
        0x0b,
      )
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeSec(body2)
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 5, s"expected 5 (after outer catch_all + post-block constant), got $v")
    }

    test("try_table runtime: body without throw falls through normally") {
      // try_table whose body just produces a constant; the catch is dead.
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),
        ft(Nil, Nil),
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(
        0x02, BT_I32,                   // block $B (result i32)
          0x1f, BT_I32,                 //   try_table (result i32)
            0x01,                       //     1 catch clause
              0x02, 0x01,               //     catch_all label=1 ($B)   ;; catch payload = []
          // catch_all $B needs $B's branchArity = []. $B has result i32 — branchArity = 1. MISMATCH.
          // Refactor: $B with no result; we produce the answer after.
          0x0b,
        0x0b,
        0x0b,
      )
      // Refactor: outer block has no result; catch_all branches to it with no carry.
      val body2 = b(
        0x02, BT_VOID,                  // block $B (no result)
          0x1f, BT_VOID,                //   try_table (no result)
            0x01,                       //     1 catch clause
              0x02, 0x01,               //     catch_all label=1 ($B)
          // body: just fall through
          0x0b,                         //   end try_table  (normal fall-through)
        0x0b,                           // end $B
        0x41, 0x11,                     // i32.const 17
        0x0b,                           // end func
      )
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeSec(body2)
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 17, s"expected 17, got $v")
    }

    test("try_table runtime: non-matching tag propagates outward to enclosing handler") {
      // Inner try_table has catch on $t0 only; throw is $t1 — propagates
      // to outer try_table's catch_all.
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),
        ft(Nil, Nil),
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1, 1)        // two tags, both typeidx 1 = () -> ()
      val expS  = expSec(("f", 0x00, 0))
      val body = b(
        0x02, BT_VOID,                  // block $OUTER (no result)
          0x1f, BT_VOID,                //   try_table (outer)
            0x01,
              0x02, 0x01,               //     catch_all label=1 ($OUTER)
            0x1f, BT_VOID,              //     try_table (inner) — catches only $t0
              0x01,
                0x00, 0x00, 0x00,       //       catch tag=0 label=0 (the inner try_table — i.e. fall through to its own end)
              0x08, 0x01,               //     throw $t1
            0x0b,                       //     end inner try_table
          0x0b,                         //   end outer try_table
        0x0b,                           // end $OUTER
        0x41, 0x29,                     // i32.const 41
        0x0b,                           // end func
      )
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeSec(body)
      val inst  = instantiate(bytes)
      val v     = callI32(inst, "f")
      check(v == 41, s"expected 41, got $v")
    }

    test("try_table runtime: catch_ref binds exnref + payload, throw_ref re-raises") {
      // Tag $t1 with i32 payload. Inner try_table catches via catch_ref —
      // payload + exnref pushed. Inner block uses the i32 (drops it),
      // keeps the exnref, and throw_refs out. Outer catches via catch_all.
      val typeS = typeSec(
        ft(Nil, Seq(BT_I32)),     // 0: () -> i32 (func)
        ft(Seq(BT_I32), Nil),     // 1: (i32) -> () (tag $t1)
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)
      val expS  = expSec(("f", 0x00, 0))
      val body = b(
        0x02, BT_VOID,                  // block $OUTER (no result)
          0x1f, BT_VOID,                //   try_table (outer)
            0x01,
              0x02, 0x01,               //     catch_all label=1 ($OUTER)
            // Inner block whose result is exnref — catch_ref's payload is
            // (i32, exnref); we need a block that consumes the i32 and
            // leaves the exnref. Use a result-typed block that catches i32+exnref.
            // Simpler: use a block whose result is exnref; catch_ref branches
            // to it with (i32, exnref) — but block-branch arity must match,
            // so block must have result (i32, exnref). Sysl-shaped → multi-value.
            // Easier still: don't use a block; just throw the exception with
            // a payload, catch_ref to label=0 (= inner try_table itself), inner
            // try_table has result (i32, exnref).
            0x1f, 0x02,                 //     try_table (inner) — blocktype typeidx 2... but we don't have one.
        0x0b, 0x0b,
      )
      // The above is awkward. Use a simpler test instead: catch_ref to
      // an outer block with multi-value result type. Define a third type
      // for the block: (i32, exnref).
      val typeS2 = typeSec(
        ft(Nil, Seq(BT_I32)),                  // 0: () -> i32 (func)
        ft(Seq(BT_I32), Nil),                  // 1: (i32) -> () (tag)
        ft(Nil, Seq(BT_I32, 0x69)),            // 2: () -> (i32, exnref) — block / try_table type
      )
      val body2 = b(
        0x02, 0x02,                     // block $B (typeidx 2 — result (i32, exnref))
          0x1f, BT_VOID,                //   try_table (no result; catch_ref branches with carry)
            0x01,
              0x01, 0x00, 0x01,         //     catch_ref tag=0 label=1 ($B with arity [i32, exnref])
          0x41, 0xd5, 0x00,             //   i32.const 85 (SLEB: bit 6 set → 2 bytes)
          0x08, 0x00,                   //   throw $t0    (with i32 payload 85)
          0x0b,                         //   end try_table
          // Fall-through path (dead): need to push (i32, exnref). Use
          // unreachable filler.
          0x00,                         //   unreachable
        0x0b,                           // end $B — top of stack: [i32, exnref]
        0x0a,                           // throw_ref — pops exnref, raises caught exception
        0x0b,                           // end func — never reached (throw_ref propagates)
      )
      // ^ The throw_ref propagates to the function's caller. The test
      // expects an UncaughtException carrying the original payload [85].
      val bytes = Header ++ typeS2 ++ funcS ++ tagS ++ expS ++ codeSec(body2)
      val inst  = instantiate(bytes)
      inst.invoke("f", Seq.empty) match
        case Left(WasmError.UncaughtException(0, Seq(I32(85)))) => ()
        case other =>
          check(false, s"expected UncaughtException(0, [I32(85)]) after throw_ref, got $other")
    }

  // === Validator ===========================================================

  private def validator(): Unit =

    test("try_table validator: tag index out of range rejected") {
      val typeS = typeSec(ft(Nil, Nil))
      val funcS = funcSec(0)
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(
        0x1f, BT_VOID,
          0x01,
            0x00, 0x09, 0x00,           // catch tag=9 label=0 — tag 9 doesn't exist
        0x0b, 0x0b,
      )
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(body)
      Parser.parse(bytes).flatMap(m => Validator.validate(m).map(_ => m)) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("tag index 9"), s"unexpected message: $msg")
        case other =>
          check(false, s"expected InvalidModule(tag 9), got $other")
    }

    test("try_table validator: label index out of range rejected") {
      val typeS = typeSec(ft(Nil, Nil))
      val funcS = funcSec(0)
      val tagS  = tagSec(0)
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(
        0x1f, BT_VOID,
          0x01,
            0x02, 0x09,                 // catch_all label=9 — out of range
        0x0b, 0x0b,
      )
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeSec(body)
      Parser.parse(bytes).flatMap(m => Validator.validate(m).map(_ => m)) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("label index 9") || msg.contains("label 9"),
                s"unexpected message: $msg")
        case other =>
          check(false, s"expected InvalidModule(label 9), got $other")
    }

    test("try_table validator: catch payload arity mismatch rejected") {
      // catch tag=0 (which has i32 payload) branching to a label that
      // expects no values → arity mismatch.
      val typeS = typeSec(
        ft(Nil, Nil),               // 0: () -> ()  (func)
        ft(Seq(BT_I32), Nil),       // 1: (i32) -> () (tag — i32 payload)
      )
      val funcS = funcSec(0)
      val tagS  = tagSec(1)
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(
        0x02, BT_VOID,              // block (no result, no carry)
          0x1f, BT_VOID,            //   try_table
            0x01,
              0x00, 0x00, 0x01,     //     catch tag=0 (i32 payload) label=1 (block with no carry) — mismatch
          0x0b,
        0x0b,
        0x0b,
      )
      val bytes = Header ++ typeS ++ funcS ++ tagS ++ expS ++ codeSec(body)
      Parser.parse(bytes).flatMap(m => Validator.validate(m).map(_ => m)) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("payload types"), s"unexpected message: $msg")
        case other =>
          check(false, s"expected InvalidModule(payload arity), got $other")
    }

    test("try_table validator: throw_ref without an exnref on stack rejected") {
      val typeS = typeSec(ft(Nil, Nil))
      val funcS = funcSec(0)
      val expS  = expSec(("f", 0x00, 0))
      val body  = b(0x0a, 0x0b)         // throw_ref with empty stack; end
      val bytes = Header ++ typeS ++ funcS ++ expS ++ codeSec(body)
      Parser.parse(bytes).flatMap(m => Validator.validate(m).map(_ => m)) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("underflow") || msg.contains("expected exnref"),
                s"unexpected message: $msg")
        case other =>
          check(false, s"expected InvalidModule(stack underflow for throw_ref), got $other")
    }
