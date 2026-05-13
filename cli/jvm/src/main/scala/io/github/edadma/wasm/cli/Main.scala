package io.github.edadma.wasm.cli

import java.nio.file.{Files, Paths}

/** JVM entry point — supplies the platform plumbing and delegates to
  * [[Cli.run]] for parsing and dispatch. */
object Main:

  private val jvmPlatform: Cli.Platform = new Cli.Platform:
    def readFile(path: String): Array[Byte] = Files.readAllBytes(Paths.get(path))
    def exit(code: Int): Nothing            = sys.exit(code)

  def main(args: Array[String]): Unit = Cli.run(args, jvmPlatform)
