package io.github.edadma.wasm.wasi

/** JVM implementation of the cross-platform sleep helper used by
  * `poll_oneoff` to wait until a monotonic-clock deadline arrives.
  *
  * `Thread.sleep` is the right tool on JVM — the call yields the
  * thread cleanly, the JIT can hoist the resulting park. The Scala
  * Native build supplies the same primitive (javalib's
  * `java.lang.Thread.sleep` is API-compatible). The Scala.js build
  * supplies a non-blocking stub because synchronous sleep isn't
  * representable on a single-threaded event loop. */
object PlatformSleep:

  /** Sleep up to `millis` milliseconds. Negative or zero is a no-op.
    * Catches `InterruptedException` and any other host failure so the
    * caller's polling loop is not interrupted by spurious wakes. */
  def sleepMillis(millis: Long): Unit =
    if millis > 0L then
      try Thread.sleep(millis)
      catch case _: Throwable => ()
