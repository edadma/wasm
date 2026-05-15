import xerial.sbt.Sonatype.sonatypeCentralHost

// ============================================================================
// Build-wide settings — apply to every sub-project.
// ============================================================================

ThisBuild / licenses               := Seq("ISC" -> url("https://opensource.org/licenses/ISC"))
ThisBuild / versionScheme          := Some("semver-spec")
ThisBuild / evictionErrorLevel     := Level.Warn
ThisBuild / scalaVersion           := "3.8.3"
ThisBuild / organization           := "io.github.edadma"
ThisBuild / organizationName       := "edadma"
ThisBuild / organizationHomepage   := Some(url("https://github.com/edadma"))
ThisBuild / version                := "0.1.1"
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

val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wnonunit-statement",
  "-Wvalue-discard",
)

// ============================================================================
// Fixture generator — derives `Fixtures.scala` from the committed .wasm
// binaries in interp/shared/src/test/resources/fixtures. Wired into the
// `interp` cross-project's Test source generators so it re-runs every compile.
// ============================================================================

def generateFixtures(outFile: File, fixturesDir: File, log: Logger, pkg: String, obj: String, srcLabel: String): Seq[File] = {
  val wasmFiles = Option(fixturesDir.listFiles()).getOrElse(Array.empty[File])
    .filter(_.getName.endsWith(".wasm"))
    .sortBy(_.getName)
  // Each `Array[Byte](b0, b1, ...)` element compiles to ~10 bytes of JVM
  // bytecode (dup / push-index / push-value / i2b / bastore), so a 40KB
  // fixture would generate >400KB of <clinit> bytecode and trip the 64KB
  // method-size limit. We base64-encode each fixture as one or more string
  // constants and decode at class-init time. Each string stays under 60K
  // chars to keep the constant pool's per-entry UTF-8 byte budget happy
  // (65535 bytes; base64 is ASCII so 1:1).
  val ChunkChars = 60000
  val sb = new StringBuilder
  sb.append(s"package $pkg\n\n")
  // Note: Scala supports nested block comments, so we have to avoid producing
  // a `/*` substring inside the generated docstring (e.g. `fixtures/*.wasm`).
  sb.append(s"/** Generated from the .wasm files in $srcLabel.\n")
  sb.append("  *\n")
  sb.append("  * Do not edit by hand — change a `.wat`, run `wat2wasm` to refresh the `.wasm`,\n")
  sb.append("  * and this file rebuilds on the next `sbt compile`.\n")
  sb.append("  *\n")
  sb.append("  * Fixtures are stored as base64-encoded string constants (chunked into\n")
  sb.append("  * pieces that fit a single JVM constant-pool UTF-8 entry) and decoded\n")
  sb.append("  * once at class init via `java.util.Base64`. This avoids the 64KB JVM\n")
  sb.append("  * method-size limit that the previous `Array[Byte](...)` literal form\n")
  sb.append("  * hit on real-world fixtures (rustc-built wasi binaries, ~40KB+).\n")
  sb.append("  */\n")
  sb.append(s"object $obj:\n\n")
  sb.append("  private val decoder = java.util.Base64.getDecoder\n\n")
  sb.append("  private def decode(parts: String*): Array[Byte] =\n")
  sb.append("    val bufs  = parts.map(decoder.decode)\n")
  sb.append("    val total = bufs.iterator.map(_.length).sum\n")
  sb.append("    val out   = new Array[Byte](total)\n")
  sb.append("    var off   = 0\n")
  sb.append("    bufs.foreach { b =>\n")
  sb.append("      System.arraycopy(b, 0, out, off, b.length)\n")
  sb.append("      off += b.length\n")
  sb.append("    }\n")
  sb.append("    out\n\n")
  wasmFiles.foreach { f =>
    val name   = f.getName.stripSuffix(".wasm")
    val bytes  = IO.readBytes(f)
    val b64    = java.util.Base64.getEncoder.encodeToString(bytes)
    val chunks = b64.grouped(ChunkChars).toSeq
    sb.append(s"  /** Compiled from `fixtures/$name.wat`. */\n")
    sb.append(s"  val $name: Array[Byte] = decode(\n")
    val q = "\""
    chunks.foreach(c => sb.append("    " + q + c + q + ",\n"))
    sb.append("  )\n\n")
  }
  IO.write(outFile, sb.toString)
  log.info(s"wrote ${outFile.getName} (${wasmFiles.length} fixtures)")
  Seq(outFile)
}

// ============================================================================
// `interp` — the interpreter library. Zero external dependencies (only Scala
// stdlib). Tests use a hand-rolled PASS/FAIL runner so the test code itself
// has no test-framework dep either.
//
//   sbt 'interpJVM/Test/run'
//   sbt 'interpJS/Test/run'
//   sbt 'interpNative/Test/run'
// ============================================================================

