package io.github.edadma.wasm.wasi

/** Scala.js stub of [[SocketTestHarness]]. Scala.js doesn't link
  * `java.net.Socket` / `ServerSocket`, so the localhost-loopback
  * pattern the JVM/Native tests rely on isn't usable here.
  *
  * `available = false` causes [[WasiSocketTests.run]] to print a
  * skip notice instead of running the test bodies. A Node-backed
  * impl could be written later via `js.Dynamic.global.require("net")`
  * but Preview-1 sockets are not in scope for the JS port today. */
object SocketTestHarness:
  val available: Boolean = false

  def bindListener(): (WasiContext.ServerSocket, Int) =
    throw new UnsupportedOperationException("sockets not available on Scala.js")

  def connectAndSend(port:        Int,
                     bytes:       Array[Byte],
                     halfClose:   Boolean = true,
                     expectBack:  Int     = 0): ClientThread =
    throw new UnsupportedOperationException("sockets not available on Scala.js")

  /** Stub matching the JVM type so [[WasiSocketTests]] can mention
    * `ClientThread` in type positions even when `available == false`.
    * Construction throws, so this is purely a name placeholder. The
    * surface (`isAlive`, `awaitDone`) tracks the JVM class so the
    * shared test code links. */
  final class ClientThread private[wasi] ():
    def isAlive: Boolean = false
    def awaitDone(timeoutMs: Long = 2000L): Array[Byte] =
      throw new UnsupportedOperationException("sockets not available on Scala.js")
