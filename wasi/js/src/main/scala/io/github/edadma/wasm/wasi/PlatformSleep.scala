package io.github.edadma.wasm.wasi

/** Scala.js stub of [[PlatformSleep]]. Synchronous sleep isn't
  * representable on a single-threaded event loop — `Thread.sleep`
  * isn't even linked by the Scala.js standard library. `poll_oneoff`
  * on JS therefore degrades to a tight busy-spin (the caller still
  * checks the monotonic clock each iteration). This is acceptable
  * for the typical wasi-program use of `poll_oneoff` (short timeouts
  * on the order of milliseconds); long-blocking sleeps were never
  * the JS-shim sweet spot. */
object PlatformSleep:
  def sleepMillis(millis: Long): Unit = ()
