import xerial.sbt.Sonatype.sonatypeCentralHost

ThisBuild / licenses               := Seq("ISC" -> url("https://opensource.org/licenses/ISC"))
ThisBuild / versionScheme          := Some("semver-spec")
ThisBuild / evictionErrorLevel     := Level.Warn
ThisBuild / scalaVersion           := "3.8.3"
ThisBuild / organization           := "io.github.edadma"
ThisBuild / organizationName       := "edadma"
ThisBuild / organizationHomepage   := Some(url("https://github.com/edadma"))
ThisBuild / version                := "0.0.1"
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost

ThisBuild / publishConfiguration := publishConfiguration.value.withOverwrite(true).withChecksums(Vector.empty)
ThisBuild / resolvers += Resolver.mavenLocal
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots
ThisBuild / resolvers += Resolver.sonatypeCentralRepo("releases")

ThisBuild / sonatypeProfileName := "io.github.edadma"

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/edadma/wasm"),
    "scm:git@github.com:edadma/wasm.git",
  ),
)
ThisBuild / developers := List(
  Developer(
    id = "edadma",
    name = "Edward A. Maxedon, Sr.",
    email = "edadma@gmail.com",
    url = url("https://github.com/edadma"),
  ),
)

ThisBuild / homepage    := Some(url("https://github.com/edadma/wasm"))
ThisBuild / description := "Zero-dependency WebAssembly MVP interpreter for Scala 3 (JVM, Scala.js, Scala Native)."

ThisBuild / publishTo := sonatypePublishToBundle.value

// Regenerate `Fixtures.scala` (an `object` holding each .wasm fixture as an
// Array[Byte]) by reading the committed `.wasm` files. Runs on every compile
// so the constants and the committed binaries can never drift.
def generateFixtures(outFile: File, fixturesDir: File, log: Logger): Seq[File] = {
  val wasmFiles = Option(fixturesDir.listFiles()).getOrElse(Array.empty[File])
    .filter(_.getName.endsWith(".wasm"))
    .sortBy(_.getName)
  val sb = new StringBuilder
  sb.append("package io.github.edadma.wasm\n\n")
  // Note: Scala supports nested block comments, so we have to avoid producing
  // a `/*` substring inside the generated docstring (e.g. `fixtures/*.wasm`).
  sb.append("/** Generated from the .wasm files in shared/src/test/resources/fixtures.\n")
  sb.append("  *\n")
  sb.append("  * Do not edit by hand — change a `.wat`, run `wat2wasm` to refresh the `.wasm`,\n")
  sb.append("  * and this file rebuilds on the next `sbt compile`.\n")
  sb.append("  */\n")
  sb.append("object Fixtures:\n\n")
  wasmFiles.foreach { f =>
    val name  = f.getName.stripSuffix(".wasm")
    val bytes = IO.readBytes(f)
    val hex   = bytes.iterator
      .map { b => val u = b & 0xff; f"0x$u%02x.toByte" }
      .grouped(12)
      .map(_.mkString("    ", ", ", ""))
      .mkString(",\n")
    sb.append(s"  /** Compiled from `fixtures/$name.wat`. */\n")
    sb.append(s"  val $name: Array[Byte] = Array[Byte](\n$hex,\n  )\n\n")
  }
  IO.write(outFile, sb.toString)
  log.info(s"wrote ${outFile.getName} (${wasmFiles.length} fixtures)")
  Seq(outFile)
}

// The library is pure Scala stdlib — no test framework, no external deps.
// Tests live in a `@main`-style runner (see InterpreterTest). Run on any platform:
//   sbt 'wasmJVM/Test/run'
//   sbt 'wasmJS/Test/run'
//   sbt 'wasmNative/Test/run'
lazy val wasm = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("."))
  .settings(
    name := "wasm",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
    ),
    publishMavenStyle      := true,
    Test / publishArtifact := false,
    // Source of truth = the committed `.wasm` binaries; Fixtures.scala is derived.
    Test / sourceGenerators += Def.task {
      val rootDir = (LocalRootProject / baseDirectory).value
      val fxDir   = rootDir / "shared" / "src" / "test" / "resources" / "fixtures"
      val outFile = (Test / sourceManaged).value / "io" / "github" / "edadma" / "wasm" / "Fixtures.scala"
      generateFixtures(outFile, fxDir, streams.value.log)
    }.taskValue,
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    scalaJSLinkerConfig ~= { _.withSourceMap(false) },
    // We don't use a test framework, so the JS linker needs to know `Test/run`
    // is firing a normal main module rather than a test-module initializer.
    // sbt auto-discovers the single main class — no explicit `mainClass`.
    Test / scalaJSUseMainModuleInitializer := true,
    Test / scalaJSUseTestModuleInitializer := false,
    jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
  )
  .nativeSettings(
    // sbt-scala-native's default `Test/run` launches its TestMain runner
    // (designed for test frameworks like scalatest). Without a framework
    // installed we have to point it at our own entry point. The Test scope
    // still discovers `InterpreterTest` via the usual mechanism on JVM/JS;
    // Native is the only platform that needs the hint.
    Test / mainClass := Some("io.github.edadma.wasm.InterpreterTest"),
  )

lazy val root = project
  .in(file("."))
  .aggregate(wasm.js, wasm.jvm, wasm.native)
  .settings(
    name                := "wasm",
    publish / skip      := true,
    publishLocal / skip := true,
  )
