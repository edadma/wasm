package io.github.edadma.wasm.cli

import io.github.edadma.wasm.wasi.{HostPreopen, WasiContext}

import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.typedarray.Uint8Array

/** Scala.js / Node.js entry point — uses Node's `fs` for file reads and
  * `process.exit` for the exit primitive. Args come in through `main`'s
  * `args: Array[String]` (we keep `scalaJSUseMainModuleInitializer := false`
  * so sbt's InputTask forwards them; see build.sbt for the rationale).
  * Host-backed preopens go through the JS `HostPreopen.fromDir` factory
  * (Node `fs.*Sync`). */
object Main:

  private val jsPlatform: Cli.Platform = new Cli.Platform:
    def readFile(path: String): Array[Byte] =
      val fs  = g.require("fs")
      val buf = fs.readFileSync(path).asInstanceOf[Uint8Array]
      val out = new Array[Byte](buf.length)
      var i   = 0
      while i < buf.length do
        out(i) = buf(i).toByte
        i += 1
      out

    def exit(code: Int): Nothing =
      val _ = g.process.exit(code)
      throw new RuntimeException("unreachable after process.exit")

    def openPreopen(hostPath: String, virtualName: String): WasiContext.Preopen =
      HostPreopen.fromDir(hostPath, virtualName)

  def main(args: Array[String]): Unit =
    // The scalajs linker calls `main(Array.empty)` regardless of process.argv
    // when `scalaJSUseMainModuleInitializer := true`. We deliberately ignore
    // the `args` parameter and read process.argv ourselves so `node main.js
    // foo bar` works the way a Unix CLI is expected to.
    val argv = g.process.argv.asInstanceOf[js.Array[String]].drop(2).toArray
    Cli.run(argv, jsPlatform)
