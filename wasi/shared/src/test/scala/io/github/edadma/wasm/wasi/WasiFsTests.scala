package io.github.edadma.wasm.wasi

import io.github.edadma.wasm.{I32, I64, ModuleInstance}

import WasiTestSupport.{check, instantiate, test}

/** Filesystem-syscall tests — covers Phases 7.E.1 + 7.E.2 + 7.E.3 +
  * 7.E.4 + 7.F:
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
  *     impl backed by a `Map[String, Array[Byte]]`.
  *
  *   - **7.E.3** added the read/seek/stat trio: `fd_read` walks iovecs
  *     into linear memory like `fd_write` in reverse; `fd_seek` does the
  *     SET/CUR/END whence math at the syscall layer and stamps the new
  *     position; `fd_filestat_get` writes the 64-byte filestat struct
  *     (filetype dispatched by fd class — stdio is CHARACTER_DEVICE,
  *     preopens are DIRECTORY, opened files are REGULAR_FILE). The
  *     [[Wasi.FsFile]] trait grew `read` / `size` / `tell` / `seek`;
  *     [[WasiContext.Preopen.inMemory]]'s `InMemoryFile` carries a
  *     cursor that read advances.
  *
  *   - **7.E.4** added `fd_fdstat_get`, the one gap surfaced by the
  *     real rustc-built file-reader binary (see
  *     [[WasiRealRustTests]]). Rust's `std::fs::File::open` queries it
  *     immediately after `path_open` to learn whether the new fd
  *     supports Seek. Writes the 24-byte `__wasi_fdstat_t` (u8
  *     filetype, u16 flags, two u64 rights words); filetype dispatch
  *     matches `fd_filestat_get`, flags are zero (no APPEND/NONBLOCK),
  *     rights are full-mask at this slice.
  *
  *   - **7.F** extended the write surface: `fd_write` now routes to the
  *     FdTable for opened-file fds (stdio + preopen-dir fds keep their
  *     7.A/7.E.2 EBADF semantics), `path_open` honours `OFLAGS_CREAT`
  *     (creates a missing file with size 0) and `OFLAGS_TRUNC` (zeroes
  *     an existing file's contents on open), and the [[Wasi.FsFile]]
  *     trait grew a `write(src, offset, length): Int` method.
  *     `WasiContext.Preopen.inMemory` is now genuinely read/write:
  *     its concrete return type
  *     [[WasiContext.Preopen.InMemoryPreopen]] exposes `bytesOf(path)`
  *     and `paths` so tests can inspect post-state.
  *
  * Three fixtures drive the surface: `wasi_prestat.wat` (7.E.1,
  * peek-only), `wasi_path_open.wat` (7.E.2, adds `call_path_open` /
  * `call_fd_close` / `store_byte`), and `wasi_fd_io.wat` (7.E.3 + 7.E.4,
  * adds `call_fd_read` / `call_fd_seek` / `call_fd_filestat_get` /
  * `call_fd_fdstat_get` plus `store_i32` for planting iovec entries).
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

    // ===== fd_read + fd_seek + fd_filestat_get (7.E.3) ====================
    //
    // Pattern: open a file in InMemoryFs (single preopen at fd 3 → opened
    // file lands at fd 4), plant an iovec table or seek args in linear
    // memory via the fixture's `store_i32` export, drive the syscall via
    // its `call_*` wrapper, and assert the result (errno + side-effects).
    //
    // Address layout (chosen by the tests, not the fixture):
    //   path bytes        @ 0
    //   opened_fd_out     @ 64
    //   iovec table       @ 256
    //   nread / newoffset @ 320 (newoffset is i64 = 8 bytes)
    //   filestat scratch  @ 512 (64 bytes)
    //   read destination  @ 768 onward

    test("fd_read: single iovec reads the file into linear memory") {
      val files = Map("hello.txt" -> "Hello, WASI!".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "hello.txt")

      // iovec: { buf = 768, len = 12 } at addr 256.
      storeI32(inst, 256, 768)
      storeI32(inst, 260, 12)
      callFdRead(inst, fd = 4, iovs = 256, iovsLen = 1, nreadOut = 320) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_fd_read: $other")
      val nread = peekI32(inst, 320)
      check(nread == 12, s"nread=$nread (want 12)")
      val got = readBytes(inst, 768, 12)
      val want = "Hello, WASI!".getBytes("UTF-8")
      check(got.sameElements(want),
            s"bytes=${new String(got, "UTF-8")} (want 'Hello, WASI!')")
    }

    test("fd_read: multi-iovec splits the read across two buffers") {
      // Two 4-byte buffers — first should get "Hell", second "o, W".
      val files = Map("h" -> "Hello, WASI!".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")

      storeI32(inst, 256, 768)   // iovec[0].buf
      storeI32(inst, 260, 4)     // iovec[0].len
      storeI32(inst, 264, 800)   // iovec[1].buf
      storeI32(inst, 268, 4)     // iovec[1].len
      callFdRead(inst, fd = 4, iovs = 256, iovsLen = 2, nreadOut = 320) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_fd_read: $other")
      check(peekI32(inst, 320) == 8, s"nread=${peekI32(inst, 320)} (want 8)")
      val a = new String(readBytes(inst, 768, 4), "UTF-8")
      val b = new String(readBytes(inst, 800, 4), "UTF-8")
      check(a == "Hell", s"iov[0]='$a' (want 'Hell')")
      check(b == "o, W", s"iov[1]='$b' (want 'o, W')")
    }

    test("fd_read: short read at EOF reports the partial count and stops") {
      // File holds 3 bytes; ask for 4 in the first iovec, 4 in the second.
      // First iovec returns 3 (EOF) — wasi short-read semantics say stop
      // walking even if a second iovec was queued.
      val files = Map("h" -> "abc".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")

      storeI32(inst, 256, 768)
      storeI32(inst, 260, 4)
      storeI32(inst, 264, 800)
      storeI32(inst, 268, 4)
      callFdRead(inst, fd = 4, iovs = 256, iovsLen = 2, nreadOut = 320) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_fd_read: $other")
      check(peekI32(inst, 320) == 3, s"nread=${peekI32(inst, 320)} (want 3)")
      check(readBytes(inst, 768, 3).sameElements("abc".getBytes("UTF-8")),
            "first iovec must hold 'abc'")
      // The second iovec must not have been touched.
      check(readBytes(inst, 800, 4).forall(_ == 0),
            "second iovec must remain zero — short-read stopped the walk")
    }

    test("fd_read: subsequent call returns 0 (EOF) and writes no bytes") {
      val files = Map("h" -> "ab".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")

      storeI32(inst, 256, 768)
      storeI32(inst, 260, 16)
      callFdRead(inst, 4, 256, 1, 320)
      check(peekI32(inst, 320) == 2, "first read drains 2 bytes")

      // Re-issue. nread must be 0; the dst buffer's first byte must
      // still hold 'a' (no clobbering).
      callFdRead(inst, 4, 256, 1, 320) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.ESUCCESS, s"errno=$errno (want 0)")
        case other => check(false, s"call_fd_read: $other")
      check(peekI32(inst, 320) == 0, s"second nread=${peekI32(inst, 320)} (want 0)")
      check(loadByte(inst, 768) == 'a'.toInt,
            "dst[0] must remain 'a' — no write past EOF")
    }

    test("fd_read: EBADF on stdio/preopen/never-opened fds") {
      val files = Map("h" -> "x".getBytes("UTF-8"))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(Preopen.inMemory("/s", files)))
      storeI32(inst, 256, 768)
      storeI32(inst, 260, 4)
      // fd 0/1/2 — stdio. fd 3 — preopen (directory, not readable). fd 99
      // — never opened.
      for badFd <- Seq(0, 1, 2, 3, 99) do
        callFdRead(inst, fd = badFd, iovs = 256, iovsLen = 1, nreadOut = 320) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.EBADF, s"fd=$badFd errno=$errno (want EBADF)")
          case other => check(false, s"call_fd_read(fd=$badFd): $other")
    }

    test("fd_read: EFAULT when iovec buffer falls outside live memory") {
      val files = Map("h" -> "abcdef".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")
      // buf=65530 + len=10 → end 65540 > 65536.
      storeI32(inst, 256, 65530)
      storeI32(inst, 260, 10)
      callFdRead(inst, 4, 256, 1, 320) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT)")
        case other => check(false, s"call_fd_read: $other")
    }

    test("fd_read: EFAULT when nread_out has no room for 4 bytes") {
      val files = Map("h" -> "abc".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")
      storeI32(inst, 256, 768)
      storeI32(inst, 260, 4)
      callFdRead(inst, fd = 4, iovs = 256, iovsLen = 1, nreadOut = 65535) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EFAULT, s"errno=$errno (want EFAULT)")
        case other => check(false, s"call_fd_read: $other")
    }

    test("fd_seek: SET / CUR / END all return correct new offset") {
      val files = Map("h" -> "0123456789".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")

      // SET 3 → cursor 3
      callFdSeek(inst, fd = 4, offset = 3L, whence = 0, newOffsetOut = 320) match
        case Right(Seq(I32(e))) => check(e == 0, s"SET errno=$e")
        case other              => check(false, s"call_fd_seek: $other")
      check(peekI64(inst, 320) == 3L, s"SET newoffset=${peekI64(inst, 320)}")

      // CUR +2 → cursor 5
      callFdSeek(inst, fd = 4, offset = 2L, whence = 1, newOffsetOut = 320) match
        case Right(Seq(I32(e))) => check(e == 0, s"CUR errno=$e")
        case other              => check(false, s"call_fd_seek: $other")
      check(peekI64(inst, 320) == 5L, s"CUR newoffset=${peekI64(inst, 320)}")

      // END -1 → cursor 9
      callFdSeek(inst, fd = 4, offset = -1L, whence = 2, newOffsetOut = 320) match
        case Right(Seq(I32(e))) => check(e == 0, s"END errno=$e")
        case other              => check(false, s"call_fd_seek: $other")
      check(peekI64(inst, 320) == 9L, s"END newoffset=${peekI64(inst, 320)}")
    }

    test("fd_seek: read after seek returns bytes from the new cursor") {
      val files = Map("h" -> "0123456789".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")

      callFdSeek(inst, 4, 4L, 0, 320)
      storeI32(inst, 256, 768)
      storeI32(inst, 260, 3)
      callFdRead(inst, 4, 256, 1, 320)
      check(peekI32(inst, 320) == 3, "read 3 bytes from offset 4")
      val got = new String(readBytes(inst, 768, 3), "UTF-8")
      check(got == "456", s"bytes='$got' (want '456')")
    }

    test("fd_seek: past-end is accepted; subsequent read returns 0") {
      val files = Map("h" -> "abc".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")

      callFdSeek(inst, 4, 100L, 0, 320) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e (past-end seek is ESUCCESS)")
        case other => check(false, s"call_fd_seek: $other")
      check(peekI64(inst, 320) == 100L, s"newoffset=${peekI64(inst, 320)} (want 100)")

      storeI32(inst, 256, 768)
      storeI32(inst, 260, 4)
      callFdRead(inst, 4, 256, 1, 320)
      check(peekI32(inst, 320) == 0, "read past EOF returns 0")
    }

    test("fd_seek: EINVAL on bad whence") {
      val files = Map("h" -> "abc".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")
      for badWhence <- Seq(3, 99, -1) do
        callFdSeek(inst, 4, 0L, badWhence, 320) match
          case Right(Seq(I32(e))) =>
            check(e == Wasi.EINVAL,
                  s"whence=$badWhence errno=$e (want EINVAL)")
          case other => check(false, s"call_fd_seek($badWhence): $other")
    }

    test("fd_seek: EINVAL on a resulting negative cursor (SET -1)") {
      val files = Map("h" -> "abc".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")
      callFdSeek(inst, 4, -1L, 0, 320) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EINVAL, s"errno=$e (want EINVAL)")
        case other => check(false, s"call_fd_seek: $other")
    }

    test("fd_seek: CUR offset that would underflow returns EINVAL") {
      val files = Map("h" -> "abc".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")
      // Cursor starts at 0; CUR -5 would land at -5.
      callFdSeek(inst, 4, -5L, 1, 320) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EINVAL, s"errno=$e (want EINVAL)")
        case other => check(false, s"call_fd_seek: $other")
    }

    test("fd_seek: EBADF on stdio/preopen/never-opened fds") {
      val files = Map("h" -> "x".getBytes("UTF-8"))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(Preopen.inMemory("/s", files)))
      for badFd <- Seq(0, 2, 3, 99) do
        callFdSeek(inst, fd = badFd, offset = 0L,
                   whence = 0, newOffsetOut = 320) match
          case Right(Seq(I32(e))) =>
            check(e == Wasi.EBADF, s"fd=$badFd errno=$e (want EBADF)")
          case other => check(false, s"call_fd_seek(fd=$badFd): $other")
    }

    test("fd_seek: EFAULT when newoffset_out has no room for 8 bytes") {
      val files = Map("h" -> "abc".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "h")
      callFdSeek(inst, 4, 0L, 0, 65530) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EFAULT, s"errno=$e (want EFAULT)")
        case other => check(false, s"call_fd_seek: $other")
    }

    test("fd_filestat_get: opened file reports REGULAR_FILE + size") {
      val files = Map("hello.txt" -> "Hello, WASI!".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "hello.txt")
      callFdFilestatGet(inst, fd = 4, buf = 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e (want 0)")
        case other => check(false, s"call_fd_filestat_get: $other")
      // filetype at offset 16 — REGULAR_FILE = 4.
      check(loadByte(inst, 512 + 16) == 4,
            s"filetype=${loadByte(inst, 512 + 16)} (want REGULAR_FILE=4)")
      // nlink at offset 24 — always 1 here.
      check(peekI64(inst, 512 + 24) == 1L,
            s"nlink=${peekI64(inst, 512 + 24)} (want 1)")
      // size at offset 32 — 12 bytes of "Hello, WASI!".
      check(peekI64(inst, 512 + 32) == 12L,
            s"size=${peekI64(inst, 512 + 32)} (want 12)")
      // Padding bytes 17..23 (between filetype u8 and nlink u64) must
      // be zero — wasi-libc reads the full struct, so a non-zero pad
      // would leak whatever the program last stored at that address.
      for off <- 17 to 23 do
        check(loadByte(inst, 512 + off) == 0,
              s"pad[$off]=${loadByte(inst, 512 + off)} (want 0)")
    }

    test("fd_filestat_get: preopen fd reports DIRECTORY + size 0") {
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(Preopen.named("/s")))
      callFdFilestatGet(inst, fd = 3, buf = 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e (want 0)")
        case other => check(false, s"call_fd_filestat_get: $other")
      check(loadByte(inst, 512 + 16) == 3,
            s"filetype=${loadByte(inst, 512 + 16)} (want DIRECTORY=3)")
      check(peekI64(inst, 512 + 32) == 0L,
            s"size=${peekI64(inst, 512 + 32)} (want 0 for a directory)")
    }

    test("fd_filestat_get: stdio fds report CHARACTER_DEVICE") {
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io, preopens = Seq.empty)
      for fd <- Seq(0, 1, 2) do
        callFdFilestatGet(inst, fd, 512) match
          case Right(Seq(I32(e))) =>
            check(e == Wasi.ESUCCESS, s"fd=$fd errno=$e")
          case other => check(false, s"call_fd_filestat_get(fd=$fd): $other")
        check(loadByte(inst, 512 + 16) == 2,
              s"fd=$fd filetype=${loadByte(inst, 512 + 16)} " +
              s"(want CHARACTER_DEVICE=2)")
    }

    test("fd_filestat_get: EBADF on fd outside every dispatch") {
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(Preopen.named("/s")))
      // fd 4 is past the preopen and not in the fd table.
      callFdFilestatGet(inst, 4, 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"errno=$e (want EBADF)")
        case other => check(false, s"call_fd_filestat_get: $other")
      // Negative fd too.
      callFdFilestatGet(inst, -1, 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"errno=$e (want EBADF)")
        case other => check(false, s"call_fd_filestat_get(-1): $other")
    }

    test("fd_filestat_get: EFAULT when buf+64 extends past memory") {
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(Preopen.named("/s")))
      // 1 page = 65536; buf=65500 + 64 = 65564 > 65536.
      callFdFilestatGet(inst, 3, 65500) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EFAULT, s"errno=$e (want EFAULT)")
        case other => check(false, s"call_fd_filestat_get: $other")
    }

    // ----- fd_fdstat_get (Phase 7.E.4) ----------------------------------
    //
    // Surfaced as the only gap by the real rustc-built file-reader binary
    // (wasi_real_rust_fileread): Rust's `std::fs::File::open` queries
    // `fd_fdstat_get` immediately after `path_open` to learn whether the
    // fd supports Seek, what its current flags are, and what rights it
    // carries. The 24-byte `__wasi_fdstat_t` layout differs from the
    // 64-byte filestat: u8 filetype @ 0, u16 flags @ 2, two u64 rights
    // words @ 8 and 16.

    test("fd_fdstat_get: opened file reports REGULAR_FILE + per-filetype rights") {
      val files = Map("hello.txt" -> "Hello, WASI!".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "hello.txt")
      callFdFdstatGet(inst, fd = 4, buf = 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e (want 0)")
        case other => check(false, s"call_fd_fdstat_get: $other")
      // filetype at offset 0 — REGULAR_FILE = 4.
      check(loadByte(inst, 512 + 0) == 4,
            s"filetype=${loadByte(inst, 512 + 0)} (want REGULAR_FILE=4)")
      // pad byte 1 stays 0.
      check(loadByte(inst, 512 + 1) == 0,
            s"pad[1]=${loadByte(inst, 512 + 1)} (want 0)")
      // fs_flags u16 @ 2..3 — no APPEND / NONBLOCK / SYNC at this slice.
      check(loadByte(inst, 512 + 2) == 0,
            s"fs_flags[lo]=${loadByte(inst, 512 + 2)} (want 0)")
      check(loadByte(inst, 512 + 3) == 0,
            s"fs_flags[hi]=${loadByte(inst, 512 + 3)} (want 0)")
      // Padding 4..7 stays 0 (struct aligned for the u64 that follows).
      for off <- 4 to 7 do
        check(loadByte(inst, 512 + off) == 0,
              s"pad[$off]=${loadByte(inst, 512 + off)} (want 0)")
      // fs_rights_base @ 8 — RIGHTS_REGULAR_FILE (read/write/seek + filestat
      // + advise/allocate/sync). Tightened from the pre-hardening "-1L full
      // mask" so userspace gating against absent bits behaves correctly.
      check(peekI64(inst, 512 + 8) == Wasi.RIGHTS_REGULAR_FILE,
            s"fs_rights_base=${peekI64(inst, 512 + 8)} " +
            s"(want ${Wasi.RIGHTS_REGULAR_FILE})")
      // fs_rights_inheriting @ 16 — regular files have no children, so 0.
      check(peekI64(inst, 512 + 16) == 0L,
            s"fs_rights_inheriting=${peekI64(inst, 512 + 16)} (want 0)")
    }

    test("fd_fdstat_get: preopen fd reports DIRECTORY + DIRECTORY_BASE/INHERITING rights") {
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(Preopen.named("/s")))
      callFdFdstatGet(inst, fd = 3, buf = 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e (want 0)")
        case other => check(false, s"call_fd_fdstat_get: $other")
      check(loadByte(inst, 512 + 0) == 3,
            s"filetype=${loadByte(inst, 512 + 0)} (want DIRECTORY=3)")
      // Directory fds advertise path_* + fd_readdir + fd_filestat_get in
      // base, plus union of regular-file rights in inheriting (since a
      // path_open through the dir may yield either a file or a subdir).
      check(peekI64(inst, 512 + 8)  == Wasi.RIGHTS_DIRECTORY_BASE,
            s"rights_base=${peekI64(inst, 512 + 8)} " +
            s"(want ${Wasi.RIGHTS_DIRECTORY_BASE})")
      check(peekI64(inst, 512 + 16) == Wasi.RIGHTS_DIRECTORY_INHERITING,
            s"rights_inheriting=${peekI64(inst, 512 + 16)} " +
            s"(want ${Wasi.RIGHTS_DIRECTORY_INHERITING})")
      // The DIRECTORY mask must NOT advertise FD_READ / FD_WRITE / FD_SEEK
      // on the directory itself — userspace uses these absences to refuse
      // dispatching `fd_read` against a directory fd.
      val base = peekI64(inst, 512 + 8)
      check((base & Wasi.RIGHT_FD_READ)  == 0L, "directory must not carry FD_READ")
      check((base & Wasi.RIGHT_FD_WRITE) == 0L, "directory must not carry FD_WRITE")
      check((base & Wasi.RIGHT_FD_SEEK)  == 0L, "directory must not carry FD_SEEK")
    }

    test("fd_fdstat_get: stdio fds report CHARACTER_DEVICE + CHARACTER_DEVICE rights") {
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io, preopens = Seq.empty)
      for fd <- Seq(0, 1, 2) do
        callFdFdstatGet(inst, fd, 512) match
          case Right(Seq(I32(e))) =>
            check(e == Wasi.ESUCCESS, s"fd=$fd errno=$e")
          case other => check(false, s"call_fd_fdstat_get(fd=$fd): $other")
        check(loadByte(inst, 512 + 0) == 2,
              s"fd=$fd filetype=${loadByte(inst, 512 + 0)} " +
              s"(want CHARACTER_DEVICE=2)")
        check(peekI64(inst, 512 + 8)  == Wasi.RIGHTS_CHARACTER_DEVICE,
              s"fd=$fd rights_base=${peekI64(inst, 512 + 8)} " +
              s"(want ${Wasi.RIGHTS_CHARACTER_DEVICE})")
        check(peekI64(inst, 512 + 16) == 0L,
              s"fd=$fd rights_inheriting=${peekI64(inst, 512 + 16)} (want 0)")
        // Stdio is NOT seekable — wasi-libc reads the absence of FD_SEEK
        // to skip `lseek` on stdin/stdout/stderr.
        val base = peekI64(inst, 512 + 8)
        check((base & Wasi.RIGHT_FD_SEEK) == 0L,
              s"fd=$fd: CHARACTER_DEVICE must not carry FD_SEEK")
        check((base & Wasi.RIGHT_FD_TELL) == 0L,
              s"fd=$fd: CHARACTER_DEVICE must not carry FD_TELL")
    }

    test("fd_fdstat_get: EBADF on fd outside every dispatch") {
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(Preopen.named("/s")))
      // fd 4 is past the preopen and never opened.
      callFdFdstatGet(inst, 4, 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"errno=$e (want EBADF)")
        case other => check(false, s"call_fd_fdstat_get: $other")
      // Negative fd.
      callFdFdstatGet(inst, -1, 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"errno=$e (want EBADF)")
        case other => check(false, s"call_fd_fdstat_get(-1): $other")
    }

    test("fd_fdstat_get: EFAULT when buf+24 extends past memory") {
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(Preopen.named("/s")))
      // 1 page = 65536; buf=65520 + 24 = 65544 > 65536.
      callFdFdstatGet(inst, 3, 65520) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EFAULT, s"errno=$e (want EFAULT)")
        case other => check(false, s"call_fd_fdstat_get: $other")
    }

    // ===== fd_write to opened files + path_open OFLAGS (7.F) =============
    //
    // Address layout (same convention as the 7.E.3 tests):
    //   path bytes        @ 0
    //   opened_fd_out     @ 64
    //   iovec table       @ 256
    //   nwritten / nread  @ 320
    //   write source data @ 768 onward
    //
    // Each test seeds an InMemoryPreopen (empty for CREAT paths,
    // pre-populated for TRUNC paths), opens a file, plants iovec entries
    // pointing at write-source bytes in linear memory, drives `call_fd_write`,
    // and inspects `preopen.bytesOf(path)` to confirm what landed in the FS.

    test("fd_write: writes through to InMemoryFs at offset 0 (single iovec)") {
      val preopen = Preopen.inMemory("/s", Map("f" -> "      ".getBytes("UTF-8")))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      val fd = openWithFlags(inst, "f", oflags = 0)
      val payload = "WRITE!".getBytes("UTF-8")
      storeBytes(inst, 768, payload)
      storeI32(inst, 256, 768)
      storeI32(inst, 260, payload.length)
      callFdWrite(inst, fd, 256, 1, 320) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e (want 0)")
        case other => check(false, s"call_fd_write: $other")
      check(peekI32(inst, 320) == payload.length,
            s"nwritten=${peekI32(inst, 320)} (want ${payload.length})")
      val bytes = preopen.bytesOf("f").getOrElse(Array.emptyByteArray)
      check(bytes.sameElements(payload),
            s"file=${new String(bytes, "UTF-8")} (want 'WRITE!')")
    }

    test("fd_write: multi-iovec concatenates at the cursor in order") {
      val preopen = Preopen.inMemory("/s", Map("f" -> Array.empty[Byte]))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      val fd = openWithFlags(inst, "f", oflags = 0)
      val a = "abcd".getBytes("UTF-8")
      val b = "efgh".getBytes("UTF-8")
      storeBytes(inst, 768, a)
      storeBytes(inst, 800, b)
      storeI32(inst, 256, 768)
      storeI32(inst, 260, a.length)
      storeI32(inst, 264, 800)
      storeI32(inst, 268, b.length)
      callFdWrite(inst, fd, 256, 2, 320) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e")
        case other => check(false, s"call_fd_write: $other")
      check(peekI32(inst, 320) == 8, s"nwritten=${peekI32(inst, 320)} (want 8)")
      val bytes = preopen.bytesOf("f").getOrElse(Array.emptyByteArray)
      check(new String(bytes, "UTF-8") == "abcdefgh",
            s"file='${new String(bytes, "UTF-8")}' (want 'abcdefgh')")
    }

    test("fd_write: past-end write grows the file and zero-fills the gap") {
      // Seed a 3-byte file, seek to offset 8, write 2 bytes. Result is
      // 10 bytes long with [a, b, c, 0, 0, 0, 0, 0, X, Y].
      val preopen = Preopen.inMemory("/s",
                                     Map("f" -> Array[Byte]('a', 'b', 'c')))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      val fd = openWithFlags(inst, "f", oflags = 0)
      callFdSeek(inst, fd, 8L, 0, 320)
      storeBytes(inst, 768, Array[Byte]('X', 'Y'))
      storeI32(inst, 256, 768)
      storeI32(inst, 260, 2)
      callFdWrite(inst, fd, 256, 1, 320) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e")
        case other => check(false, s"call_fd_write: $other")
      val bytes = preopen.bytesOf("f").getOrElse(Array.emptyByteArray)
      val want  = Array[Byte]('a', 'b', 'c', 0, 0, 0, 0, 0, 'X', 'Y')
      check(bytes.sameElements(want),
            s"file=${bytes.toSeq} (want ${want.toSeq})")
    }

    test("fd_write: write after fd_seek lands at the new cursor") {
      // Seed "abcdef", seek to offset 2, write "XY" → "abXYef".
      val preopen = Preopen.inMemory("/s",
                                     Map("f" -> "abcdef".getBytes("UTF-8")))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      val fd = openWithFlags(inst, "f", oflags = 0)
      callFdSeek(inst, fd, 2L, 0, 320)
      storeBytes(inst, 768, "XY".getBytes("UTF-8"))
      storeI32(inst, 256, 768)
      storeI32(inst, 260, 2)
      callFdWrite(inst, fd, 256, 1, 320)
      val bytes = preopen.bytesOf("f").getOrElse(Array.emptyByteArray)
      check(new String(bytes, "UTF-8") == "abXYef",
            s"file='${new String(bytes, "UTF-8")}' (want 'abXYef')")
    }

    test("fd_write: write-then-seek-to-0-then-read returns the bytes (round-trip)") {
      val preopen = Preopen.inMemory("/s", Map("f" -> Array.empty[Byte]))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      val fd = openWithFlags(inst, "f", oflags = 0)
      val payload = "hello".getBytes("UTF-8")
      storeBytes(inst, 768, payload)
      storeI32(inst, 256, 768)
      storeI32(inst, 260, payload.length)
      callFdWrite(inst, fd, 256, 1, 320)

      // Seek back to start, read into a fresh region.
      callFdSeek(inst, fd, 0L, 0, 320)
      storeI32(inst, 256, 832)
      storeI32(inst, 260, payload.length)
      callFdRead(inst, fd, 256, 1, 320)
      check(peekI32(inst, 320) == payload.length,
            s"nread=${peekI32(inst, 320)}")
      val got = readBytes(inst, 832, payload.length)
      check(got.sameElements(payload),
            s"round-trip got=${new String(got, "UTF-8")} (want 'hello')")
    }

    test("fd_write: EBADF on stdio fd 0, preopen fd 3, never-opened fd") {
      val preopen   = Preopen.inMemory("/s", Map("f" -> "x".getBytes("UTF-8")))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      // Plant a dummy 1-byte iovec — doesn't matter because dispatch
      // bails before reading the source.
      storeI32(inst, 256, 768)
      storeI32(inst, 260, 1)
      for badFd <- Seq(0, 3, 99) do
        callFdWrite(inst, badFd, 256, 1, 320) match
          case Right(Seq(I32(e))) =>
            check(e == Wasi.EBADF, s"fd=$badFd errno=$e (want EBADF)")
          case other => check(false, s"call_fd_write(fd=$badFd): $other")
    }

    test("fd_write: EBADF after fd_close releases the slot") {
      val preopen   = Preopen.inMemory("/s", Map("f" -> Array.empty[Byte]))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      val fd = openWithFlags(inst, "f", oflags = 0)
      inst.invoke("call_fd_close", Seq(I32(fd))) match
        case Right(Seq(I32(e))) => check(e == Wasi.ESUCCESS, s"close errno=$e")
        case other              => check(false, s"call_fd_close: $other")
      storeI32(inst, 256, 768)
      storeI32(inst, 260, 1)
      callFdWrite(inst, fd, 256, 1, 320) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"errno=$e (want EBADF on closed fd)")
        case other => check(false, s"call_fd_write: $other")
    }

    test("fd_write: EFAULT when iovec buffer extends past memory") {
      val preopen   = Preopen.inMemory("/s", Map("f" -> Array.empty[Byte]))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      val fd = openWithFlags(inst, "f", oflags = 0)
      // 1 page = 65536. buf=65530, len=10 → end 65540 > 65536.
      storeI32(inst, 256, 65530)
      storeI32(inst, 260, 10)
      callFdWrite(inst, fd, 256, 1, 320) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EFAULT, s"errno=$e (want EFAULT)")
        case other => check(false, s"call_fd_write: $other")
      // File must remain empty — the failed call did not partial-write.
      val bytes = preopen.bytesOf("f").getOrElse(Array.emptyByteArray)
      check(bytes.isEmpty, s"file size=${bytes.length} (want 0, EFAULT must not write)")
    }

    test("path_open: OFLAGS_CREAT creates a missing file with size 0") {
      val preopen   = Preopen.inMemory("/s")  // empty
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "new")
      callPathOpenFlags(inst, dirfd = 3, pathPtr = 0, pathLen = 3,
                        oflags = 0x0001, openedFdOut = 64) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e (want 0 — OFLAGS_CREAT new file)")
        case other => check(false, s"call_path_open: $other")
      check(preopen.paths == Seq("new"),
            s"paths=${preopen.paths} (want Seq(new))")
      check(preopen.bytesOf("new").exists(_.length == 0),
            "newly CREAT'd file must be size 0")
    }

    test("path_open: OFLAGS_CREAT on existing file preserves contents") {
      val preopen   = Preopen.inMemory("/s",
                                       Map("f" -> "keep".getBytes("UTF-8")))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "f")
      // CREAT (0x01) without TRUNC: open existing without zeroing.
      callPathOpenFlags(inst, dirfd = 3, pathPtr = 0, pathLen = 1,
                        oflags = 0x0001, openedFdOut = 64) match
        case Right(Seq(I32(e))) => check(e == Wasi.ESUCCESS, s"errno=$e")
        case other              => check(false, s"call_path_open: $other")
      val bytes = preopen.bytesOf("f").getOrElse(Array.emptyByteArray)
      check(new String(bytes, "UTF-8") == "keep",
            s"file='${new String(bytes, "UTF-8")}' (want 'keep' — CREAT-only " +
            "must not truncate)")
    }

    test("path_open: OFLAGS_TRUNC zeros an existing file's contents") {
      val preopen   = Preopen.inMemory("/s",
                                       Map("f" -> "junk".getBytes("UTF-8")))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "f")
      // TRUNC = 0x08; no CREAT bit, but file exists so unconditional zero.
      callPathOpenFlags(inst, dirfd = 3, pathPtr = 0, pathLen = 1,
                        oflags = 0x0008, openedFdOut = 64) match
        case Right(Seq(I32(e))) => check(e == Wasi.ESUCCESS, s"errno=$e")
        case other              => check(false, s"call_path_open: $other")
      val bytes = preopen.bytesOf("f").getOrElse(Array.emptyByteArray)
      check(bytes.isEmpty,
            s"file size=${bytes.length} (want 0 — OFLAGS_TRUNC zeroed it)")
    }

    // ----- path_filestat_get (hardening pass) -----------------------------
    //
    // wasi-libc dispatches `stat(path)` / `access(path)` / `lstat(path)`
    // through `path_filestat_get`. Output shape mirrors `fd_filestat_get`
    // (64-byte struct), input is a path resolved against a preopen-dir fd.

    test("path_filestat_get: existing file reports REGULAR_FILE + size") {
      val files   = Map("hello.txt" -> "Hello, WASI!".getBytes("UTF-8"))
      val preopen = Preopen.inMemory("/s", files)
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "hello.txt")
      callPathFilestatGet(inst, fd = 3, lookupflags = 0,
                          pathPtr = 0, pathLen = 9, buf = 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e (want 0)")
        case other => check(false, s"call_path_filestat_get: $other")
      check(loadByte(inst, 512 + 16) == 4,
            s"filetype=${loadByte(inst, 512 + 16)} (want REGULAR_FILE=4)")
      check(peekI64(inst, 512 + 24) == 1L,
            s"nlink=${peekI64(inst, 512 + 24)} (want 1)")
      check(peekI64(inst, 512 + 32) == 12L,
            s"size=${peekI64(inst, 512 + 32)} (want 12)")
    }

    test("path_filestat_get: missing path returns ENOENT") {
      val preopen = Preopen.inMemory("/s")
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "absent.txt")
      callPathFilestatGet(inst, fd = 3, lookupflags = 0,
                          pathPtr = 0, pathLen = 10, buf = 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ENOENT, s"errno=$e (want ENOENT)")
        case other => check(false, s"call_path_filestat_get: $other")
    }

    test("path_filestat_get: named (non-capability) preopen returns ENOTCAPABLE") {
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(Preopen.named("/s")))
      storePath(inst, 0, "x")
      callPathFilestatGet(inst, fd = 3, lookupflags = 0,
                          pathPtr = 0, pathLen = 1, buf = 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ENOTCAPABLE, s"errno=$e (want ENOTCAPABLE)")
        case other => check(false, s"call_path_filestat_get: $other")
    }

    test("path_filestat_get: EBADF on non-preopen fd") {
      val files   = Map("hello.txt" -> "Hello".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "hello.txt")
      // fd 4 is an opened-file fd, not a preopen — must reject.
      storePath(inst, 0, "hello.txt")
      callPathFilestatGet(inst, fd = 4, lookupflags = 0,
                          pathPtr = 0, pathLen = 9, buf = 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"errno=$e (want EBADF)")
        case other => check(false, s"call_path_filestat_get: $other")
      // And stdio fds aren't preopens either.
      callPathFilestatGet(inst, fd = 1, lookupflags = 0,
                          pathPtr = 0, pathLen = 9, buf = 512) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"errno=$e (want EBADF)")
        case other => check(false, s"call_path_filestat_get(fd=1): $other")
    }

    test("path_filestat_get: EFAULT when buf+64 extends past memory") {
      val files   = Map("f" -> Array.emptyByteArray)
      val preopen = Preopen.inMemory("/s", files)
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "f")
      callPathFilestatGet(inst, fd = 3, lookupflags = 0,
                          pathPtr = 0, pathLen = 1, buf = 65500) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EFAULT, s"errno=$e (want EFAULT)")
        case other => check(false, s"call_path_filestat_get: $other")
    }

    test("path_filestat_get: lookupflags ignored (SYMLINK_FOLLOW absent in InMemoryFs)") {
      val files   = Map("f" -> "x".getBytes("UTF-8"))
      val preopen = Preopen.inMemory("/s", files)
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "f")
      // Both lookupflags=0 and lookupflags=1 (SYMLINK_FOLLOW) succeed — the
      // InMemoryFs has no symlinks so the bit is a no-op.
      for lf <- Seq(0, 1) do
        callPathFilestatGet(inst, fd = 3, lookupflags = lf,
                            pathPtr = 0, pathLen = 1, buf = 512) match
          case Right(Seq(I32(e))) =>
            check(e == Wasi.ESUCCESS, s"lf=$lf errno=$e")
          case other => check(false, s"call_path_filestat_get(lf=$lf): $other")
    }

    // ----- fd_sync / fd_datasync (hardening pass) -------------------------
    //
    // InMemoryFs has no buffered-writes layer, so both syscalls reduce to
    // "is this fd valid?" — ESUCCESS for stdio/preopens/opened-files,
    // EBADF for everything else.

    test("fd_sync: ESUCCESS on stdio + preopen + opened file") {
      val files   = Map("f" -> "x".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "f")
      // fd 4 is the just-opened regular file; fds 0/1/2 are stdio; fd 3 is /s.
      for fd <- Seq(0, 1, 2, 3, 4) do
        inst.invoke("call_fd_sync", Seq(I32(fd))) match
          case Right(Seq(I32(e))) =>
            check(e == Wasi.ESUCCESS, s"fd=$fd errno=$e (want 0)")
          case other => check(false, s"call_fd_sync(fd=$fd): $other")
    }

    test("fd_sync: EBADF on negative + never-opened + closed fds") {
      val files   = Map("f" -> "x".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "f")
      // fd 5 is never opened — single preopen + one open = highest fd is 4.
      for fd <- Seq(-1, 5, 99) do
        inst.invoke("call_fd_sync", Seq(I32(fd))) match
          case Right(Seq(I32(e))) =>
            check(e == Wasi.EBADF, s"fd=$fd errno=$e (want EBADF)")
          case other => check(false, s"call_fd_sync(fd=$fd): $other")
      // Close fd 4 and confirm it goes from ESUCCESS to EBADF.
      inst.invoke("call_fd_close", Seq(I32(4))) match
        case Right(Seq(I32(e))) => check(e == Wasi.ESUCCESS, s"fd_close: $e")
        case other              => check(false, s"call_fd_close: $other")
      inst.invoke("call_fd_sync", Seq(I32(4))) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"after-close errno=$e (want EBADF)")
        case other => check(false, s"call_fd_sync(after close): $other")
    }

    test("fd_datasync: matches fd_sync semantics") {
      val files   = Map("f" -> "x".getBytes("UTF-8"))
      val (inst, _) = openSingleFile(files, "f")
      // Same partition as fd_sync — verifies the two syscalls behave
      // identically (no metadata/data distinction in InMemoryFs).
      for fd <- Seq(0, 1, 2, 3, 4) do
        inst.invoke("call_fd_datasync", Seq(I32(fd))) match
          case Right(Seq(I32(e))) =>
            check(e == Wasi.ESUCCESS, s"fd=$fd errno=$e (want 0)")
          case other => check(false, s"call_fd_datasync(fd=$fd): $other")
      inst.invoke("call_fd_datasync", Seq(I32(-1))) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"fd=-1 errno=$e (want EBADF)")
        case other => check(false, s"call_fd_datasync(-1): $other")
    }

    // ----- OFLAGS_EXCL in path_open (hardening pass) ----------------------
    //
    // EXCL gives userspace atomic-create semantics: `CREAT | EXCL` on an
    // existing path fails with EEXIST. Used for things like lockfiles
    // where the existence-check IS the signal. Without EXCL, CREAT on an
    // existing file is a benign no-op (the previous slice's behaviour).

    test("path_open: OFLAGS_CREAT|EXCL on existing file returns EEXIST") {
      val preopen = Preopen.inMemory("/s",
                                     Map("f" -> "x".getBytes("UTF-8")))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "f")
      // CREAT = 0x01, EXCL = 0x04 → combined = 0x05.
      callPathOpenFlags(inst, dirfd = 3, pathPtr = 0, pathLen = 1,
                        oflags = 0x0005, openedFdOut = 64) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EEXIST, s"errno=$e (want EEXIST)")
        case other => check(false, s"call_path_open: $other")
      // The pre-existing file is untouched (no TRUNC) — assert the bytes
      // are still there.
      check(preopen.bytesOf("f").exists(_.sameElements("x".getBytes("UTF-8"))),
            "file contents unchanged after EXCL-rejected open")
    }

    test("path_open: OFLAGS_CREAT|EXCL on missing path succeeds (atomic create)") {
      val preopen = Preopen.inMemory("/s")
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "new.txt")
      callPathOpenFlags(inst, dirfd = 3, pathPtr = 0, pathLen = 7,
                        oflags = 0x0005, openedFdOut = 64) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e (want 0)")
        case other => check(false, s"call_path_open: $other")
      // File was created (size 0).
      check(preopen.bytesOf("new.txt").exists(_.isEmpty),
            "new file created at size 0 via CREAT|EXCL")
    }

    test("path_open: OFLAGS_EXCL without CREAT is a no-op (existing file opens normally)") {
      val preopen = Preopen.inMemory("/s",
                                     Map("f" -> "x".getBytes("UTF-8")))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "f")
      // EXCL only (0x04) — POSIX says EXCL is meaningful only with CREAT;
      // bare EXCL on an existing file just opens it.
      callPathOpenFlags(inst, dirfd = 3, pathPtr = 0, pathLen = 1,
                        oflags = 0x0004, openedFdOut = 64) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e (want 0)")
        case other => check(false, s"call_path_open: $other")
    }

    // ----- path_unlink_file (hardening pass) ------------------------------

    test("path_unlink_file: removes existing file from preopen") {
      val preopen = Preopen.inMemory("/s",
                                     Map("f" -> "x".getBytes("UTF-8")))
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "f")
      callPathUnlinkFile(inst, fd = 3, pathPtr = 0, pathLen = 1) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"errno=$e (want 0)")
        case other => check(false, s"call_path_unlink_file: $other")
      check(preopen.bytesOf("f").isEmpty, "file removed from preopen.bytesOf")
      check(!preopen.paths.contains("f"), "file removed from preopen.paths")
    }

    test("path_unlink_file: missing path returns ENOENT") {
      val preopen = Preopen.inMemory("/s")
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      storePath(inst, 0, "absent")
      callPathUnlinkFile(inst, fd = 3, pathPtr = 0, pathLen = 6) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ENOENT, s"errno=$e (want ENOENT)")
        case other => check(false, s"call_path_unlink_file: $other")
    }

    test("path_unlink_file: ENOTCAPABLE on named preopen") {
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(Preopen.named("/s")))
      storePath(inst, 0, "x")
      callPathUnlinkFile(inst, fd = 3, pathPtr = 0, pathLen = 1) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ENOTCAPABLE, s"errno=$e (want ENOTCAPABLE)")
        case other => check(false, s"call_path_unlink_file: $other")
    }

    test("path_unlink_file: EBADF on non-preopen fd") {
      val (inst, _) = openSingleFile(Map("f" -> Array.emptyByteArray), "f")
      // fd 4 is an opened-file fd, not a preopen-dir.
      storePath(inst, 0, "f")
      callPathUnlinkFile(inst, fd = 4, pathPtr = 0, pathLen = 1) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"errno=$e (want EBADF)")
        case other => check(false, s"call_path_unlink_file: $other")
      callPathUnlinkFile(inst, fd = 1, pathPtr = 0, pathLen = 1) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.EBADF, s"errno=$e (want EBADF)")
        case other => check(false, s"call_path_unlink_file(fd=1): $other")
    }

    test("path_unlink_file: open handle survives unlink (POSIX inode semantics)") {
      // Open a file, unlink it, then read from the still-open handle.
      // The handle keeps its own FileCell reference, so the bytes are
      // still readable through the fd even though `bytesOf` no longer
      // sees them. Matches POSIX `unlink(2)` against an open fd.
      val initial = Map("doomed.txt" -> "stay-readable".getBytes("UTF-8"))
      val preopen = Preopen.inMemory("/s", initial)
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(preopen))
      // Open first.
      storePath(inst, 0, "doomed.txt")
      callPathOpen(inst, dirfd = 3, pathPtr = 0, pathLen = 10,
                   openedFdOut = 64) match
        case Right(Seq(I32(e))) if e == Wasi.ESUCCESS => ()
        case other => check(false, s"open: $other")
      val fd = peekI32(inst, 64)
      // Unlink.
      callPathUnlinkFile(inst, fd = 3, pathPtr = 0, pathLen = 10) match
        case Right(Seq(I32(e))) =>
          check(e == Wasi.ESUCCESS, s"unlink errno=$e")
        case other => check(false, s"call_path_unlink_file: $other")
      check(preopen.bytesOf("doomed.txt").isEmpty,
            "preopen.bytesOf no longer sees the unlinked path")
      // Read through the still-open fd — POSIX inode semantics keep
      // the bytes alive.
      storeI32(inst, 256, 768)    // iov.buf
      storeI32(inst, 260, 32)     // iov.len
      callFdRead(inst, fd = fd, iovs = 256, iovsLen = 1, nreadOut = 320) match
        case Right(Seq(I32(e))) => check(e == Wasi.ESUCCESS, s"fd_read: $e")
        case other              => check(false, s"call_fd_read: $other")
      val n = peekI32(inst, 320)
      check(n == 13, s"nread=$n (want 13 — 'stay-readable')")
      val bytes = readBytes(inst, 768, n)
      check(new String(bytes, "UTF-8") == "stay-readable",
            s"bytes=${new String(bytes, "UTF-8")}")
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

  /** 9-arg path_open wrapper for 7.F tests that need non-zero `oflags`
    * (`OFLAGS_CREAT` = 0x0001, `OFLAGS_TRUNC` = 0x0008) or non-zero
    * `fdflags`. Default `fdflags` is 0. */
  private def callPathOpenFlags(inst: ModuleInstance,
                                dirfd:       Int,
                                pathPtr:     Int,
                                pathLen:     Int,
                                oflags:      Int,
                                fdflags:     Int = 0,
                                openedFdOut: Int = 64) =
    inst.invoke("call_path_open", Seq(
      I32(dirfd),
      I32(0),
      I32(pathPtr),
      I32(pathLen),
      I32(oflags),
      I64(0L),
      I64(0L),
      I32(fdflags),
      I32(openedFdOut),
    ))

  /** Plant `bytes` into linear memory starting at `addr` via the fixture's
    * `store_byte` export. Tests use this to seed the source buffer that
    * `fd_write` reads through iovecs. Same shape as [[storePath]] but
    * accepts a raw `Array[Byte]`. */
  private def storeBytes(inst: ModuleInstance, addr: Int, bytes: Array[Byte]): Unit =
    var i = 0
    while i < bytes.length do
      inst.invoke("store_byte", Seq(I32(addr + i), I32(bytes(i) & 0xff))) match
        case Right(_) => ()
        case other    => throw new AssertionError(s"store_byte($i): $other")
      i += 1

  /** 4-arg `fd_write` wrapper — same arity as the underlying syscall.
    * Mirrors [[callFdRead]]; tests use it to drive writes through the
    * 7.F FdTable routing path. */
  private def callFdWrite(inst:         ModuleInstance,
                          fd:           Int,
                          iovs:         Int,
                          iovsLen:      Int,
                          nwrittenOut:  Int) =
    inst.invoke("call_fd_write",
                Seq(I32(fd), I32(iovs), I32(iovsLen), I32(nwrittenOut)))

  /** Open `pathKey` against the single-preopen fixture with the given
    * `oflags`, plant the path bytes at addr 0, and return the freshly
    * allocated fd. Used by the 7.F regression tests as the common
    * boilerplate for "seed an InMemoryFs file, then drive fd_write". */
  private def openWithFlags(inst:    ModuleInstance,
                            pathKey: String,
                            oflags:  Int): Int =
    storePath(inst, 0, pathKey)
    callPathOpenFlags(inst, dirfd = 3, pathPtr = 0,
                      pathLen = pathKey.getBytes("UTF-8").length,
                      oflags = oflags, openedFdOut = 64) match
      case Right(Seq(I32(e))) if e == Wasi.ESUCCESS => ()
      case other => throw new AssertionError(s"openWithFlags: $other")
    peekI32(inst, 64)

  /** Read back a little-endian i32 at `addr` via the fixture's
    * `load_i32` helper. Throws on a non-i32 return so the test fails
    * with a useful message rather than a `MatchError`. */
  private def peekI32(inst: ModuleInstance, addr: Int): Int =
    inst.invoke("load_i32", Seq(I32(addr))) match
      case Right(Seq(I32(v))) => v
      case other              => throw new AssertionError(s"load_i32($addr): $other")

  /** i64 sibling of [[peekI32]] — reads the 8-byte little-endian word at
    * `addr` via the fixture's `load_i64` helper. Used by `fd_seek`'s
    * newoffset readout and `fd_filestat_get`'s size/nlink fields. */
  private def peekI64(inst: ModuleInstance, addr: Int): Long =
    inst.invoke("load_i64", Seq(I32(addr))) match
      case Right(Seq(I64(v))) => v
      case other              => throw new AssertionError(s"load_i64($addr): $other")

  /** Plant a little-endian i32 at `addr`. Tests use this to write iovec
    * entries (buf-pointer + length pairs) before invoking `call_fd_read`. */
  private def storeI32(inst: ModuleInstance, addr: Int, v: Int): Unit =
    inst.invoke("store_i32", Seq(I32(addr), I32(v))) match
      case Right(_) => ()
      case other    => throw new AssertionError(s"store_i32($addr, $v): $other")

  /** Read back a single unsigned byte. Returns Int because the fixture's
    * `load_byte` is `i32.load8_u` (already zero-extended). */
  private def loadByte(inst: ModuleInstance, addr: Int): Int =
    inst.invoke("load_byte", Seq(I32(addr))) match
      case Right(Seq(I32(b))) => b
      case other              => throw new AssertionError(s"load_byte($addr): $other")

  /** Slurp `len` bytes from linear memory starting at `addr`. Used to
    * verify what `fd_read` planted. */
  private def readBytes(inst: ModuleInstance, addr: Int, len: Int): Array[Byte] =
    val out = new Array[Byte](len)
    var i = 0
    while i < len do
      out(i) = loadByte(inst, addr + i).toByte
      i += 1
    out

  /** 4-arg `fd_read` wrapper — same arity as the underlying syscall. */
  private def callFdRead(inst:     ModuleInstance,
                         fd:       Int,
                         iovs:     Int,
                         iovsLen:  Int,
                         nreadOut: Int) =
    inst.invoke("call_fd_read",
                Seq(I32(fd), I32(iovs), I32(iovsLen), I32(nreadOut)))

  /** 4-arg `fd_seek` wrapper. `offset` is the 64-bit signed delta. */
  private def callFdSeek(inst:         ModuleInstance,
                         fd:           Int,
                         offset:       Long,
                         whence:       Int,
                         newOffsetOut: Int) =
    inst.invoke("call_fd_seek",
                Seq(I32(fd), I64(offset), I32(whence), I32(newOffsetOut)))

  /** 2-arg `fd_fdstat_get` wrapper. `buf` is the 24-byte struct
    * destination (`fs_filetype` at 0, `fs_flags` at 2, rights words at
    * 8 and 16). Mirrors [[callFdFilestatGet]]'s shape. */
  private def callFdFdstatGet(inst: ModuleInstance, fd: Int, buf: Int) =
    inst.invoke("call_fd_fdstat_get", Seq(I32(fd), I32(buf)))

  /** 2-arg `fd_filestat_get` wrapper. `buf` is the 64-byte struct
    * destination. */
  private def callFdFilestatGet(inst: ModuleInstance, fd: Int, buf: Int) =
    inst.invoke("call_fd_filestat_get", Seq(I32(fd), I32(buf)))

  /** 5-arg `path_filestat_get` wrapper. Resolves `pathPtr`/`pathLen`
    * against the preopen at `fd` and writes a 64-byte filestat at `buf`.
    * `lookupflags` bit 0 = SYMLINK_FOLLOW (ignored in the InMemoryFs). */
  private def callPathFilestatGet(inst:        ModuleInstance,
                                  fd:          Int,
                                  lookupflags: Int,
                                  pathPtr:     Int,
                                  pathLen:     Int,
                                  buf:         Int) =
    inst.invoke("call_path_filestat_get",
                Seq(I32(fd), I32(lookupflags), I32(pathPtr),
                    I32(pathLen), I32(buf)))

  /** 3-arg `path_unlink_file` wrapper. Remove the file at `pathPtr`
    * (resolved against the preopen at `fd`). No `lookupflags` in
    * wasi-preview1 (always lstat-style — never follows symlinks). */
  private def callPathUnlinkFile(inst:    ModuleInstance,
                                 fd:      Int,
                                 pathPtr: Int,
                                 pathLen: Int) =
    inst.invoke("call_path_unlink_file",
                Seq(I32(fd), I32(pathPtr), I32(pathLen)))

  /** Open a single-preopen InMemoryFs, store the path bytes at addr 0,
    * open the file via `call_path_open`, and assert the returned fd is
    * 4 (one preopen → first opened-file fd is 3 + 1). Returns the
    * instance plus the captured collecting context so callers can keep
    * working with it. Used by every fd_read / fd_seek / fd_filestat_get
    * happy-path test as the common boilerplate. */
  private def openSingleFile(files:    Map[String, Array[Byte]],
                             pathKey:  String): (ModuleInstance, WasiContext.Collecting) =
    val (inst, ctx) = instantiate(WasiFixtures.wasi_fd_io,
                                  preopens = Seq(Preopen.inMemory("/s", files)))
    storePath(inst, 0, pathKey)
    callPathOpen(inst, dirfd = 3, pathPtr = 0,
                 pathLen = pathKey.getBytes("UTF-8").length,
                 openedFdOut = 64) match
      case Right(Seq(I32(errno))) if errno == 0 => ()
      case other => throw new AssertionError(s"openSingleFile: $other")
    val fd = peekI32(inst, 64)
    if fd != 4 then
      throw new AssertionError(s"openSingleFile: expected fd 4, got $fd")
    (inst, ctx)

end WasiFsTests
