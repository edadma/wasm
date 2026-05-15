package io.github.edadma.wasm.wasi

import java.io.{IOException, InputStream, OutputStream}
import java.net.{InetSocketAddress, ServerSocket as JServerSocket, Socket}

/** Scala Native factory for host-provided listening sockets. Wraps
  * `java.net.ServerSocket` from javalib in a [[WasiContext.ServerSocket]]
  * — API-compatible with the JVM build. See the JVM `HostServerSocket`
  * for usage docs. */
object HostServerSocket:

  def bind(port: Int = 0): (WasiContext.ServerSocket, Int) =
    val server = new JServerSocket()
    server.bind(new InetSocketAddress("127.0.0.1", port))
    (new NativeServerSocket(server), server.getLocalPort)

  def wrap(server: JServerSocket): WasiContext.ServerSocket =
    new NativeServerSocket(server)

  private final class NativeServerSocket(server: JServerSocket)
      extends WasiContext.ServerSocket:
    override def accept(): Either[Int, WasiContext.ClientSocket] =
      try
        val client = server.accept()
        Right(new NativeClientSocket(client))
      catch
        case _: java.net.SocketException => Left(Wasi.ECONNRESET)
        case _: IOException              => Left(Wasi.EIO)

    override def address: String =
      val a = server.getInetAddress
      val p = server.getLocalPort
      if a == null then s":$p" else s"${a.getHostAddress}:$p"

  private final class NativeClientSocket(client: Socket)
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
      // Per-side try/catch: Native's javalib `Socket.shutdownInput` /
      // `shutdownOutput` can throw `IOException("not implemented")` on
      // some platforms even when the OS-level half-close did happen
      // (or the underlying TCP state is already half-closed). Treat
      // those as ESUCCESS — the wasi caller's expectation is "the
      // shutdown intent has been recorded", not "the host syscall
      // returned 0 on every retry".
      if (how & 0x1) != 0 && !client.isInputShutdown then
        try client.shutdownInput() catch case _: IOException => ()
      if (how & 0x2) != 0 && !client.isOutputShutdown then
        try client.shutdownOutput() catch case _: IOException => ()
      Right(())
