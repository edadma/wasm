package io.github.edadma.wasm.wasi

import java.io.IOException
import java.net.{InetSocketAddress, Socket}

/** Scala Native impl of [[SocketTestHarness]]. javalib provides an
  * API-compatible `java.net.Socket` so the file is byte-for-byte
  * identical to the JVM version. Duplicated rather than `shared/`
  * because the JS port has a stub that can't reach `java.net`. */
object SocketTestHarness:

  val available: Boolean = true

  def bindListener(): (WasiContext.ServerSocket, Int) =
    HostServerSocket.bind(0)

  def connectAndSend(port:        Int,
                     bytes:       Array[Byte],
                     halfClose:   Boolean = true,
                     expectBack:  Int     = 0): ClientThread =
    val ct = new ClientThread(port, bytes, halfClose, expectBack)
    ct.start()
    ct

  final class ClientThread private[wasi] (port:       Int,
                                          bytes:      Array[Byte],
                                          halfClose:  Boolean,
                                          expectBack: Int) extends Thread:
    @volatile private var received: Array[Byte] = Array.emptyByteArray
    @volatile private var failure:  Option[Throwable] = None

    setDaemon(true)
    setName(s"wasi-socket-client-$port")

    override def run(): Unit =
      try
        val sock = new Socket()
        try
          sock.connect(new InetSocketAddress("127.0.0.1", port), 2000)
          val out = sock.getOutputStream
          // Native's javalib InputStream/OutputStream rejects zero-length
          // writes with `ArrayIndexOutOfBoundsException`; the JVM accepts
          // them as a no-op. Skip the call so the harness behaves
          // identically on both ports.
          if bytes.length > 0 then
            out.write(bytes)
            out.flush()
          if halfClose then sock.shutdownOutput()

          if expectBack > 0 then
            val buf = new Array[Byte](expectBack)
            val in  = sock.getInputStream
            var read = 0
            while read < expectBack do
              val n = in.read(buf, read, expectBack - read)
              if n < 0 then return
              read += n
            received = buf
        finally
          try sock.close() catch case _: IOException => ()
      catch case e: Throwable => failure = Some(e)

    def awaitDone(timeoutMs: Long = 2000L): Array[Byte] =
      join(timeoutMs)
      if isAlive then throw new AssertionError(
        s"client thread for port $port did not exit within ${timeoutMs}ms")
      failure match
        case Some(e) => throw new AssertionError(
          s"client thread failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
        case None    => received
