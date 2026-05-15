package io.github.edadma.wasm.wasi

import java.io.IOException
import java.net.{InetSocketAddress, Socket}

/** Per-platform helper used by [[WasiSocketTests]] to spin up a real
  * listening socket and drive client traffic against it. JVM impl —
  * Scala Native ships an identical file under `wasi/native/src/test/`
  * (javalib's `java.net.Socket` is API-compatible). The JS impl is a
  * "not available" stub: Scala.js doesn't link `java.net`, so the
  * shared `WasiSocketTests.run()` checks `available` and skips on JS.
  *
  * Why this isn't in `shared/`: `java.net.Socket` doesn't exist on
  * Scala.js, so test code can't reach for it directly. Putting a
  * thin platform-dispatched helper here lets the actual test logic
  * (sock_accept / sock_recv / sock_send / sock_shutdown call shapes)
  * live in `shared/`. */
object SocketTestHarness:

  /** Whether this platform supports the localhost listener pattern
    * the socket tests need. `true` on JVM and Scala Native; `false`
    * on Scala.js. When false, [[WasiSocketTests.run]] prints a
    * skip notice instead of asserting. */
  val available: Boolean = true

  /** Bind a localhost listener on an ephemeral port; return the
    * wasi-facing [[WasiContext.ServerSocket]] and the actual host
    * port number. Tests connect a client to that port from a
    * background thread. */
  def bindListener(): (WasiContext.ServerSocket, Int) =
    HostServerSocket.bind(0)

  /** Spawn a background thread that connects to `127.0.0.1:port`,
    * sends `bytes`, optionally closes the write side, and (if
    * `expectBack > 0`) reads back exactly that many bytes which the
    * caller can inspect via [[receivedFrom]].
    *
    * The thread shouldn't outlive the test — `awaitClientDone` blocks
    * until the thread exits. We use a JVM `Thread` (not a futures
    * pool) because the test harness is single-threaded and the
    * lifecycle is precisely scoped. */
  def connectAndSend(port:        Int,
                     bytes:       Array[Byte],
                     halfClose:   Boolean = true,
                     expectBack:  Int     = 0): ClientThread =
    val ct = new ClientThread(port, bytes, halfClose, expectBack)
    ct.start()
    ct

  /** Background thread that drives a client connection. Construction
    * is via [[SocketTestHarness.connectAndSend]]. After the test
    * exercises the wasi program's `sock_accept` / `sock_recv` /
    * `sock_send` calls, [[awaitDone]] joins the thread and returns
    * any bytes the client received. */
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

    /** Join the thread and surface what it received from the wasi
      * program (empty if `expectBack` was 0). Re-throws any
      * connect/IO failure so the test fails with a precise message
      * rather than a silent timeout. */
    def awaitDone(timeoutMs: Long = 2000L): Array[Byte] =
      join(timeoutMs)
      if isAlive then throw new AssertionError(
        s"client thread for port $port did not exit within ${timeoutMs}ms")
      failure match
        case Some(e) => throw new AssertionError(
          s"client thread failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
        case None    => received
