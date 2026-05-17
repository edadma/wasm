package io.github.edadma.wasm.cli

/** Static build identity for the CLI. Hand-rolled rather than wired through
  * sbt-buildinfo to keep the dependency surface at zero on the interpreter
  * side; the version literal lives next to `ThisBuild / version` in build.sbt.
  *
  * TODO: replace with sbt-buildinfo if and when more than `version` is needed
  * (commit hash, build time, etc.).
  */
object BuildInfo:
  val version: String = "0.4.0"
