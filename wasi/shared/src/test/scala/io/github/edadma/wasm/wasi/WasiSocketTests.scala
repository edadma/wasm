package io.github.edadma.wasm.wasi

import io.github.edadma.wasm.{I32, ModuleInstance}

import WasiTestSupport.{check, instantiate, test}

/** sock_* tests — covers the WASI Preview 1 socket surface:
  *
  *   - `sock_accept(fd, fdflags, retfd_out) -> errno` accepts a single
  *     connection from a listening fd and stores the new fd in the
  *     output slot. The host-side [[WasiContext.ServerSocket]] supplies
  *     the underlying connection.
  *
  *   - `sock_recv(fd, iovs, iovs_len, ri_flags, ro_datalen_out,
  *                ro_flags_out) -> errno` reads from an accepted socket
  *     into iovec destinations; mirrors `fd_read`'s iovec walk with
  *     extra recvflags/roflags slots.
  *
  *   - `sock_send(fd, iovs, iovs_len, si_flags, so_datalen_out) -> errno`
  *     writes iovec sources to an accepted socket; sendflags is
  *     reserved in Preview 1.
  *
  *   - `sock_shutdown(fd, how) -> errno` half-closes one or both sides
  *     of an accepted connection. `how` is `SD_RD = 1`, `SD_WR = 2`,
  *     or `SD_BOTH = 3`.
  *
  * The shim adopts the BSD-inetd model: the host pre-binds listening
  * sockets via [[WasiContext.sockets]]. Each listening socket is
  * exposed at fd `3 + preopens.length + i`; the accepted client lands
  * in the per-instance fd table at the next free slot.
  *
  * Tests use a real localhost listener via [[SocketTestHarness]] —
  * a JVM/Native helper that binds an ephemeral port and drives client
  * traffic from a background thread. Scala.js doesn't link
  * `java.net.Socket`, so the harness reports `available = false` and
  * the test bodies print a skip notice instead of running.
  */
