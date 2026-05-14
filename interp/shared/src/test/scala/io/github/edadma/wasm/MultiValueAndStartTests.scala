package io.github.edadma.wasm

import TestSupport.*

/** Tests for two post-MVP features added together:
  *
  *   1. **Section 8 (Start)** — the WASM binary's hook for an
  *      automatically-invoked initialiser. The parser now recognises
  *      section id 8 (was silently skipped) and surfaces the funcidx
  *      on `WasmModule.startFunction`; `Runtime.instantiate` validates
  *      the target's signature is `() -> ()` and invokes it just
  *      before constructing the `ModuleInstance` (so side effects on
  *      memory / globals are visible to the first user call).
  *
  *   2. **Multi-value** — the post-MVP relaxation of "blocks and
  *      functions return ≤ 1 value." Function signatures already
  *      carried `Vector[ValueType]` for results; what changed is the
  *      blocktype encoding, which now accepts a positive-SLEB typeidx
  *      in addition to the inline empty/i32/i64/f32/f64 bytes. The
  *      typeidx form lets blocks / loops / ifs carry both a
  *      `paramArity` (popped from the surrounding stack on entry) and
  *      a `resultArity` (pushed at end / fall-through). `Label` now
  *      records `branchArity = resultArity` for blocks/ifs and
  *      `paramArity` for loops (loops re-feed their params on `br`).
  *
  * Both features are small but high-leverage: Start unblocks
  * compilers that emit a module-init hook (e.g. C++ static
  * constructors, or a sysl backend wanting to set up its own
  * allocator); multi-value unblocks any language that emits tuple
  * returns or multi-result expressions.
  */