// The sub-project lives in `interp/`, but the published artifact name is
// just `wasm` — the directory name is a code-organization detail, the
// `name` setting is what ends up in the pom.
lazy val interp = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("interp"))
  .settings(
    name := "wasm",
    scalacOptions ++= commonScalacOptions,
    publishMavenStyle      := true,
    Test / publishArtifact := false,
    // Generate Fixtures.scala from the committed .wasm binaries on every compile.
    Test / sourceGenerators += Def.task {
      val rootDir = (LocalRootProject / baseDirectory).value
      val fxDir   = rootDir / "interp" / "shared" / "src" / "test" / "resources" / "fixtures"
      val outFile = (Test / sourceManaged).value / "io" / "github" / "edadma" / "wasm" / "Fixtures.scala"
      generateFixtures(
        outFile, fxDir, streams.value.log,
        pkg      = "io.github.edadma.wasm",
        obj      = "Fixtures",
        srcLabel = "interp/shared/src/test/resources/fixtures",
      )
    }.taskValue,
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    scalaJSLinkerConfig ~= { _.withSourceMap(false) },
    Test / scalaJSUseMainModuleInitializer := true,
    Test / scalaJSUseTestModuleInitializer := false,
    jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
  )
  .nativeSettings(
    // sbt-scala-native's default `Test/run` launches the TestMain framework
    // runner; we point it at our own zero-dep test main instead.
    Test / mainClass := Some("io.github.edadma.wasm.InterpreterTest"),
  )

// ============================================================================
// `cli` — command-line interface using scopt. Depends on `interp`; JS/JVM/Native
// each have a small Main.scala that supplies the file-reading + exit primitives;
// the parsing and dispatch are shared.
//
//   sbt 'cliJVM/run examples/hello.wasm'
//   sbt 'cliJS/run examples/hello.wasm'        (Node.js)
//   sbt 'cliNative/run examples/hello.wasm'
// ============================================================================

lazy val cli = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("cli"))
  .dependsOn(interp, wasi)
  .settings(
    name := "wasm-cli",
    scalacOptions ++= commonScalacOptions,
    // The interpreter library is the published artifact; the CLI is a runner
    // built on top of it and is not published to Sonatype.
    publish / skip      := true,
    publishLocal / skip := true,
    // scopt is the only external dep in the whole project, and it's confined
    // to the CLI module — the interp library stays zero-dep.
    libraryDependencies += "com.github.scopt" %%% "scopt" % "4.1.0",
  )
  .jvmSettings(
    // Hand-rolled `Test/run` main, same convention as interp / wasi. Tests
    // exercise the dispatch logic (legacy main mode, _start auto-detect,
    // proc_exit propagation, explicit --invoke override) against committed
    // wasi fixtures resolved via project-relative paths — JVM-only because
    // those paths don't survive into the JS / Native test classpaths.
    Test / mainClass := Some("io.github.edadma.wasm.cli.CliJvmTests"),
  )
  .jsSettings(
    // CommonJS keeps the linker output runnable as a plain `node main.js`
    // call, which is how end users invoke the JS CLI. With CommonJSModule
    // and `scalaJSUseMainModuleInitializer := true`, the link output is a
    // self-contained script: `node main.js examples/hello.wasm` works.
    //
    // Note: `sbt cliJS/run <args>` does NOT forward args (sbt's command
    // parser for scalajs's run task rejects positional args). Link with
    // `sbt cliJS/fastLinkJS` and call node directly to pass args.
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    scalaJSLinkerConfig ~= { _.withSourceMap(false) },
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass             := Some("io.github.edadma.wasm.cli.Main"),
    jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
  )

// ============================================================================
// `wasi` — WASI Preview 1 host shim. Implements the `wasi_snapshot_preview1`
// import module so real wasi-compiled programs (Rust, Zig, C with wasi-sdk,
// AssemblyScript) can run end-to-end on top of `interp`. Depends on `interp`;
// stays zero-external-dep (the test runner is the same hand-rolled one).
//
//   sbt 'wasiJVM/Test/run'
//   sbt 'wasiJS/Test/run'
//   sbt 'wasiNative/Test/run'
// ============================================================================

lazy val wasi = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("wasi"))
  .dependsOn(interp)
  .settings(
    name := "wasm-wasi",
    scalacOptions ++= commonScalacOptions,
    publishMavenStyle      := true,
    Test / publishArtifact := false,
    Test / sourceGenerators += Def.task {
      val rootDir = (LocalRootProject / baseDirectory).value
      val fxDir   = rootDir / "wasi" / "shared" / "src" / "test" / "resources" / "fixtures"
      val outFile = (Test / sourceManaged).value / "io" / "github" / "edadma" / "wasm" / "wasi" / "WasiFixtures.scala"
      generateFixtures(
        outFile, fxDir, streams.value.log,
        pkg      = "io.github.edadma.wasm.wasi",
        obj      = "WasiFixtures",
        srcLabel = "wasi/shared/src/test/resources/fixtures",
      )
    }.taskValue,
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    scalaJSLinkerConfig ~= { _.withSourceMap(false) },
    Test / scalaJSUseMainModuleInitializer := true,
    Test / scalaJSUseTestModuleInitializer := false,
    jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
  )
  .nativeSettings(
    Test / mainClass := Some("io.github.edadma.wasm.wasi.WasiTest"),
  )

// ============================================================================
// Aggregator — `sbt compile` / `sbt test` operate on everything.
// ============================================================================

lazy val root = project
  .in(file("."))
  .aggregate(
    interp.js, interp.jvm, interp.native,
    wasi.js,   wasi.jvm,   wasi.native,
    cli.js,    cli.jvm,    cli.native,
  )
  .settings(
    name                := "wasm",
    publish / skip      := true,
    publishLocal / skip := true,
  )
