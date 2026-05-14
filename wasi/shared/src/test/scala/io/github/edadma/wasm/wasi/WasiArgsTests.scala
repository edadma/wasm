package io.github.edadma.wasm.wasi

import io.github.edadma.wasm.I32

import WasiTestSupport.{check, instantiate, readCString, test}

/** Phase 7.B — `args_*` and `environ_*` syscalls. The fixture
  * (`wasi_args.wat`) is a generic passthrough that exposes each of the
  * four syscalls plus `load_i32` / `load_byte` peek helpers; tests
  * plant pointer-vec + buffer addresses, invoke the syscall, then read
  * back what the shim wrote.
  */
object WasiArgsTests:

  def run(): Unit =
    println()
    println("-- WasiArgsTests --")

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
      check(readCString(inst, ptrs(0)) == "FOO=bar",     "env[0]")
      check(readCString(inst, ptrs(1)) == "PATH=/usr/x", "env[1]")
    }
