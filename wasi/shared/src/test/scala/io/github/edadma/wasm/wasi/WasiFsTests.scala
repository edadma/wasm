package io.github.edadma.wasm.wasi

import io.github.edadma.wasm.{I32, I64, ModuleInstance}

import WasiTestSupport.{check, instantiate, test}

/** Filesystem-syscall tests — currently covering Phases 7.E.1 + 7.E.2:
  *
  *   - **7.E.1** wired the preopen-walk syscalls (`fd_prestat_get` and
  *     `fd_prestat_dir_name`), the two functions wasi-libc reaches for at
  *     program startup (before any `path_open` call): it walks fds 3, 4,
  *     …, stops when the host returns EBADF, and builds an internal
  *     map of preopen-name → fd that all later relative-path lookups
  *     resolve through.
  *
  *   - **7.E.2** added the file-handle surface — `path_open` plus a
  *     general-fd `fd_close` that extends the 7.C stdio-only version to
  *     consult an [[Wasi.FdTable]] for fds beyond the preopen range. The
  *     [[WasiContext.Preopen]] trait grew an `open(path, oflags, fdflags)`
  *     method; the default impl returns `Left(Wasi.ENOTCAPABLE)`, and
  *     [[WasiContext.Preopen.inMemory]] is the read-only test-harness
  *     impl backed by a `Map[String, Array[Byte]]`. Two new fixtures
  *     drive the surface: `wasi_prestat.wat` (7.E.1, peek-only) and
  *     `wasi_path_open.wat` (7.E.2, adds `call_path_open` /
  *     `call_fd_close` / `store_byte`).
  *
  * Phase 7.E.3 will add `fd_read` / `fd_seek` / `fd_filestat_get` on top
  * of the same [[Wasi.FsFile]] handle (extending its trait with the
  * methods those syscalls need).
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

    // ===== path_open + general-fd fd_close (7.E.2) ========================
    //
    // Path-bytes-in-linear-memory pattern: each test calls `storePath` to
    // poke the UTF-8 bytes of a relative path into memory at a chosen
    // address, then invokes `call_path_open` with that (ptr, len) pair.
    // The opened-fd output address is also caller-chosen — `load_i32`
    // reads back what the shim planted there.

    test("path_open: opens an existing file in InMemoryFs and returns 3+N") {
      // Single preopen at fd 3 → baseFd for opened files is 4.
      val files = Map("hello.txt" -> "Hi".getBytes("UTF-8"))
      val (inst, _) = instantiate(WasiFixtures.wasi_path_open,
                                  preopens = Seq(Preopen.inMemory("/s", files)))
      val pathAddr   = 0
      val outFdAddr  = 64
      storePath(inst, pathAddr, "hello.txt")
      callPathOpen(inst, dirfd = 3, pathPtr = pathAddr,
                   pathLen = 9, openedFdOut = outFdAddr) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_path_open: $other")
      inst.invoke("load_i32", Seq(I32(outFdAddr))) match
        case Right(Seq(I32(fd))) =>
          check(fd == 4, s"opened fd=$fd (want 4 = 3 + preopens.length)")
        case other => check(false, s"load_i32: $other")
    }

    test("path_open: ENOENT when file is not in the InMemoryFs map") {
      val (inst, _) = instantiate(WasiFixtures.wasi_path_open,
                                  preopens = Seq(Preopen.inMemory("/s", Map.empty)))
      storePath(inst, 0, "missing")
      callPathOpen(inst, dirfd = 3, pathPtr = 0,
                   pathLen = 7, openedFdOut = 64) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ENOENT,
                s"errno=$errno (want ENOENT=${Wasi.ENOENT})")
        case other => check(false, s"call_path_open: $other")
    }

    test("path_open: ENOTCAPABLE when preopen has no fs (Preopen.named)") {
      // `Preopen.named` inherits the trait's default `open`, which returns
      // Left(ENOTCAPABLE) — the preopen advertises its name but can't
      // open anything inside it.
      val (inst, _) = instantiate(WasiFixtures.wasi_path_open,
                                  preopens = Seq(Preopen.named("/s")))
      storePath(inst, 0, "anything")
      callPathOpen(inst, dirfd = 3, pathPtr = 0,
                   pathLen = 8, openedFdOut = 64) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ENOTCAPABLE,
                s"errno=$errno (want ENOTCAPABLE=${Wasi.ENOTCAPABLE})")
        case other => check(false, s"call_path_open: $other")
    }

    test("path_open: EBADF when dirfd is not a preopen") {
      val files = Map("h" -> "x".getBytes("UTF-8"))
      val (inst, _) = instantiate(WasiFixtures.wasi_path_open,
                                  preopens = Seq(Preopen.inMemory("/s", files)))
      storePath(inst, 0, "h")
      // fd 5 is past the one preopen; fd 2 (stderr) is not a preopen; fd 0
      // is below the preopen base — all three must produce EBADF.
      for badFd <- Seq(0, 2, 5) do
        callPathOpen(inst, dirfd = badFd, pathPtr = 0,
                     pathLen = 1, openedFdOut = 64) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.EBADF,
                  s"dirfd=$badFd errno=$errno (want EBADF)")
          case other => check(false, s"call_path_open(dirfd=$badFd): $other")
    }

    test("path_open: EFAULT when path_ptr+path_len extends past memory") {
      val files = Map("h" -> "x".getBytes("UTF-8"))
      val (inst, _) = instantiate(WasiFixtures.wasi_path_open,
                                  preopens = Seq(Preopen.inMemory("/s", files)))
      // 1 page = 65536 bytes; ptr=65530 + len=10 → end 65540 > 65536.
      callPathOpen(inst, dirfd = 3, pathPtr = 65530,
                   pathLen = 10, openedFdOut = 64) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT)")
        case other => check(false, s"call_path_open: $other")
    }

    test("path_open: EFAULT when opened_fd_out has no room for a 4-byte i32") {
      val files = Map("h" -> "x".getBytes("UTF-8"))
      val (inst, _) = instantiate(WasiFixtures.wasi_path_open,
                                  preopens = Seq(Preopen.inMemory("/s", files)))
      storePath(inst, 0, "h")
      // Buffer end at 65536; openedFdOut=65535 leaves 1 byte — short of 4.
      callPathOpen(inst, dirfd = 3, pathPtr = 0,
                   pathLen = 1, openedFdOut = 65535) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT)")
        case other => check(false, s"call_path_open: $other")
    }

    test("path_open: smallest-free fd reuse after close") {
      // POSIX semantics: open returns the smallest unused fd. After
      // opening two files (fds 4 and 5), closing fd 4 should make a
      // subsequent open reuse fd 4 rather than monotonically increment.
      val files = Map("a" -> "1".getBytes("UTF-8"),
                      "b" -> "2".getBytes("UTF-8"))
      val (inst, _) = instantiate(WasiFixtures.wasi_path_open,
                                  preopens = Seq(Preopen.inMemory("/s", files)))
      val out = 64
      storePath(inst, 0, "a")
      callPathOpen(inst, 3, 0, 1, out)
      val fdA = peekI32(inst, out)
      check(fdA == 4, s"first open fd=$fdA (want 4)")

      storePath(inst, 0, "b")
      callPathOpen(inst, 3, 0, 1, out)
      val fdB = peekI32(inst, out)
      check(fdB == 5, s"second open fd=$fdB (want 5)")

      inst.invoke("call_fd_close", Seq(I32(fdA))) match
        case Right(Seq(I32(e))) => check(e == 0, s"close(4) errno=$e")
        case other              => check(false, s"call_fd_close: $other")

      storePath(inst, 0, "a")
      callPathOpen(inst, 3, 0, 1, out)
      val fdAagain = peekI32(inst, out)
      check(fdAagain == 4,
            s"reopen fd=$fdAagain (want 4 — smallest free, not 6)")
    }

    test("fd_close: opened-file fd closes once, EBADF on second close") {
      val files = Map("a" -> "1".getBytes("UTF-8"))
      val (inst, _) = instantiate(WasiFixtures.wasi_path_open,
                                  preopens = Seq(Preopen.inMemory("/s", files)))
      storePath(inst, 0, "a")
      callPathOpen(inst, 3, 0, 1, 64)
      val fd = peekI32(inst, 64)
      check(fd == 4, s"opened fd=$fd")
      inst.invoke("call_fd_close", Seq(I32(fd))) match
        case Right(Seq(I32(e))) => check(e == Wasi.ESUCCESS, s"first close errno=$e")
        case other              => check(false, s"call_fd_close: $other")
      inst.invoke("call_fd_close", Seq(I32(fd))) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"second close errno=$e (want EBADF)")
        case other => check(false, s"call_fd_close: $other")
    }

    test("fd_close: preopen fd is a benign no-op (ESUCCESS)") {
      // POSIX: closing a valid fd succeeds. Preopens are static for the
      // lifetime of the WasiContext, so the shim returns ESUCCESS without
      // actually invalidating anything.
      val (inst, _) = instantiate(WasiFixtures.wasi_path_open,
                                  preopens = Seq(Preopen.named("/x")))
      inst.invoke("call_fd_close", Seq(I32(3))) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"close(preopen fd 3) errno=$e (want 0)")
        case other => check(false, s"call_fd_close: $other")
    }

    test("fd_close: EBADF on a high fd that was never opened") {
      val (inst, _) = instantiate(WasiFixtures.wasi_path_open,
                                  preopens = Seq.empty)
      inst.invoke("call_fd_close", Seq(I32(100))) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"close(100) errno=$e (want EBADF)")
        case other => check(false, s"call_fd_close: $other")
    }

  // ----- helpers ----------------------------------------------------------

  /** Poke the UTF-8 bytes of `path` into linear memory starting at `addr`
    * via the fixture's `store_byte` export. Tests use this to plant a
    * path before invoking `call_path_open`. */
  private def storePath(inst: ModuleInstance, addr: Int, path: String): Unit =
    val bytes = path.getBytes("UTF-8")
    var i = 0
    while i < bytes.length do
      inst.invoke("store_byte", Seq(I32(addr + i), I32(bytes(i) & 0xff))) match
        case Right(_) => ()
        case other    => throw new AssertionError(s"store_byte($i): $other")
      i += 1

  /** Wrap the 9-arg path_open call with the defaults most tests want
    * (zero `dirflags` / `oflags` / `fdflags` / rights). */
  private def callPathOpen(inst: ModuleInstance,
                           dirfd:       Int,
                           pathPtr:     Int,
                           pathLen:     Int,
                           openedFdOut: Int) =
    inst.invoke("call_path_open", Seq(
      I32(dirfd),       // dirfd
      I32(0),           // dirflags
      I32(pathPtr),     // path_ptr
      I32(pathLen),     // path_len
      I32(0),           // oflags
      I64(0L),          // fs_rights_base
      I64(0L),          // fs_rights_inheriting
      I32(0),           // fdflags
      I32(openedFdOut), // opened_fd_out
    ))

  /** Read back a little-endian i32 at `addr` via the fixture's
    * `load_i32` helper. Throws on a non-i32 return so the test fails
    * with a useful message rather than a `MatchError`. */
  private def peekI32(inst: ModuleInstance, addr: Int): Int =
    inst.invoke("load_i32", Seq(I32(addr))) match
      case Right(Seq(I32(v))) => v
      case other              => throw new AssertionError(s"load_i32($addr): $other")

end WasiFsTests
