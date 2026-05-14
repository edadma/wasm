package io.github.edadma.wasm.wasi

import WasiTestSupport.{check, test}

/** Tests for the host-backed [[HostBackedPreopen]] — the platform
  * factory `HostPreopen.fromDir(hostPath, virtualName)` returns a
  * Preopen backed by a real on-disk directory through the platform's
  * native filesystem API (`java.nio.file` on JVM/Native, Node `fs`
  * on JS).
  *
  * Tests exercise the [[WasiContext.Preopen]] trait surface directly
  * rather than through the wasi syscall layer: that surface is the
  * contract every preopen impl honours, and the syscall dispatch is
  * already proven against [[WasiFsTests]]' InMemoryPreopen battery.
  * Hitting the trait surface directly keeps each test small and lets
  * a regression in (say) `unlinkPath` failure-mode mapping fail with
  * a precise stack trace rather than a 50-line wasi-shape decode.
  *
  * Per-platform [[TempDir]] helpers (one impl per platform folder)
  * spin up the on-disk scratch dir and clean it up; the test bodies
  * themselves only see preopen-relative paths plus the host root as
  * an opaque `String`. */
object WasiHostFsTests:

  def run(): Unit =
    println()
    println("-- WasiHostFsTests --")

    // ----- construction --------------------------------------------------

    test("fromDir: returns a preopen with the given virtual name") {
      val root = TempDir.create("wasm-host-")
      try
        val p = HostPreopen.fromDir(root, "/sandbox")
        check(p.name == "/sandbox", s"name=${p.name} (want /sandbox)")
      finally TempDir.remove(root)
    }

    test("fromDir: missing host path throws IllegalArgumentException") {
      val ok =
        try
          HostPreopen.fromDir("/this/path/does/not/exist/wasm-test", "/x")
          false
        catch case _: IllegalArgumentException => true
      check(ok, "expected IllegalArgumentException for missing hostPath")
    }

    test("fromDir: hostPath that is a regular file throws") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.writeFile(root, "afile", Array[Byte](1, 2, 3))
        val ok =
          try
            HostPreopen.fromDir(s"$root/afile", "/x")
            false
          catch case _: IllegalArgumentException => true
        check(ok, "expected IllegalArgumentException for file hostPath")
      finally TempDir.remove(root)
    }

    // ----- statPath / filetypeOf -----------------------------------------

    test("statPath: existing file returns Right(size)") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.writeFile(root, "hello.txt", "Hello!".getBytes("UTF-8"))
        val p = HostPreopen.fromDir(root, "/s")
        p.statPath("hello.txt") match
          case Right(sz) => check(sz == 6L, s"size=$sz (want 6)")
          case Left(e)   => check(false, s"unexpected Left($e)")
      finally TempDir.remove(root)
    }

    test("statPath: existing directory returns Right(0)") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.mkdir(root, "subdir")
        val p = HostPreopen.fromDir(root, "/s")
        p.statPath("subdir") match
          case Right(sz) => check(sz == 0L, s"size=$sz (want 0 for dir)")
          case Left(e)   => check(false, s"unexpected Left($e)")
      finally TempDir.remove(root)
    }

    test("statPath: missing path returns Left(ENOENT)") {
      val root = TempDir.create("wasm-host-")
      try
        val p = HostPreopen.fromDir(root, "/s")
        p.statPath("nope.txt") match
          case Left(e) => check(e == Wasi.ENOENT, s"errno=$e (want ENOENT)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    test("statPath: absolute path returns Left(ENOTCAPABLE)") {
      val root = TempDir.create("wasm-host-")
      try
        val p = HostPreopen.fromDir(root, "/s")
        p.statPath("/etc/passwd") match
          case Left(e) => check(e == Wasi.ENOTCAPABLE, s"errno=$e (want ENOTCAPABLE)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    test("statPath: `..` escape returns Left(ENOTCAPABLE)") {
      val root = TempDir.create("wasm-host-")
      try
        val p = HostPreopen.fromDir(root, "/s")
        p.statPath("../../../etc/passwd") match
          case Left(e) => check(e == Wasi.ENOTCAPABLE, s"errno=$e (want ENOTCAPABLE)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    test("statPath: empty path returns Left(ENOENT)") {
      val root = TempDir.create("wasm-host-")
      try
        val p = HostPreopen.fromDir(root, "/s")
        p.statPath("") match
          case Left(e) => check(e == Wasi.ENOENT, s"errno=$e (want ENOENT)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    test("statPath: `subdir/../file.txt` resolves to file.txt") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.writeFile(root, "file.txt", "ok".getBytes("UTF-8"))
        TempDir.mkdir(root, "subdir")
        val p = HostPreopen.fromDir(root, "/s")
        p.statPath("subdir/../file.txt") match
          case Right(sz) => check(sz == 2L, s"size=$sz (want 2)")
          case Left(e)   => check(false, s"unexpected Left($e)")
      finally TempDir.remove(root)
    }

    // ----- open (read) ---------------------------------------------------

    test("open: existing file returns a readable handle") {
      val root = TempDir.create("wasm-host-")
      try
        val bytes = "Hello, host!".getBytes("UTF-8")
        TempDir.writeFile(root, "hello.txt", bytes)
        val p = HostPreopen.fromDir(root, "/s")
        p.open("hello.txt", 0, 0) match
          case Right(f) =>
            val buf = new Array[Byte](bytes.length)
            val n   = f.read(buf, 0, bytes.length)
            f.close()
            check(n == bytes.length, s"read=$n (want ${bytes.length})")
            check(buf.toSeq == bytes.toSeq, s"bytes mismatch: ${buf.toSeq}")
          case Left(e) => check(false, s"unexpected Left($e)")
      finally TempDir.remove(root)
    }

    test("open: missing file with no CREAT returns Left(ENOENT)") {
      val root = TempDir.create("wasm-host-")
      try
        val p = HostPreopen.fromDir(root, "/s")
        p.open("nope.txt", 0, 0) match
          case Left(e) => check(e == Wasi.ENOENT, s"errno=$e (want ENOENT)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    test("open: directory entry returns Left(EISDIR)") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.mkdir(root, "subdir")
        val p = HostPreopen.fromDir(root, "/s")
        p.open("subdir", 0, 0) match
          case Left(e) => check(e == Wasi.EISDIR, s"errno=$e (want EISDIR)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    test("open: OFLAGS_DIRECTORY on regular file returns Left(ENOTDIR)") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.writeFile(root, "file.txt", Array[Byte](1, 2, 3))
        val p = HostPreopen.fromDir(root, "/s")
        // OFLAGS_DIRECTORY = 0x0002
        p.open("file.txt", 0x0002, 0) match
          case Left(e) => check(e == Wasi.ENOTDIR, s"errno=$e (want ENOTDIR)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    // ----- open (create) -------------------------------------------------

    test("open: OFLAGS_CREAT on missing path creates an empty file") {
      val root = TempDir.create("wasm-host-")
      try
        val p = HostPreopen.fromDir(root, "/s")
        // OFLAGS_CREAT = 0x0001
        p.open("new.txt", 0x0001, 0) match
          case Right(f) =>
            check(f.size == 0L, s"new file size=${f.size} (want 0)")
            f.close()
            check(TempDir.isFile(root, "new.txt"),
                  "host should have a new.txt file on disk")
            check(TempDir.readFile(root, "new.txt").length == 0,
                  "new.txt should be 0 bytes on disk")
          case Left(e) => check(false, s"unexpected Left($e)")
      finally TempDir.remove(root)
    }

    test("open: OFLAGS_CREAT+EXCL on existing returns Left(EEXIST)") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.writeFile(root, "x.txt", Array[Byte](1))
        val p = HostPreopen.fromDir(root, "/s")
        // OFLAGS_CREAT|OFLAGS_EXCL = 0x05
        p.open("x.txt", 0x0005, 0) match
          case Left(e) => check(e == Wasi.EEXIST, s"errno=$e (want EEXIST)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    test("open: OFLAGS_TRUNC on existing zeroes the file") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.writeFile(root, "junk.txt", "garbage".getBytes("UTF-8"))
        val p = HostPreopen.fromDir(root, "/s")
        // OFLAGS_TRUNC = 0x0008
        p.open("junk.txt", 0x0008, 0) match
          case Right(f) =>
            check(f.size == 0L, s"size after TRUNC=${f.size} (want 0)")
            f.close()
            check(TempDir.readFile(root, "junk.txt").length == 0,
                  "on-disk file should be 0 bytes after TRUNC")
          case Left(e) => check(false, s"unexpected Left($e)")
      finally TempDir.remove(root)
    }

    // ----- write + read roundtrip ----------------------------------------

    test("write + read: bytes round-trip through the host filesystem") {
      val root = TempDir.create("wasm-host-")
      try
        val p   = HostPreopen.fromDir(root, "/s")
        val msg = "Hello, host filesystem!".getBytes("UTF-8")
        // CREAT, then write, then close, then check disk
        p.open("out.txt", 0x0001, 0) match
          case Right(f) =>
            val n = f.write(msg, 0, msg.length)
            check(n == msg.length, s"write returned $n (want ${msg.length})")
            check(f.tell == msg.length.toLong, s"cursor=${f.tell}")
            f.close()
          case Left(e) => check(false, s"open returned Left($e)")
        // Verify content on disk via the platform helper.
        val onDisk = TempDir.readFile(root, "out.txt")
        check(onDisk.toSeq == msg.toSeq, s"on-disk bytes mismatch: ${onDisk.toSeq}")
        // And via the preopen surface again.
        p.open("out.txt", 0, 0) match
          case Right(f) =>
            val buf = new Array[Byte](msg.length)
            val n   = f.read(buf, 0, buf.length)
            f.close()
            check(n == msg.length, s"reread n=$n (want ${msg.length})")
            check(buf.toSeq == msg.toSeq, s"reread bytes mismatch: ${buf.toSeq}")
          case Left(e) => check(false, s"reopen returned Left($e)")
      finally TempDir.remove(root)
    }

    test("seek + read: read after seek lands at the new offset") {
      val root = TempDir.create("wasm-host-")
      try
        val all = "abcdefghij".getBytes("UTF-8")
        TempDir.writeFile(root, "alpha.txt", all)
        val p = HostPreopen.fromDir(root, "/s")
        p.open("alpha.txt", 0, 0) match
          case Right(f) =>
            f.seek(3L)
            check(f.tell == 3L, s"tell after seek=${f.tell} (want 3)")
            val buf = new Array[Byte](4)
            val n   = f.read(buf, 0, 4)
            f.close()
            check(n == 4, s"read n=$n (want 4)")
            check(new String(buf, "UTF-8") == "defg",
                  s"got '${new String(buf, "UTF-8")}'")
          case Left(e) => check(false, s"open returned Left($e)")
      finally TempDir.remove(root)
    }

    test("read past EOF returns 0") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.writeFile(root, "tiny.txt", "abc".getBytes("UTF-8"))
        val p = HostPreopen.fromDir(root, "/s")
        p.open("tiny.txt", 0, 0) match
          case Right(f) =>
            f.seek(100L)
            val buf = new Array[Byte](16)
            val n   = f.read(buf, 0, 16)
            f.close()
            check(n == 0, s"read past EOF returned $n (want 0)")
          case Left(e) => check(false, s"open returned Left($e)")
      finally TempDir.remove(root)
    }

    // ----- mkdir ---------------------------------------------------------

    test("mkdir: creates a directory on disk") {
      val root = TempDir.create("wasm-host-")
      try
        val p = HostPreopen.fromDir(root, "/s")
        p.mkdir("newdir") match
          case Right(_) =>
            check(TempDir.isDir(root, "newdir"),
                  "newdir should exist as a directory on disk")
          case Left(e) => check(false, s"unexpected Left($e)")
      finally TempDir.remove(root)
    }

    test("mkdir: existing path returns Left(EEXIST)") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.mkdir(root, "exists")
        val p = HostPreopen.fromDir(root, "/s")
        p.mkdir("exists") match
          case Left(e) => check(e == Wasi.EEXIST, s"errno=$e (want EEXIST)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    test("mkdir: existing file (not dir) returns Left(EEXIST)") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.writeFile(root, "x", Array[Byte](1))
        val p = HostPreopen.fromDir(root, "/s")
        p.mkdir("x") match
          case Left(e) => check(e == Wasi.EEXIST, s"errno=$e (want EEXIST)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    // ----- unlinkPath ----------------------------------------------------

    test("unlinkPath: removes file from disk") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.writeFile(root, "gone.txt", Array[Byte](9))
        val p = HostPreopen.fromDir(root, "/s")
        p.unlinkPath("gone.txt") match
          case Right(_) =>
            check(!TempDir.exists(root, "gone.txt"),
                  "gone.txt should be gone from disk")
          case Left(e) => check(false, s"unexpected Left($e)")
      finally TempDir.remove(root)
    }

    test("unlinkPath: missing path returns Left(ENOENT)") {
      val root = TempDir.create("wasm-host-")
      try
        val p = HostPreopen.fromDir(root, "/s")
        p.unlinkPath("nope") match
          case Left(e) => check(e == Wasi.ENOENT, s"errno=$e (want ENOENT)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    test("unlinkPath: directory returns Left(EISDIR)") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.mkdir(root, "adir")
        val p = HostPreopen.fromDir(root, "/s")
        p.unlinkPath("adir") match
          case Left(e) => check(e == Wasi.EISDIR, s"errno=$e (want EISDIR)")
          case other   => check(false, s"unexpected $other")
      finally TempDir.remove(root)
    }

    // ----- readdir -------------------------------------------------------

    test("readdir: lists entries with filetype and stable inode") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.writeFile(root, "a.txt", Array[Byte](1))
        TempDir.writeFile(root, "b.txt", Array[Byte](2))
        TempDir.mkdir(root, "subdir")
        val p   = HostPreopen.fromDir(root, "/s")
        val es  = p.readdir
        check(es.length == 3, s"entry count=${es.length} (want 3)")
        // Listed in lexicographic order — JVM, Native, JS all sort
        // explicitly in their HostFs.listDir() impls.
        check(es(0)._1 == "a.txt" && es(0)._2 == 4,
              s"first entry: ${es(0)} (want a.txt/file)")
        check(es(1)._1 == "b.txt" && es(1)._2 == 4,
              s"second entry: ${es(1)} (want b.txt/file)")
        check(es(2)._1 == "subdir" && es(2)._2 == 3,
              s"third entry: ${es(2)} (want subdir/dir)")
        // Inodes are 0..N-1 stable indices.
        check(es.map(_._3) == Seq(0L, 1L, 2L),
              s"inodes: ${es.map(_._3)}")
      finally TempDir.remove(root)
    }

    test("readdir: empty directory returns empty seq") {
      val root = TempDir.create("wasm-host-")
      try
        val p = HostPreopen.fromDir(root, "/s")
        check(p.readdir.isEmpty, s"non-empty: ${p.readdir}")
      finally TempDir.remove(root)
    }

    test("filetypeOf: file=4, dir=3, missing=None") {
      val root = TempDir.create("wasm-host-")
      try
        TempDir.writeFile(root, "f.txt", Array[Byte](1))
        TempDir.mkdir(root, "d")
        val p = HostPreopen.fromDir(root, "/s")
        check(p.filetypeOf("f.txt") == Some(4.toByte),
              s"file: ${p.filetypeOf("f.txt")}")
        check(p.filetypeOf("d") == Some(3.toByte),
              s"dir: ${p.filetypeOf("d")}")
        check(p.filetypeOf("nope") == None,
              s"missing: ${p.filetypeOf("nope")}")
      finally TempDir.remove(root)
    }
