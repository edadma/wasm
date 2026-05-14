package io.github.edadma.wasm.wasi

import io.github.edadma.wasm.{I32, I64}

import WasiTestSupport.{check, instantiate, test}

/** Phase 7.C — `clock_time_get` and `random_get`. The fixture
  * (`wasi_clock_random.wat`) exposes both syscalls directly through
  * `call_clock_time_get` / `call_random_get` plus the usual
  * `write_byte` / `load_byte` / `load_i32` / `load_i64` peek helpers.
  *
  * Tests inject deterministic clock + random sources via
  * [[WasiContext.collecting]] so the assertions read exact values
  * back. The default-impl behaviour (`System.currentTimeMillis * 1e6`
  * and `scala.util.Random`) isn't directly pinned by these tests — the
  * point of having injection points is precisely so tests don't have
  * to. A smoke check that `Wasi.preview1` registers the slot at all is
  * implicit in every test that invokes it.
  */
object WasiClockTests:

  // === Deterministic test fixtures ========================================

  /** A clock that returns hard-coded distinct nanos for each method so we
    * can pin which clock the shim consulted. Realtime ≈ 2023-11-14 UTC,
    * monotonic = 42_000ns; the values themselves are arbitrary, only
    * the fact that they differ matters. */
  private val fixedClock: WasiContext.Clock = new WasiContext.Clock:
    def realtimeNanos():  Long = 1_700_000_000_000_000_000L
    def monotonicNanos(): Long = 42_000L

  /** Deterministic byte source — the i-th byte is `(i * 7 + 3) & 0xff`.
    * Lets us assert on exact bytes the shim wrote without seeding any
    * RNG. Length-respecting: returns exactly `n` bytes. */
  private val stubRandom: Int => Array[Byte] = n =>
    Array.tabulate[Byte](n)(i => ((i * 7 + 3) & 0xff).toByte)

  // === Tests ==============================================================

  def run(): Unit =
    println()
    println("-- WasiClockTests --")

    // ----- clock_time_get -------------------------------------------------

    test("clock_time_get: id=0 (realtime) writes the injected nanos") {
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random,
                                  clock = fixedClock)
      inst.invoke("call_clock_time_get",
                  Seq(I32(0), I64(0L), I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_clock_time_get: $other")
      inst.invoke("load_i64", Seq(I32(0))) match
        case Right(Seq(I64(v))) =>
          check(v == 1_700_000_000_000_000_000L,
                s"realtime=$v (want 1_700_000_000_000_000_000)")
        case other => check(false, s"load_i64: $other")
    }

    test("clock_time_get: id=1 (monotonic) writes the injected nanos") {
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random,
                                  clock = fixedClock)
      inst.invoke("call_clock_time_get",
                  Seq(I32(1), I64(0L), I32(8))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_clock_time_get: $other")
      inst.invoke("load_i64", Seq(I32(8))) match
        case Right(Seq(I64(v))) =>
          check(v == 42_000L, s"monotonic=$v (want 42_000)")
        case other => check(false, s"load_i64: $other")
    }

    test("clock_time_get: id=2 (process_cputime) folds to monotonic") {
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random,
                                  clock = fixedClock)
      inst.invoke("call_clock_time_get",
                  Seq(I32(2), I64(0L), I32(16))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_clock_time_get: $other")
      inst.invoke("load_i64", Seq(I32(16))) match
        case Right(Seq(I64(v))) =>
          check(v == 42_000L,
                s"process_cputime=$v (want monotonic 42_000)")
        case other => check(false, s"load_i64: $other")
    }

    test("clock_time_get: id=3 (thread_cputime) folds to monotonic") {
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random,
                                  clock = fixedClock)
      inst.invoke("call_clock_time_get",
                  Seq(I32(3), I64(0L), I32(24))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_clock_time_get: $other")
      inst.invoke("load_i64", Seq(I32(24))) match
        case Right(Seq(I64(v))) =>
          check(v == 42_000L,
                s"thread_cputime=$v (want monotonic 42_000)")
        case other => check(false, s"load_i64: $other")
    }

    test("clock_time_get: unknown clock id returns EINVAL") {
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random,
                                  clock = fixedClock)
      inst.invoke("call_clock_time_get",
                  Seq(I32(99), I64(0L), I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EINVAL, s"errno=$errno (want EINVAL=${Wasi.EINVAL})")
        case other => check(false, s"call_clock_time_get: $other")
      // No bytes should have landed at the time_ptr — the timestamp word
      // at offset 0 is still all zeros (the fresh memory's initial state).
      inst.invoke("load_i64", Seq(I32(0))) match
        case Right(Seq(I64(v))) =>
          check(v == 0L, s"time_ptr should be untouched on EINVAL (got $v)")
        case other => check(false, s"load_i64: $other")
    }

    test("clock_time_get: EFAULT when time_ptr+8 extends past memory end") {
      // memory is 1 page = 65536 bytes. time_ptr=65530 leaves 6 bytes
      // of headroom — not enough for the 8-byte timestamp.
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random,
                                  clock = fixedClock)
      inst.invoke("call_clock_time_get",
                  Seq(I32(0), I64(0L), I32(65530))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
        case other => check(false, s"call_clock_time_get: $other")
    }

    // ----- random_get -----------------------------------------------------

    test("random_get: writes deterministic bytes from the injected source") {
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random,
                                  random = stubRandom)
      val n = 16
      inst.invoke("call_random_get", Seq(I32(0), I32(n))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_random_get: $other")
      val expected = Array.tabulate[Byte](n)(i => ((i * 7 + 3) & 0xff).toByte)
      var i = 0
      while i < n do
        inst.invoke("load_byte", Seq(I32(i))) match
          case Right(Seq(I32(b))) =>
            val want = expected(i) & 0xff
            check(b == want, s"byte[$i]=$b (want $want)")
          case other => check(false, s"load_byte($i): $other")
        i += 1
    }

    test("random_get: zero-length buffer is ESUCCESS no-op") {
      var calls = 0
      val countingRandom: Int => Array[Byte] = n => { calls += 1; new Array[Byte](n) }
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random,
                                  random = countingRandom)
      inst.invoke("call_random_get", Seq(I32(0), I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_random_get: $other")
      // Skipping the random source on len=0 is observable and intentional —
      // it lets tests inject a misbehaving source without flakiness on
      // an empty request. Programs that depend on the source being
      // *called* for zero-length requests would be in the wrong.
      check(calls == 0, s"random source called $calls times (want 0)")
    }

    test("random_get: EFAULT when buf+buf_len extends past memory end") {
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random,
                                  random = stubRandom)
      // 1 page = 65536 bytes. buf=65000, len=1000 → end 66000 > 65536.
      inst.invoke("call_random_get", Seq(I32(65000), I32(1000))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
        case other => check(false, s"call_random_get: $other")
    }
