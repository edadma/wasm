package io.github.edadma.wasm.cli

import java.nio.file.{Files, Paths}

/** Scala Native entry point — `java.nio.file` is provided by Scala Native's
  * javalib, so the implementation is identical to the JVM one. */
object Main:

  private val nativePlatform: Cli.Platform = new Cli.Platform:
    def readFile(path: String): Array[Byte] = Files.readAllBytes(Paths.get(path))
    def exit(code: Int): Nothing            = sys.exit(code)

  def main(args: Array[String]): Unit = Cli.run(args, nativePlatform)