object MultiValueAndStartTests:

  def run(): Unit =
    println()
    println("-- MultiValueAndStartTests --")
    multiValueFunction()
    multiValueBlocks()
    startSection()

  // === multi-value function signatures =====================================

  private def multiValueFunction(): Unit =

    test("multi-value: function returning (i32, i32) yields both on the result Seq") {
      val inst = instantiate(Fixtures.multi_value_func)
      val results = runRight(inst.invoke("two_values"))
      check(results == Seq(I32(7), I32(11)),
            s"results=$results (want Seq(I32(7), I32(11)))")
    }

    test("multi-value: call site receives both values, downstream op consumes both") {
      val inst = instantiate(Fixtures.multi_value_call)
      // sum_pair: call $pair (returns 100, 200) then i32.add → 300
      check(callI32(inst, "sum_pair") == 300,
            "call $pair must push 100 + 200; i32.add consumes both")
    }

  // === multi-value blocks / loops / ifs ====================================

  private def multiValueBlocks(): Unit =

    test("multi-value: block (result i32 i32) — both values survive end + outer i32.add") {
      val inst = instantiate(Fixtures.multi_value_block)
      check(callI32(inst, "block_sum") == 123,
            "block falls through with (100, 23); outer i32.add → 123")
    }

    test("multi-value: block (param i32 i32) consumes 2, returns 1") {
      val inst = instantiate(Fixtures.multi_value_block_param)
      check(callI32(inst, "add_via_block", 3,  4) ==  7,  "3+4 via block-param")
      check(callI32(inst, "add_via_block", 9, 16) == 25,  "9+16 via block-param")
      check(callI32(inst, "add_via_block", -5, 5) ==  0,  "negative cancels out")
    }

    test("multi-value: br_if from a multi-result block preserves both carry values") {
      val inst = instantiate(Fixtures.multi_value_block_branch)
      // a >= b: br_if exits with [a, b]                       (max, min)
      runRight(inst.invoke("max_min", Seq(I32(5),  I32(3)))) match
        case Seq(I32(mx), I32(mn)) =>
          check(mx == 5 && mn == 3, s"a=5 b=3 → ($mx, $mn) (want (5, 3))")
        case other => check(false, s"max_min(5,3) = $other")
      // a < b: fall-through path replaces [a, b] with [b, a]  (max, min)
      runRight(inst.invoke("max_min", Seq(I32(2), I32(7)))) match
        case Seq(I32(mx), I32(mn)) =>
          check(mx == 7 && mn == 2, s"a=2 b=7 → ($mx, $mn) (want (7, 2))")
        case other => check(false, s"max_min(2,7) = $other")
      // a == b: br_if exits (5 >= 5 is true)
      runRight(inst.invoke("max_min", Seq(I32(5), I32(5)))) match
        case Seq(I32(mx), I32(mn)) =>
          check(mx == 5 && mn == 5, s"a=5 b=5 → ($mx, $mn) (want (5, 5))")
        case other => check(false, s"max_min(5,5) = $other")
    }

    test("multi-value: if/else (result i32 i32) — both arms reach the same fall-through") {
      val inst = instantiate(Fixtures.multi_value_if)
      runRight(inst.invoke("if_two", Seq(I32(1)))) match
        case Seq(I32(a), I32(b)) =>
          check(a == 10 && b == 20, s"cond=1 → ($a, $b) (want (10, 20))")
        case other => check(false, s"if_two(1) = $other")
      runRight(inst.invoke("if_two", Seq(I32(0)))) match
        case Seq(I32(a), I32(b)) =>
          check(a == 100 && b == 200, s"cond=0 → ($a, $b) (want (100, 200))")
        case other => check(false, s"if_two(0) = $other")
    }

    test("multi-value: loop (param i32) br re-feeds the param (log2_floor)") {
      val inst = instantiate(Fixtures.multi_value_loop_param)
      check(callI32(inst, "log2_floor",  1) == 0, "log2(1) = 0")
      check(callI32(inst, "log2_floor",  2) == 1, "log2(2) = 1")
      check(callI32(inst, "log2_floor",  8) == 3, "log2(8) = 3")
      check(callI32(inst, "log2_floor", 16) == 4, "log2(16) = 4")
      check(callI32(inst, "log2_floor", 1024) == 10, "log2(1024) = 10")
    }

  // === Section 8 (Start) ===================================================

  private def startSection(): Unit =

    test("start: () -> () function runs at instantiation; side effects on globals visible") {
      val inst = instantiate(Fixtures.start_global)
      // peek reads the global $g, which the start function set to 42.
      check(callI32(inst, "peek") == 42,
            "start function must run before instantiate returns")
    }

    test("start: side effects on linear memory visible to first user call") {
      val inst = instantiate(Fixtures.start_memory)
      check(callI32(inst, "peek") == 0x12345678,
            "start function's i32.store at addr 100 must persist to peek")
    }

    test("start: parsed funcidx surfaces on WasmModule.startFunction") {
      // Direct parser-level assertion: the binary's section 8 (Start) made
      // it onto the typed module record. The instantiate-time invocation
      // is covered by the two side-effect tests above; this pins the
      // parsing layer separately so a regression where the section
      // silently disappears would still fail loudly.
      val mod = Parser.parse(Fixtures.start_global) match
        case Right(m) => m
        case Left(e)  => throw new AssertionError(s"parse failed: $e")
      check(mod.startFunction.contains(0),
            s"startFunction=${mod.startFunction} (want Some(0) for $$init)")
    }

    test("start: out-of-range funcidx returns InvalidModule at instantiation") {
      // Parse the good fixture, then redirect the start funcidx to a
      // value past the function table. Goes through Runtime.instantiate's
      // typed-module overload so we don't have to scan raw bytes for the
      // section-8 offset.
      val good = Parser.parse(Fixtures.start_global) match
        case Right(m) => m
        case Left(e)  => throw new AssertionError(s"parse failed: $e")
      val bad = good.copy(startFunction = Some(99))
      Runtime.instantiate(bad, Seq(EnvModule.default)) match
        case Right(_) => check(false, "instantiate must reject out-of-range start funcidx")
        case Left(WasmError.InvalidModule(m)) =>
          check(m.contains("start") && m.contains("99"),
                s"diagnostic must mention 'start' and the bad index, got: $m")
        case Left(other) => check(false, s"wrong error variant: $other")
    }

    test("start: function with wrong signature returns InvalidModule") {
      // The good fixture has function 0 = $init (() -> ()) and function 1
      // = peek (() -> i32). Redirect start to function 1: the parser
      // accepts a funcidx of any shape, but instantiate must reject any
      // non-`() -> ()` signature.
      val good = Parser.parse(Fixtures.start_global) match
        case Right(m) => m
        case Left(e)  => throw new AssertionError(s"parse failed: $e")
      val bad = good.copy(startFunction = Some(1))
      Runtime.instantiate(bad, Seq(EnvModule.default)) match
        case Right(_) => check(false, "instantiate must reject start with wrong signature")
        case Left(WasmError.InvalidModule(m)) =>
          check(m.contains("start") && m.contains("expected"),
                s"diagnostic must mention 'start' and 'expected', got: $m")
        case Left(other) => check(false, s"wrong error variant: $other")
    }

end MultiValueAndStartTests
