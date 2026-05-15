package io.github.edadma.wasm.wasi

import java.io.{IOException, InputStream, OutputStream}
import java.net.{InetSocketAddress, ServerSocket as JServerSocket, Socket}

/** JVM factory for host-provided listening sockets. Wraps
  * `java.net.ServerSocket` in a [[WasiContext.ServerSocket]] so the
  * wasi shim can hand the listening fd to the program and let it call
  * `sock_accept`.
  *
  * Usage:
  *
  * {{{
  * val (server, port) = HostServerSocket.bind(0)   // OS-chosen port
  * val ctx = WasiContext(sockets = Vector(server))
  * }}}
  *
  * The Scala Native build supplies an identical object (javalib's
  * `java.net.ServerSocket` is API-compatible). Scala.js does not
  * ship `java.net.ServerSocket` — a wasi program running under
  * Node.js that needs sockets must provide its own
  * [[WasiContext.ServerSocket]] impl backed by Node's `net` module
  * (or the shim returns `EBADF` against any `sock_accept`). */
object HostServerSocket:

  /** Bind a listening socket on `localhost:port`. Pass `port = 0` to
    * let the kernel pick a free ephemeral port (then read the actual
    * port back with `.address`). Returns the [[WasiContext.ServerSocket]]
    * wrapping the JVM-side `ServerSocket` plus the port number for
    * tests that need to connect a client.
    *
    * Throws `IOException` eagerly if the bind fails — failing here is
    * friendlier than failing at the first `sock_accept` inside the
    * wasi program. */
  def bind(port: Int = 0): (WasiContext.ServerSocket, Int) =
    val server = new JServerSocket()
    server.bind(new InetSocketAddress("127.0.0.1", port))
    (new JvmServerSocket(server), server.getLocalPort)

  /** Wrap an already-bound `java.net.ServerSocket`. Useful when the
    * test harness wants to control the bind (e.g. to attach multiple
    * listeners) or to share a port across instantiations. The
    * returned wrapper takes ownership for `close()` purposes — call
    * `close()` on the wrapper (or rely on `fd_close` from the wasi
    * program) rather than on `server` directly. */
  def wrap(server: JServerSocket): WasiContext.ServerSocket =
    new JvmServerSocket(server)

  private final class JvmServerSocket(server: JServerSocket)
      extends WasiContext.ServerSocket:
    override def accept(): Either[Int, WasiContext.ClientSocket] =
      try
        val client = server.accept()
        Right(new JvmClientSocket(client))
      catch
        case _: java.net.SocketException => Left(Wasi.ECONNRESET)
        case _: IOException              => Left(Wasi.EIO)

    override def address: String =
      val a = server.getInetAddress
      val p = server.getLocalPort
      if a == null then s":$p" else s"${a.getHostAddress}:$p"

  /** Adapter from `java.net.Socket` to [[WasiContext.ClientSocket]].
    * Caches the input/output streams once at construction — reuse on
    * every `read` / `write` avoids the per-call lookup cost and matches
    * the `Socket` contract (the streams are stable for the lifetime
    * of the socket).
    *
    * Read EOF (peer closed) and write-after-close both surface as
    * 0-byte counts rather than wasi errnos, matching wasi-libc's
    * expectation: a 0-byte `fd_read` is end-of-stream, a 0-byte
    * `fd_write` is "host accepted nothing right now". A future
    * sharpening pass could differentiate via `ECONNRESET`. */
  private final class JvmClientSocket(client: Socket)
      extends WasiContext.ClientSocket:
    private val in:  InputStream  = client.getInputStream
    private val out: OutputStream = client.getOutputStream

    def close(): Unit =
      try client.close() catch case _: IOException => ()

    def read(dst: Array[Byte], offset: Int, length: Int): Int =
      if length <= 0 then 0
      else
        try
          val n = in.read(dst, offset, length)
          if n < 0 then 0 else n
        catch case _: IOException => 0

    override def write(src: Array[Byte], offset: Int, length: Int): Int =
      if length <= 0 then 0
      else
        try
          out.write(src, offset, length)
          out.flush()
          length
        catch case _: IOException => 0

    override def shutdown(how: Int): Either[Int, Unit] =
      // POSIX `shutdown(2)` is idempotent — repeating SD_RD after the
      // read side is already shut down is not an error. `java.net.Socket`
      // disagrees: it throws `SocketException("Socket input is already
      // shutdown")` in that case. Guard with `isInputShutdown` /
      // `isOutputShutdown` so the wasi-facing behaviour matches POSIX.
      try
        if (how & 0x1) != 0 && !client.isInputShutdown  then client.shutdownInput()
        if (how & 0x2) != 0 && !client.isOutputShutdown then client.shutdownOutput()
        Right(())
      catch case _: IOException => Left(Wasi.EIO)
