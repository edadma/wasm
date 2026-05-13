package io.github.edadma.wasm

import java.lang as jl
import TestSupport.*

/** End-to-end tests for Phase 2: globals.
  *
  * Covers Section 6 init-expr decoding for every numeric type, `global.get` /
  * `global.set` dispatch, persistence across calls, fresh-per-instance
  * isolation, the immutable-write trap, signed-zero / Inf preservation, and
  * `ModuleInstance.globalValue` accessor consistency.
  */
object GlobalsTests:

  def run(): Unit =

    test("globals: counter persists across calls; tee replacement via get-after-set") {
      val inst = instantiate(Fixtures.globals_basic)
      // Counter starts at zero per the const init.
      check(callI32(inst, "get_count") == 0, "fresh instance starts at 0")
      // Two consecutive bumps must observe the running total — this is the
      // signature behaviour globals add over locals (state across calls).
      check(callI32(inst, "bump") == 1, "first bump → 1")
      check(callI32(inst, "bump") == 2, "second bump → 2")
      check(callI32(inst, "bump") == 3, "third bump → 3")
      check(callI32(inst, "get_count") == 3, "counter visible from a separate getter")
      // set_count returns the prior value and stores the new one.
      check(callI32(inst, "set_count", 100) == 3,   "set_count returns previous value")
      check(callI32(inst, "get_count")     == 100, "new value stuck")
      // Each fresh instantiation gets its own globals — instances don't share.
      val inst2 = instantiate(Fixtures.globals_basic)
      check(callI32(inst2, "get_count") == 0, "second instance starts at 0 (no cross-instance bleed)")
    }

    test("globals: immutable seed readable; module-instance exposes both globals via globalValue") {
      val inst = instantiate(Fixtures.globals_basic)
      check(callI32(inst, "get_seed") == 42, "seed reads back as 42")
      inst.globalValue("seed") match
        case Right(I32(42)) => ()
        case other          => check(false, s"`seed` global export should read 42: $other")
      // Direct read of `counter` mirrors the function-getter reading.
      inst.globalValue("counter") match
        case Right(I32(0)) => ()
        case other         => check(false, s"`counter` should start at 0: $other")
      runRight(inst.invoke("bump"))
      inst.globalValue("counter") match
        case Right(I32(1)) => ()
        case other         => check(false, s"`counter` should now be 1: $other")
    }

    test("globals: per-type round-trip — i32 / i64 / f32 / f64 init values decode correctly") {
      val inst = instantiate(Fixtures.globals_types)
      // Init values from the section-6 init-expr decode (each *.const form).
      check(callI32(inst, "get_i32") == 0x0bad0dad,            "i32 init")
      check(callI64(inst, "get_i64") == 0x1122334455667788L,   "i64 init")
      check(callF32(inst, "get_f32") == 1.5f,                  "f32 init")
      check(callF64(inst, "get_f64") == -2.5,                  "f64 init")

      // set, then read back — confirms `global.set` is type-stable for each type.
      runRight(inst.invoke("set_i32", Seq(I32(-7))))
      runRight(inst.invoke("set_i64", Seq(I64(Long.MinValue))))
      runRight(inst.invoke("set_f32", Seq(F32(Float.NaN))))
      runRight(inst.invoke("set_f64", Seq(F64(Double.PositiveInfinity))))

      check(callI32(inst, "get_i32") == -7,                            "i32 round-trip")
      check(callI64(inst, "get_i64") == Long.MinValue,                 "i64 round-trip")
      check(jl.Float.isNaN(callF32(inst, "get_f32")),                  "f32 NaN survives")
      check(callF64(inst, "get_f64") == Double.PositiveInfinity,       "f64 +Inf survives")

      // The globalValue accessor sees the same live state.
      inst.globalValue("gi") match
        case Right(I32(-7))                            => ()
        case other                                     => check(false, s"gi: $other")
      inst.globalValue("gj") match
        case Right(I64(v)) if v == Long.MinValue       => ()
        case other                                     => check(false, s"gj: $other")
      inst.globalValue("gf") match
        case Right(F32(v)) if jl.Float.isNaN(v)        => ()
        case other                                     => check(false, s"gf: $other")
      inst.globalValue("gd") match
        case Right(F64(v)) if v == Double.PositiveInfinity => ()
        case other                                     => check(false, s"gd: $other")
    }

    test("globals: ±Inf and signed-zero survive a set/get round-trip for f32 and f64") {
      // NaN-payload preservation across a global slot isn't reliable on
      // Scala.js (Float-boxing through JS `number` lets the engine canonicalise
      // the bit pattern); the per-type test above already pins NaN-as-NaN via
      // isNaN. What we additionally want pinned here is that *non-NaN* IEEE
      // specials — signed zeros, infinities — survive bit-exact, which they
      // must to keep arithmetic semantics intact.
      val inst = instantiate(Fixtures.globals_types)

      runRight(inst.invoke("set_f32", Seq(F32(-0.0f))))
      check(jl.Float.floatToRawIntBits(callF32(inst, "get_f32"))
              == jl.Float.floatToRawIntBits(-0.0f),
            "f32 -0 sign bit preserved across set/get")

      runRight(inst.invoke("set_f32", Seq(F32(Float.NegativeInfinity))))
      check(callF32(inst, "get_f32") == Float.NegativeInfinity, "f32 -Inf survives")

      runRight(inst.invoke("set_f64", Seq(F64(-0.0))))
      check(jl.Double.doubleToRawLongBits(callF64(inst, "get_f64"))
              == jl.Double.doubleToRawLongBits(-0.0),
            "f64 -0 sign bit preserved across set/get")

      runRight(inst.invoke("set_f64", Seq(F64(Double.NegativeInfinity))))
      check(callF64(inst, "get_f64") == Double.NegativeInfinity, "f64 -Inf survives")
    }

    test("globals: set on immutable global traps with InvalidModule(\"immutable\")") {
      val inst = instantiate(Fixtures.globals_immutable_trap)
      // Reading the const still works.
      check(callI32(inst, "get_k") == 99, "immutable global readable")
      // Writing traps with a recognisable message.
      expectError(inst, "try_overwrite", Seq(I32(0))) {
        case WasmError.InvalidModule(m) => m.contains("immutable")
      }
      // And the value should still be 99 — the trap fires before mutation.
      check(callI32(inst, "get_k") == 99, "value unchanged after failed write")
    }
