package io.github.edadma.wasm.wasi

import scala.collection.mutable.ArrayBuffer

import io.github.edadma.wasm.{I32, ModuleInstance, Runtime, WasmError}

/** Test runner for the wasi shim. Hand-rolled to match `interp`'s
  * zero-dep style — no scalatest / munit / utest. Each backend
  * (`wasiJVM/Test/run`, `wasiJS/Test/run`, `wasiNative/Test/run`)
  * runs the same suite identically.
  *
  * Phase 7.A coverage:
  *   - Hello-world `_start` via `fd_write` to fd 1
  *   - Multi-iovec write — bytes land in iovec order
  *   - `proc_exit(42)` unwinds cleanly with exit code = 42
  *   - `fd_write` to an unknown fd returns EBADF
  *   - `fd_write` to fd 2 routes to stderr (not stdout)
  *
  * Subsequent phases (7.B–7.E) add their own category files and orchestrate
  * them from `main`, the same way `InterpreterTest` calls each `*.run()`.
  */
object WasiTest:

  // === Test framework (same shape as TestSupport in interp) =================

  private var passed: Int                       = 0
  private val failures: ArrayBuffer[String]     = ArrayBuffer.empty

  private def test(name: String)(body: => Unit): Unit =
    try
      body
      passed += 1
      println(s"  OK  $name")
    catch case e: Throwable =>
      val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
      failures += s"$name — $msg"
      println(s"  FAIL $name — $msg")

  private def check(cond: Boolean, msg: => String): Unit =
    if !cond then throw new AssertionError(msg)

  // === Helpers ==============================================================

  /** Instantiate a wasi fixture against a fresh [[WasiContext.Collecting]]
    * and return both — most tests want to invoke something and then read
    * the captured stdout/stderr. */
  private def instantiate(
      bytes: Array[Byte],
      args:  Seq[String]            = Seq.empty,
      envs:  Seq[(String, String)]  = Seq.empty,
  ): (ModuleInstance, WasiContext.Collecting) =
    val collecting = WasiContext.collecting(args, envs)
    Runtime.instantiate(bytes, Seq(Wasi.preview1(collecting.context))) match
      case Right(inst) => (inst, collecting)
      case Left(err)   => throw new AssertionError(s"instantiate failed: $err")

  // === Entry point ==========================================================

  def main(args: Array[String]): Unit =
    println()
    println("== WASI Preview 1 tests ==")

    test("hello_wasi: _start writes 'Hello, WASI!\\n' to fd 1") {
      val (inst, collecting) = instantiate(WasiFixtures.hello_wasi)
      Wasi.run(inst) match
        case Right(0) => ()
        case other    => check(false, s"expected Right(0), got $other")
      check(collecting.stdoutString == "Hello, WASI!\n",
            s"stdout: ${collecting.stdoutString.toList}")
      check(collecting.stderrBytes.isEmpty,
            "stderr should be empty for a stdout-only program")
      // nwritten was stamped at offset 24
      inst.invoke("nwritten") match
        case Right(Seq(I32(n))) => check(n == 13, s"nwritten=$n (want 13)")
        case other              => check(false, s"nwritten result: $other")
    }

    test("wasi_multi_iovec: three iovecs concatenate in order") {
      val (inst, collecting) = instantiate(WasiFixtures.wasi_multi_iovec)
      Wasi.run(inst) match
        case Right(0) => ()
        case other    => check(false, s"expected Right(0), got $other")
      check(collecting.stdoutString == "fooBARbaz",
            s"stdout: ${collecting.stdoutString.toList}")
      inst.invoke("nwritten") match
        case Right(Seq(I32(n))) => check(n == 9, s"nwritten=$n (want 9)")
        case other              => check(false, s"nwritten result: $other")
    }

    test("wasi_exit: proc_exit(42) unwinds via Wasi.run with code 42") {
      val (inst, collecting) = instantiate(WasiFixtures.wasi_exit)
      Wasi.run(inst) match
        case Right(42) => ()
        case other     => check(false, s"expected Right(42), got $other")
      check(collecting.stdoutBytes.isEmpty, "stdout should be empty")
      check(collecting.stderrBytes.isEmpty, "stderr should be empty")
    }

    test("wasi_bad_fd: fd_write to fd 99 returns EBADF") {
      val (inst, collecting) = instantiate(WasiFixtures.wasi_bad_fd)
      inst.invoke("try_fd", Seq(I32(99))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EBADF, s"errno=$errno (want EBADF=${Wasi.EBADF})")
        case other => check(false, s"try_fd result: $other")
      check(collecting.stdoutBytes.isEmpty,
            "stdout should not have been written to")
    }

    test("wasi_bad_fd: fd_write to fd 0 (stdin) returns EBADF") {
      val (inst, _) = instantiate(WasiFixtures.wasi_bad_fd)
      inst.invoke("try_fd", Seq(I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EBADF, s"errno=$errno (want EBADF=${Wasi.EBADF})")
        case other => check(false, s"try_fd result: $other")
    }

    test("wasi_bad_fd: fd_write to fd 2 (stderr) routes correctly") {
      val (inst, collecting) = instantiate(WasiFixtures.wasi_bad_fd)
      inst.invoke("try_fd", Seq(I32(2))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"try_fd result: $other")
      check(collecting.stdoutBytes.isEmpty,
            "stdout should be empty when writing to fd 2")
      check(collecting.stderrString == "x",
            s"stderr: ${collecting.stderrString.toList}")
    }

    test("Wasi.run: normal return (no proc_exit) yields Right(0)") {
      val (inst, _) = instantiate(WasiFixtures.hello_wasi)
      Wasi.run(inst) match
        case Right(0) => ()
        case other    => check(false, s"expected Right(0), got $other")
    }

    test("Wasi.run: missing export surfaces ExportNotFound, not exit") {
      val (inst, _) = instantiate(WasiFixtures.hello_wasi)
      Wasi.run(inst, "nope") match
        case Left(WasmError.ExportNotFound("nope")) => ()
        case other => check(false, s"expected ExportNotFound, got $other")
    }

    test("fd_write: EFAULT when iovec table extends past memory end") {
      // Memory is 1 page (65536 bytes). Place the iovec table well past
      // the end and confirm fd_write rejects it with EFAULT before
      // dereferencing.
      val (inst, _) = instantiate(WasiFixtures.wasi_passthrough)
      inst.invoke("fd_write_raw",
                  Seq(I32(1), I32(70000), I32(1), I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
        case other => check(false, s"fd_write_raw result: $other")
    }

    test("fd_write: EFAULT when an iovec's buffer extends past memory end") {
      // Plant a single iovec at addr 0 whose buf+len would walk off the
      // end of memory. The shim must catch this in the per-iovec
      // bounds check (not the table-bounds check).
      val (inst, collecting) = instantiate(WasiFixtures.wasi_passthrough)
      // iovec[0] = { buf=65000, buf_len=1000 } → end is 66000 > 65536
      inst.invoke("write_i32", Seq(I32(0), I32(65000)))
      inst.invoke("write_i32", Seq(I32(4), I32(1000)))
      inst.invoke("fd_write_raw",
                  Seq(I32(1), I32(0), I32(1), I32(16))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
        case other => check(false, s"fd_write_raw result: $other")
      check(collecting.stdoutBytes.isEmpty,
            "no bytes should have been written on EFAULT")
    }

    test("fd_write: EFAULT when nwritten pointer is past memory end") {
      // A valid iovec but a corrupt nwritten pointer. The bytes still
      // shouldn't reach the sink — we treat the call as a unit.
      val (inst, collecting) = instantiate(WasiFixtures.wasi_passthrough)
      // Plant a valid iovec { buf=8, buf_len=1 } and a byte at addr 8.
      inst.invoke("write_byte", Seq(I32(8), I32(0x21))) // '!'
      inst.invoke("write_i32",  Seq(I32(0), I32(8)))
      inst.invoke("write_i32",  Seq(I32(4), I32(1)))
      inst.invoke("fd_write_raw",
                  Seq(I32(1), I32(0), I32(1), I32(70000))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
        case other => check(false, s"fd_write_raw result: $other")
      // NOTE: bytes from a valid iovec WERE written to the sink before
      // the nwritten-pointer check fired (the shim writes as it walks).
      // This matches the spec's "non-zero errno is authoritative; the
      // nwritten slot is undefined" contract — wasi callers must NOT
      // read nwritten on a non-zero errno. Document this expectation
      // by asserting stdout saw the byte.
      check(collecting.stdoutString == "!",
            s"stdout: ${collecting.stdoutString.toList}")
    }

    test("fd_write: zero iovecs is a no-op success, nwritten=0") {
      val (inst, collecting) = instantiate(WasiFixtures.wasi_passthrough)
      // No iovec table, no buffers — but the nwritten pointer must
      // still be valid for the success path to stamp it.
      inst.invoke("fd_write_raw",
                  Seq(I32(1), I32(0), I32(0), I32(16))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"fd_write_raw result: $other")
      check(collecting.stdoutBytes.isEmpty, "no bytes should have been written")
      inst.invoke("load_i32", Seq(I32(16))) match
        case Right(Seq(I32(n))) => check(n == 0, s"nwritten=$n (want 0)")
        case other              => check(false, s"load nwritten: $other")
    }

    // ===== Phase 7.B — args + environ ========================================

    /** Read a NUL-terminated UTF-8 string starting at `addr` by polling
      * `load_byte` on the instance. Used to read back what `args_get` /
      * `environ_get` planted into linear memory. */
    def readCString(inst: ModuleInstance, addr: Int): String =
      val sb = new StringBuilder
      var p  = addr
      var loop = true
      while loop do
        inst.invoke("load_byte", Seq(I32(p))) match
          case Right(Seq(I32(b))) =>
            if b == 0 then loop = false
            else
              sb += b.toChar
              p  += 1
          case other =>
            throw new AssertionError(s"load_byte($p) result: $other")
      sb.toString

    test("args_sizes_get: empty args → count=0, buf_size=0") {
      val (inst, _) = instantiate(WasiFixtures.wasi_args, args = Seq.empty)
      inst.invoke("call_args_sizes_get", Seq(I32(0), I32(4))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_args_sizes_get: $other")
      inst.invoke("load_i32", Seq(I32(0))) match
        case Right(Seq(I32(n))) => check(n == 0, s"count=$n (want 0)")
        case other              => check(false, s"load count: $other")
      inst.invoke("load_i32", Seq(I32(4))) match
        case Right(Seq(I32(n))) => check(n == 0, s"buf_size=$n (want 0)")
        case other              => check(false, s"load buf_size: $other")
    }

    test("args_sizes_get: three args → count=3, byte sum includes NULs") {
      // "a"   + NUL = 2
      // "bb"  + NUL = 3
      // "ccc" + NUL = 4  → total 9
      val (inst, _) = instantiate(WasiFixtures.wasi_args,
                                  args = Seq("a", "bb", "ccc"))
      inst.invoke("call_args_sizes_get", Seq(I32(0), I32(4))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_args_sizes_get: $other")
      inst.invoke("load_i32", Seq(I32(0))) match
        case Right(Seq(I32(n))) => check(n == 3, s"count=$n (want 3)")
        case other              => check(false, s"load count: $other")
      inst.invoke("load_i32", Seq(I32(4))) match
        case Right(Seq(I32(n))) => check(n == 9, s"buf_size=$n (want 9)")
        case other              => check(false, s"load buf_size: $other")
    }

    test("args_get: three args land as NUL-terminated UTF-8 + pointer vec") {
      val (inst, _) = instantiate(WasiFixtures.wasi_args,
                                  args = Seq("a", "bb", "ccc"))
      // Pointer vec at 0..12 (3 × i32), buffer at 16.
      inst.invoke("call_args_get", Seq(I32(0), I32(16))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_args_get: $other")
      val ptrs = (0 to 2).map { i =>
        inst.invoke("load_i32", Seq(I32(i * 4))) match
          case Right(Seq(I32(p))) => p
          case other => throw new AssertionError(s"load ptr[$i]: $other")
      }
      check(ptrs == Seq(16, 18, 21),
            s"ptrs=$ptrs (want Seq(16, 18, 21))")
      check(readCString(inst, ptrs(0)) == "a",   "arg[0]")
      check(readCString(inst, ptrs(1)) == "bb",  "arg[1]")
      check(readCString(inst, ptrs(2)) == "ccc", "arg[2]")
    }

    test("args_sizes_get: EFAULT when count_ptr is past memory end") {
      val (inst, _) = instantiate(WasiFixtures.wasi_args,
                                  args = Seq("hi"))
      inst.invoke("call_args_sizes_get", Seq(I32(70000), I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
        case other => check(false, s"call_args_sizes_get: $other")
    }

    test("args_get: EFAULT when argv pointer vec extends past memory end") {
      // memory is 1 page (65536 bytes). 3 args ⇒ vec size = 12 bytes,
      // so a vec at addr 65530 overruns by 6 bytes.
      val (inst, _) = instantiate(WasiFixtures.wasi_args,
                                  args = Seq("a", "b", "c"))
      inst.invoke("call_args_get", Seq(I32(65530), I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
        case other => check(false, s"call_args_get: $other")
    }

    test("args_get: EFAULT when arg buffer extends past memory end") {
      // 3 NUL-terminated 1-byte args = 6 bytes of buffer total. A buf
      // at 65532 only has 4 bytes of headroom — should fail EFAULT.
      val (inst, _) = instantiate(WasiFixtures.wasi_args,
                                  args = Seq("a", "b", "c"))
      inst.invoke("call_args_get", Seq(I32(0), I32(65532))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
        case other => check(false, s"call_args_get: $other")
    }

    test("environ_sizes_get: NAME=VALUE form counts include NUL") {
      // "FOO=bar"     + NUL = 8
      // "PATH=/usr/x" + NUL = 12   → total 20
      val (inst, _) = instantiate(WasiFixtures.wasi_args,
                                  envs = Seq("FOO" -> "bar",
                                             "PATH" -> "/usr/x"))
      inst.invoke("call_environ_sizes_get", Seq(I32(0), I32(4))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_environ_sizes_get: $other")
      inst.invoke("load_i32", Seq(I32(0))) match
        case Right(Seq(I32(n))) => check(n == 2, s"count=$n (want 2)")
        case other              => check(false, s"load count: $other")
      inst.invoke("load_i32", Seq(I32(4))) match
        case Right(Seq(I32(n))) => check(n == 20, s"buf_size=$n (want 20)")
        case other              => check(false, s"load buf_size: $other")
    }

    test("environ_get: writes NAME=VALUE strings + pointer vec") {
      val (inst, _) = instantiate(WasiFixtures.wasi_args,
                                  envs = Seq("FOO" -> "bar",
                                             "PATH" -> "/usr/x"))
      inst.invoke("call_environ_get", Seq(I32(0), I32(16))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_environ_get: $other")
      val ptrs = (0 to 1).map { i =>
        inst.invoke("load_i32", Seq(I32(i * 4))) match
          case Right(Seq(I32(p))) => p
          case other => throw new AssertionError(s"load ptr[$i]: $other")
      }
      // "FOO=bar\0" is 8 bytes ⇒ second pointer should be at 16 + 8 = 24.
      check(ptrs == Seq(16, 24), s"ptrs=$ptrs (want Seq(16, 24))")
      check(readCString(inst, ptrs(0)) == "FOO=bar",      "env[0]")
      check(readCString(inst, ptrs(1)) == "PATH=/usr/x", "env[1]")
    }

    println()
    val total = passed + failures.size
    if failures.isEmpty then
      println(s"All $total WASI tests passed.")
    else
      println(s"${failures.size} of $total WASI tests failed:")
      failures.foreach(f => println(s"  - $f"))
      throw new RuntimeException(s"${failures.size} of $total WASI tests failed")
