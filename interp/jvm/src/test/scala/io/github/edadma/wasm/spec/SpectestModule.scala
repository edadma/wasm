package io.github.edadma.wasm.spec

import io.github.edadma.wasm.*

/** Minimal stand-in for the spec testsuite's `spectest` host module.
  *
  * The W3C testsuite imports `spectest.print_*` from a handful of `.wast`
  * files (`func_ptrs`, `imports`, etc.) for side-effect printing. The
  * full spec also exports a `memory`, `table`, `global_*` set — those
  * require multi-module instantiation plumbing the runner doesn't have
  * yet, so for now only the print family is wired in. Files that need
  * the structural imports stay pinned in [[KnownFailures]].
  */
private[spec] object SpectestModule extends HostModule:
  val name: String = "spectest"

  override val functions: Map[String, HostFunc] = Map(
    "print"          -> { (_, _) => Seq.empty },
    "print_i32"      -> { (_, _) => Seq.empty },
    "print_i64"      -> { (_, _) => Seq.empty },
    "print_f32"      -> { (_, _) => Seq.empty },
    "print_f64"      -> { (_, _) => Seq.empty },
    "print_i32_f32"  -> { (_, _) => Seq.empty },
    "print_f64_f64"  -> { (_, _) => Seq.empty },
  )
