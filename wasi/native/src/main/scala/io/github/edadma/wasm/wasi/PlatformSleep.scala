package io.github.edadma.wasm.wasi

/** Scala Native impl of the platform sleep helper. javalib's
  * `java.lang.Thread.sleep` is API-compatible with JVM, so the body
  * is identical. Lives in the per-platform directory because the
  * Scala.js port can't link `Thread.sleep` and must stub. */
object PlatformSleep:
  def sleepMillis(millis: Long): Unit =
    if millis > 0L then
      try Thread.sleep(millis)
      catch case _: Throwable => ()