object WasiSocketTests:

  def run(): Unit =
    println()
    println("-- WasiSocketTests --")

    if !SocketTestHarness.available then
      println("  (skipped — sockets not available on this platform)")
      return

    // ----- sock_accept ---------------------------------------------------

    test("sock_accept: EBADF for unknown listening fd") {
      val (inst, _) = instantiate(WasiFixtures.wasi_fd_io)
      // No sockets at all — the listening-fd range is empty, so fd 3
      // (or anything ≥ 3) isn't a listener.
      callSockAccept(inst, fd = 3, retfdOut = 64) match
        case Right(Seq(I32(errno))) =>
          check(errno == Wasi.EBADF, s"errno=$errno (want EBADF=${Wasi.EBADF})")
        case other => check(false, s"call_sock_accept: $other")
    }

    test("sock_accept: EBADF for fd just past the listening range") {
      val (server, _) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        // fd 3 is the (only) listener; fd 4 is out of the listening range.
        callSockAccept(inst, fd = 4, retfdOut = 64) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.EBADF,
                  s"errno=$errno (want EBADF=${Wasi.EBADF})")
          case other => check(false, s"call_sock_accept: $other")
      finally serverClose(server)
    }

    test("sock_accept: EFAULT when retfd_out is out of bounds") {
      val (server, _) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        callSockAccept(inst, fd = 3, retfdOut = 65535) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.EFAULT,
                  s"errno=$errno (want EFAULT=${Wasi.EFAULT})")
          case other => check(false, s"call_sock_accept: $other")
      finally serverClose(server)
    }

    test("sock_accept: happy path — fd ends up in the FdTable") {
      val (server, port) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        val client = SocketTestHarness.connectAndSend(port, Array[Byte](),
                                                      halfClose = true)
        try
          callSockAccept(inst, fd = 3, retfdOut = 64) match
            case Right(Seq(I32(errno))) =>
              check(errno == Wasi.ESUCCESS,
                    s"errno=$errno (want ESUCCESS)")
            case other => check(false, s"call_sock_accept: $other")
          val acceptedFd = peekI32(inst, 64)
          // One preopen-less context + one listening socket means the
          // FdTable's base fd is 3 + 0 + 1 = 4. First allocation = 4.
          check(acceptedFd == 4, s"accepted fd=$acceptedFd (want 4)")
        finally
          val _ = client.awaitDone()
      finally serverClose(server)
    }

    // ----- sock_recv -----------------------------------------------------

    test("sock_recv: reads bytes the peer sent") {
      val (server, port) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        val msg = "hello, sockets".getBytes("UTF-8")
        val client = SocketTestHarness.connectAndSend(port, msg,
                                                      halfClose = true)
        try
          accept(inst, listeningFd = 3)
          val acceptedFd = peekI32(inst, 64)

          // iovec table at 0x100: one entry (buf=0x300, len=64).
          storeI32(inst, 0x100, 0x300)
          storeI32(inst, 0x104, 64)
          callSockRecv(inst, acceptedFd, iovs = 0x100, iovsLen = 1,
                       riFlags = 0, roDatalenOut = 0x120, roFlagsOut = 0x124) match
            case Right(Seq(I32(errno))) =>
              check(errno == Wasi.ESUCCESS, s"errno=$errno")
            case other => check(false, s"call_sock_recv: $other")

          val n = peekI32(inst, 0x120)
          check(n == msg.length, s"nread=$n (want ${msg.length})")
          val roFlags = peekI32(inst, 0x124)
          check(roFlags == 0, s"ro_flags=$roFlags (want 0)")
          val got = readBytes(inst, 0x300, n)
          check(got.sameElements(msg),
                s"recv bytes: ${new String(got, "UTF-8")} (want ${new String(msg, "UTF-8")})")
        finally
          val _ = client.awaitDone()
      finally serverClose(server)
    }

    test("sock_recv: EBADF on unknown fd") {
      val (server, _) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        // fd 99 isn't anything — neither stdio, preopen, listener, nor
        // accepted socket in this fresh instance.
        storeI32(inst, 0x100, 0x300)
        storeI32(inst, 0x104, 64)
        callSockRecv(inst, fd = 99, iovs = 0x100, iovsLen = 1,
                     riFlags = 0, roDatalenOut = 0x120, roFlagsOut = 0x124) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.EBADF, s"errno=$errno (want EBADF)")
          case other => check(false, s"call_sock_recv: $other")
      finally serverClose(server)
    }

    test("sock_recv: ENOTSOCK on listening-socket fd") {
      val (server, _) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        storeI32(inst, 0x100, 0x300)
        storeI32(inst, 0x104, 64)
        callSockRecv(inst, fd = 3, iovs = 0x100, iovsLen = 1,
                     riFlags = 0, roDatalenOut = 0x120, roFlagsOut = 0x124) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.ENOTSOCK,
                  s"errno=$errno (want ENOTSOCK=${Wasi.ENOTSOCK})")
          case other => check(false, s"call_sock_recv: $other")
      finally serverClose(server)
    }

    test("sock_recv: EINVAL on unknown ri_flags bits") {
      val (server, _) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        storeI32(inst, 0x100, 0x300)
        storeI32(inst, 0x104, 64)
        callSockRecv(inst, fd = 4, iovs = 0x100, iovsLen = 1,
                     riFlags = 0x8, roDatalenOut = 0x120, roFlagsOut = 0x124) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.EINVAL, s"errno=$errno (want EINVAL)")
          case other => check(false, s"call_sock_recv: $other")
      finally serverClose(server)
    }

    // ----- sock_send -----------------------------------------------------

    test("sock_send: writes bytes the peer receives") {
      val (server, port) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        val client = SocketTestHarness.connectAndSend(port, Array[Byte](),
                                                      halfClose = true,
                                                      expectBack = 5)
        try
          accept(inst, listeningFd = 3)
          val acceptedFd = peekI32(inst, 64)

          val payload = "12345".getBytes("UTF-8")
          // Pack the payload at 0x300, iovec at 0x100.
          for i <- payload.indices do storeByte(inst, 0x300 + i, payload(i))
          storeI32(inst, 0x100, 0x300)
          storeI32(inst, 0x104, payload.length)
          callSockSend(inst, acceptedFd, iovs = 0x100, iovsLen = 1,
                       siFlags = 0, soDatalenOut = 0x120) match
            case Right(Seq(I32(errno))) =>
              check(errno == Wasi.ESUCCESS, s"errno=$errno")
            case other => check(false, s"call_sock_send: $other")
          val n = peekI32(inst, 0x120)
          check(n == payload.length,
                s"sock_send nwritten=$n (want ${payload.length})")

          val got = client.awaitDone()
          check(got.sameElements(payload),
                s"client got ${new String(got, "UTF-8")} (want ${new String(payload, "UTF-8")})")
        finally
          // If the test threw before awaitDone, drain anyway so the
          // background thread can't outlive the test.
          if client.isAlive then
            val _ = client.awaitDone()
      finally serverClose(server)
    }

    test("sock_send: EINVAL on non-zero si_flags") {
      val (server, _) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        storeI32(inst, 0x100, 0x300)
        storeI32(inst, 0x104, 1)
        callSockSend(inst, fd = 4, iovs = 0x100, iovsLen = 1,
                     siFlags = 1, soDatalenOut = 0x120) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.EINVAL, s"errno=$errno (want EINVAL)")
          case other => check(false, s"call_sock_send: $other")
      finally serverClose(server)
    }

    test("sock_send: ENOTSOCK on listening-socket fd") {
      val (server, _) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        storeI32(inst, 0x100, 0x300)
        storeI32(inst, 0x104, 1)
        callSockSend(inst, fd = 3, iovs = 0x100, iovsLen = 1,
                     siFlags = 0, soDatalenOut = 0x120) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.ENOTSOCK,
                  s"errno=$errno (want ENOTSOCK)")
          case other => check(false, s"call_sock_send: $other")
      finally serverClose(server)
    }

    // ----- sock_shutdown -------------------------------------------------

    test("sock_shutdown: SD_RD=1, SD_WR=2, SD_BOTH=3 all succeed") {
      val (server, port) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        val client = SocketTestHarness.connectAndSend(port, Array[Byte](),
                                                      halfClose = false)
        try
          accept(inst, listeningFd = 3)
          val acceptedFd = peekI32(inst, 64)
          for how <- Seq(1, 2, 3) do
            callSockShutdown(inst, acceptedFd, how) match
              case Right(Seq(I32(errno))) =>
                check(errno == Wasi.ESUCCESS,
                      s"shutdown how=$how errno=$errno (want 0)")
              case other => check(false, s"call_sock_shutdown how=$how: $other")
        finally
          val _ = client.awaitDone()
      finally serverClose(server)
    }

    test("sock_shutdown: EINVAL on how=0 / how=4") {
      val (server, port) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        val client = SocketTestHarness.connectAndSend(port, Array[Byte](),
                                                      halfClose = false)
        try
          accept(inst, listeningFd = 3)
          val acceptedFd = peekI32(inst, 64)
          for how <- Seq(0, 4, -1, 99) do
            callSockShutdown(inst, acceptedFd, how) match
              case Right(Seq(I32(errno))) =>
                check(errno == Wasi.EINVAL,
                      s"shutdown how=$how errno=$errno (want EINVAL)")
              case other => check(false, s"call_sock_shutdown how=$how: $other")
        finally
          val _ = client.awaitDone()
      finally serverClose(server)
    }

    test("sock_shutdown: ENOTSOCK on listening-socket fd") {
      val (server, _) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        callSockShutdown(inst, fd = 3, how = 3) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.ENOTSOCK,
                  s"errno=$errno (want ENOTSOCK)")
          case other => check(false, s"call_sock_shutdown: $other")
      finally serverClose(server)
    }

    test("sock_shutdown: EBADF on unknown fd") {
      val (server, _) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        callSockShutdown(inst, fd = 99, how = 3) match
          case Right(Seq(I32(errno))) =>
            check(errno == Wasi.EBADF,
                  s"errno=$errno (want EBADF)")
          case other => check(false, s"call_sock_shutdown: $other")
      finally serverClose(server)
    }

    // ----- fd_read / fd_write through an accepted socket -----------------

    test("fd_read on an accepted socket routes to ClientSocket.read") {
      val (server, port) = SocketTestHarness.bindListener()
      try
        val (inst, _) = instantiate(WasiFixtures.wasi_fd_io,
                                    sockets = Seq(server))
        val msg = "via-fd_read".getBytes("UTF-8")
        val client = SocketTestHarness.connectAndSend(port, msg,
                                                      halfClose = true)
        try
          accept(inst, listeningFd = 3)
          val acceptedFd = peekI32(inst, 64)

          storeI32(inst, 0x100, 0x300)
          storeI32(inst, 0x104, 64)
          inst.invoke("call_fd_read",
                      Seq(I32(acceptedFd), I32(0x100), I32(1), I32(0x120))) match
            case Right(Seq(I32(errno))) =>
              check(errno == Wasi.ESUCCESS, s"errno=$errno")
            case other => check(false, s"call_fd_read: $other")

          val n = peekI32(inst, 0x120)
          check(n == msg.length, s"nread=$n (want ${msg.length})")
          val got = readBytes(inst, 0x300, n)
          check(got.sameElements(msg),
                s"fd_read bytes: ${new String(got, "UTF-8")}")
        finally
          val _ = client.awaitDone()
      finally serverClose(server)
    }

  // === helpers ===========================================================

  /** Perform the canonical `sock_accept` against the listener at
    * `listeningFd` and assert that the resulting errno is ESUCCESS.
    * Leaves the accepted fd in linear memory at address 64. */
  private def accept(inst: ModuleInstance, listeningFd: Int): Unit =
    callSockAccept(inst, listeningFd, retfdOut = 64) match
      case Right(Seq(I32(errno))) =>
        check(errno == Wasi.ESUCCESS,
              s"sock_accept errno=$errno (want ESUCCESS)")
      case other => check(false, s"sock_accept dispatch: $other")

  private def serverClose(s: WasiContext.ServerSocket): Unit =
    // No host-side `close` on the trait — the JVM impl's resources are
    // released when the underlying `ServerSocket` is GC'd. We could
    // expose `close()` on the trait in a future hardening pass; for
    // tests this is fine because each test binds a fresh ephemeral
    // port and the OS reclaims the port when the JVM exits.
    ()

  private def callSockAccept(inst: ModuleInstance, fd: Int, retfdOut: Int) =
    inst.invoke("call_sock_accept",
                Seq(I32(fd), I32(0), I32(retfdOut)))

  private def callSockRecv(inst:         ModuleInstance,
                           fd:           Int,
                           iovs:         Int,
                           iovsLen:      Int,
                           riFlags:      Int,
                           roDatalenOut: Int,
                           roFlagsOut:   Int) =
    inst.invoke("call_sock_recv",
                Seq(I32(fd), I32(iovs), I32(iovsLen), I32(riFlags),
                    I32(roDatalenOut), I32(roFlagsOut)))

  private def callSockSend(inst:         ModuleInstance,
                           fd:           Int,
                           iovs:         Int,
                           iovsLen:      Int,
                           siFlags:      Int,
                           soDatalenOut: Int) =
    inst.invoke("call_sock_send",
                Seq(I32(fd), I32(iovs), I32(iovsLen), I32(siFlags),
                    I32(soDatalenOut)))

  private def callSockShutdown(inst: ModuleInstance, fd: Int, how: Int) =
    inst.invoke("call_sock_shutdown", Seq(I32(fd), I32(how)))

  private def storeI32(inst: ModuleInstance, addr: Int, v: Int): Unit =
    inst.invoke("store_i32", Seq(I32(addr), I32(v))) match
      case Right(_) => ()
      case other    => throw new AssertionError(s"store_i32($addr): $other")

  private def storeByte(inst: ModuleInstance, addr: Int, b: Byte): Unit =
    inst.invoke("store_byte", Seq(I32(addr), I32(b & 0xff))) match
      case Right(_) => ()
      case other    => throw new AssertionError(s"store_byte($addr): $other")

  private def peekI32(inst: ModuleInstance, addr: Int): Int =
    inst.invoke("load_i32", Seq(I32(addr))) match
      case Right(Seq(I32(v))) => v
      case other              => throw new AssertionError(s"load_i32($addr): $other")

  private def readBytes(inst: ModuleInstance, addr: Int, len: Int): Array[Byte] =
    val out = new Array[Byte](len)
    var i = 0
    while i < len do
      inst.invoke("load_byte", Seq(I32(addr + i))) match
        case Right(Seq(I32(b))) => out(i) = b.toByte
        case other => throw new AssertionError(s"load_byte: $other")
      i += 1
    out
