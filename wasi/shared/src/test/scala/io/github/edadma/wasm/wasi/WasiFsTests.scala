package io.github.edadma.wasm.wasi

import io.github.edadma.wasm.I32

import WasiTestSupport.{check, instantiate, test}

/** Phase 7.E.1 — preopen-walk syscalls (`fd_prestat_get` and
  * `fd_prestat_dir_name`).
  *
  * These are the two functions wasi-libc reaches for at program startup
  * (before any `path_open` call): it walks fds 3, 4, … asking for each
  * one's `prestat`, stops when the host returns EBADF, and builds an
  * internal map of preopen-name → fd that all later relative-path
  * lookups resolve through.
  *
  * 7.E.1's scope is intentionally narrow — the shim reports the preopen
  * *shape* but no FS-handle operations exist yet. The fixture
  * (`wasi_prestat.wat`) is a passthrough exposing both syscalls plus
  * `load_byte` / `load_i32` peek helpers, and tests inject preopens
  * via the `preopens` parameter on [[WasiTestSupport.instantiate]] /
  * [[WasiContext.collecting]]. Phase 7.E.2 will extend
  * [[WasiContext.Preopen]] with `open(path, ...)` and the rest of the
  * file-handle surface arrives.
  */
object WasiFsTests:

  private val Preopen = WasiContext.Preopen

  def run(): Unit =
    println()
    println("-- WasiFsTests --")

    // ----- fd_prestat_get -------------------------------------------------

    test("fd_prestat_get: zero preopens (default) returns EBADF at fd 3") {
      val (inst, _) = instantiate(WasiFixtures.wasi_prestat)
      inst.invoke("call_prestat_get", Seq(I32(3), I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EBADF, s"errno=$errno (want EBADF=${Wasi.EBADF})")
        case other => check(false, s"call_prestat_get: $other")
    }

    test("fd_prestat_get: single preopen reports tag=dir and the byte name_len") {
      val (inst, _) = instantiate(WasiFixtures.wasi_prestat,
                                  preopens = Seq(Preopen.named("/sandbox")))
      inst.invoke("call_prestat_get", Seq(I32(3), I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_prestat_get: $other")
      // tag byte at offset 0 — only currently-defined preopen variant is dir (0).
      inst.invoke("load_byte", Seq(I32(0))) match
        case Right(Seq(I32(tag))) => check(tag == 0, s"tag=$tag (want 0)")
        case other                => check(false, s"load_byte: $other")
      // pr_name_len at offset 4 — byte length of "/sandbox" = 8.
      inst.invoke("load_i32", Seq(I32(4))) match
        case Right(Seq(I32(n))) => check(n == 8, s"name_len=$n (want 8)")
        case other              => check(false, s"load_i32: $other")
      // Reserved padding bytes 1..3 must be zero — a future preopen
      // variant might pack additional bytes there, so a partial write
      // would leak undefined data into userspace.
      for off <- Seq(1, 2, 3) do
        inst.invoke("load_byte", Seq(I32(off))) match
          case Right(Seq(I32(b))) =>
            check(b == 0, s"pad byte[$off]=$b (want 0)")
          case other => check(false, s"load_byte($off): $other")
    }

    test("fd_prestat_get: two preopens walked at fd 3+4; fd 5 returns EBADF") {
      val pos = Seq(Preopen.named("/a"), Preopen.named("/etc"))
      val (inst, _) = instantiate(WasiFixtures.wasi_prestat, preopens = pos)

      inst.invoke("call_prestat_get", Seq(I32(3), I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"fd=3 errno=$errno")
        case other => check(false, s"call_prestat_get(3): $other")
      inst.invoke("load_i32", Seq(I32(4))) match
        case Right(Seq(I32(n))) => check(n == 2, s"fd=3 name_len=$n (want 2)")
        case other              => check(false, s"load_i32(4): $other")

      inst.invoke("call_prestat_get", Seq(I32(4), I32(16))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"fd=4 errno=$errno")
        case other => check(false, s"call_prestat_get(4): $other")
      inst.invoke("load_i32", Seq(I32(20))) match
        case Right(Seq(I32(n))) => check(n == 4, s"fd=4 name_len=$n (want 4)")
        case other              => check(false, s"load_i32(20): $other")

      // fd 5 is one past the end — terminates wasi-libc's walk.
      inst.invoke("call_prestat_get", Seq(I32(5), I32(32))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EBADF, s"fd=5 errno=$errno (want EBADF)")
        case other => check(false, s"call_prestat_get(5): $other")
    }

    test("fd_prestat_get: stdin/stdout/stderr never look like preopens") {
      // Even with a preopen present, fds 0/1/2 must still report EBADF
      // — wasi-libc treats stdio fds as a separate world from preopens.
      val (inst, _) = instantiate(WasiFixtures.wasi_prestat,
                                  preopens = Seq(Preopen.named("/sandbox")))
      for fd <- Seq(0, 1, 2) do
        inst.invoke("call_prestat_get", Seq(I32(fd), I32(0))) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.EBADF, s"fd=$fd errno=$errno (want EBADF)")
          case other => check(false, s"call_prestat_get(fd=$fd): $other")
    }

    test("fd_prestat_get: name_len is UTF-8 byte length, not char count") {
      // "/café" — 5 chars, but 6 bytes in UTF-8 (é = 0xC3 0xA9). The
      // wasi ABI counts bytes; wasi-libc will allocate a 6-byte buffer.
      val (inst, _) = instantiate(WasiFixtures.wasi_prestat,
                                  preopens = Seq(Preopen.named("/café")))
      inst.invoke("call_prestat_get", Seq(I32(3), I32(0))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno")
        case other => check(false, s"call_prestat_get: $other")
      inst.invoke("load_i32", Seq(I32(4))) match
        case Right(Seq(I32(n))) => check(n == 6, s"name_len=$n (want 6 bytes)")
        case other              => check(false, s"load_i32: $other")
    }

    test("fd_prestat_get: EFAULT when buf+8 extends past memory end") {
      val (inst, _) = instantiate(WasiFixtures.wasi_prestat,
                                  preopens = Seq(Preopen.named("/x")))
      // 1 page = 65536 bytes; buf=65530 leaves 6 bytes — short of 8.
      inst.invoke("call_prestat_get", Seq(I32(3), I32(65530))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT)")
        case other => check(false, s"call_prestat_get: $other")
    }

    // ----- fd_prestat_dir_name --------------------------------------------

    test("fd_prestat_dir_name: writes the UTF-8 name bytes at the given buf") {
      val (inst, _) = instantiate(WasiFixtures.wasi_prestat,
                                  preopens = Seq(Preopen.named("/sandbox")))
      inst.invoke("call_prestat_dir_name", Seq(I32(3), I32(32), I32(8))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_prestat_dir_name: $other")
      val want = "/sandbox".getBytes("UTF-8")
      var i = 0
      while i < want.length do
        inst.invoke("load_byte", Seq(I32(32 + i))) match
          case Right(Seq(I32(b))) =>
            val w = want(i) & 0xff
            check(b == w, s"byte[$i] got=$b want=$w")
          case other => check(false, s"load_byte($i): $other")
        i += 1
    }

    test("fd_prestat_dir_name: EBADF when fd outside preopen range") {
      val (inst, _) = instantiate(WasiFixtures.wasi_prestat,
                                  preopens = Seq(Preopen.named("/a")))
      // fd 4 is past the end; fd 2 (stderr) is not a preopen.
      for fd <- Seq(2, 4) do
        inst.invoke("call_prestat_dir_name",
                    Seq(I32(fd), I32(0), I32(8))) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.EBADF, s"fd=$fd errno=$errno (want EBADF)")
          case other => check(false, s"call_prestat_dir_name(fd=$fd): $other")
    }

    test("fd_prestat_dir_name: ENAMETOOLONG when buf shorter than name") {
      val (inst, _) = instantiate(WasiFixtures.wasi_prestat,
                                  preopens = Seq(Preopen.named("/sandbox")))
      inst.invoke("call_prestat_dir_name", Seq(I32(3), I32(64), I32(4))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ENAMETOOLONG,
                s"errno=$errno (want ENAMETOOLONG=${Wasi.ENAMETOOLONG})")
        case other => check(false, s"call_prestat_dir_name: $other")
      // A failed call must not have stamped a partial name — the first
      // byte at buf is still zero (fresh memory).
      inst.invoke("load_byte", Seq(I32(64))) match
        case Right(Seq(I32(b))) =>
          check(b == 0, s"buf[0]=$b (want 0 — failed call must not write)")
        case other => check(false, s"load_byte: $other")
    }

    test("fd_prestat_dir_name: EFAULT when buf+path_len extends past memory") {
      val (inst, _) = instantiate(WasiFixtures.wasi_prestat,
                                  preopens = Seq(Preopen.named("/sandbox")))
      // 1 page = 65536 bytes; buf=65535, len=8 → end 65543 > 65536.
      inst.invoke("call_prestat_dir_name",
                  Seq(I32(3), I32(65535), I32(8))) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT)")
        case other => check(false, s"call_prestat_dir_name: $other")
    }

end WasiFsTests
