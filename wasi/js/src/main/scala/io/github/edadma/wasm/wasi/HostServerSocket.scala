package io.github.edadma.wasm.wasi

/** Scala.js stub for [[HostServerSocket]]. Scala.js does not link
  * `java.net.ServerSocket`; if a Node-backed program needs a wasi
  * `sock_accept` capability it must supply its own
  * [[WasiContext.ServerSocket]] impl (e.g. wrapping Node's `net`
  * module) rather than relying on this factory.
  *
  * The two stub methods throw `UnsupportedOperationException` so a
  * `bind` call that wandered into a JS code path fails loudly at
  * the construction site rather than silently producing a broken
  * shim. */
object HostServerSocket:

  def bind(port: Int = 0): (WasiContext.ServerSocket, Int) =
    throw new UnsupportedOperationException(
      "HostServerSocket.bind unavailable on Scala.js; provide a custom WasiContext.ServerSocket")

  /** No-op wrap that delegates to [[bind]]'s error — the JS port
    * has no `java.net.ServerSocket` to wrap in the first place.
    * Defined so the cross-build compiles when callers reach for it. */
  def wrap(server: Any): WasiContext.ServerSocket =
    throw new UnsupportedOperationException(
      "HostServerSocket.wrap unavailable on Scala.js")
