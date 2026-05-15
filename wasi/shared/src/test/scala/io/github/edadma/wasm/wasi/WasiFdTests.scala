package io.github.edadma.wasm.wasi

import io.github.edadma.wasm.{I32, WasmError}

import WasiTestSupport.{check, instantiate, runOk, test}

/** fd_write, fd_close, proc_exit + the `Wasi.run` runner. Phase 7.A
  * shipped the fd_write surface (success / EBADF / EFAULT, multi-iovec,
  * routing to stdout vs stderr) and the proc_exit unwind; Phase 7.C
  * adds fd_close (ESUCCESS for stdin/stdout/stderr, EBADF otherwise —
  * real fs fds come in 7.E).
  */
object WasiFdTests:

  def run(): Unit =
    println()
    println("-- WasiFdTests --")

    // ----- fd_write happy paths -------------------------------------------

    test("hello_wasi: _start writes 'Hello, WASI!\\n' to fd 1") {
      val (inst, collecting) = instantiate(WasiFixtures.hello_wasi)
      Wasi.run(inst) match
        case Right(0) => ()
        case other    => check(false, s"expected Right(0), got $other")
      check(collecting.stdoutString == "Hello, WASI!\n",
            s"stdout: ${collecting.stdoutString.toList}")
      check(collecting.stderrBytes.isEmpty,
            "stderr should be empty for a stdout-only program")
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

    // ----- proc_exit + Wasi.run --------------------------------------------

    test("wasi_exit: proc_exit(42) unwinds via Wasi.run with code 42") {
      val (inst, collecting) = instantiate(WasiFixtures.wasi_exit)
      Wasi.run(inst) match
        case Right(42) => ()
        case other     => check(false, s"expected Right(42), got $other")
      check(collecting.stdoutBytes.isEmpty, "stdout should be empty")
      check(collecting.stderrBytes.isEmpty, "stderr should be empty")
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

    // ----- fd_write EBADF ---------------------------------------------------

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

    // ----- fd_write EFAULT --------------------------------------------------

    test("fd_write: EFAULT when iovec table extends past memory end") {
      val (inst, _) = instantiate(WasiFixtures.wasi_passthrough)
      inst.invoke("fd_write_raw",
                  Seq(I32(1), I32(70000), I32(1), I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
        case other => check(false, s"fd_write_raw result: $other")
    }

    test("fd_write: EFAULT when an iovec's buffer extends past memory end") {
      val (inst, collecting) = instantiate(WasiFixtures.wasi_passthrough)
      // iovec[0] = { buf=65000, buf_len=1000 } → end is 66000 > 65536
      runOk(inst.invoke("write_i32", Seq(I32(0), I32(65000))))
      runOk(inst.invoke("write_i32", Seq(I32(4), I32(1000))))
      inst.invoke("fd_write_raw",
                  Seq(I32(1), I32(0), I32(1), I32(16))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
        case other => check(false, s"fd_write_raw result: $other")
      check(collecting.stdoutBytes.isEmpty,
            "no bytes should have been written on EFAULT")
    }

    test("fd_write: EFAULT when nwritten pointer is past memory end") {
      val (inst, collecting) = instantiate(WasiFixtures.wasi_passthrough)
      // Valid iovec { buf=8, buf_len=1 } and byte at addr 8; corrupt nwritten.
      runOk(inst.invoke("write_byte", Seq(I32(8), I32(0x21)))) // '!'
      runOk(inst.invoke("write_i32",  Seq(I32(0), I32(8))))
      runOk(inst.invoke("write_i32",  Seq(I32(4), I32(1))))
      inst.invoke("fd_write_raw",
                  Seq(I32(1), I32(0), I32(1), I32(70000))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
        case other => check(false, s"fd_write_raw result: $other")
      // Bytes from the valid iovec WERE written to the sink before the
      // nwritten-pointer check fired (the shim writes as it walks). This
      // matches the wasi spec contract: non-zero errno is authoritative
      // and the nwritten slot is undefined on failure.
      check(collecting.stdoutString == "!",
            s"stdout: ${collecting.stdoutString.toList}")
    }

    test("fd_write: zero iovecs is a no-op success, nwritten=0") {
      val (inst, collecting) = instantiate(WasiFixtures.wasi_passthrough)
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

    // ----- fd_close (Phase 7.C) --------------------------------------------

    test("fd_close: stdin/stdout/stderr all return ESUCCESS") {
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random)
      for fd <- Seq(0, 1, 2) do
        inst.invoke("call_fd_close", Seq(I32(fd))) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.ESUCCESS,
                  s"fd_close($fd) errno=$errno (want 0)")
          case other => check(false, s"fd_close($fd) result: $other")
    }

    test("fd_close: fd 3 returns EBADF (no fs fds until Phase 7.E)") {
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random)
      inst.invoke("call_fd_close", Seq(I32(3))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EBADF, s"errno=$errno (want EBADF=${Wasi.EBADF})")
        case other => check(false, s"fd_close(3) result: $other")
    }

    test("fd_close: negative fd returns EBADF") {
      val (inst, _) = instantiate(WasiFixtures.wasi_clock_random)
      inst.invoke("call_fd_close", Seq(I32(-1))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EBADF, s"errno=$errno (want EBADF=${Wasi.EBADF})")
        case other => check(false, s"fd_close(-1) result: $other")
    }
