package io.github.edadma.wasm.cli

import io.github.edadma.wasm.wasi.{HostPreopen, WasiContext}

import java.nio.file.{Files, Paths}

/** JVM entry point — supplies the platform plumbing and delegates to
  * [[Cli.run]] for parsing and dispatch. Host-backed preopens go through
  * the JVM `HostPreopen.fromDir` factory (`java.nio.file` + `FileChannel`). */
object Main:

  private val jvmPlatform: Cli.Platform = new Cli.Platform:
    def readFile(path: String): Array[Byte] = Files.readAllBytes(Paths.get(path))
    def exit(code: Int): Nothing            = sys.exit(code)
    def openPreopen(hostPath: String, virtualName: String): WasiContext.Preopen =
      HostPreopen.fromDir(hostPath, virtualName)

  def main(args: Array[String]): Unit = Cli.run(args, jvmPlatform)
