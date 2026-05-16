package io.github.edadma.wasm.spec

import io.github.edadma.wasm.*

/** Minimal stand-in for the spec testsuite's `spectest` host module.
  *
  * The W3C testsuite imports a small set of canonical `spectest.*` names
  * from a handful of `.wast` files (`global`, `imports`, `exports`,
  * `func_ptrs`, ...). Three categories of import:
  *
  *   - `print_*` — side-effecting host functions used to confirm a value
  *     reached the host. We accept the call and discard the args.
  *   - `global_{i32,i64,f32,f64}` — typed immutable global constants,
  *     fixed values per the spec runner convention (i32=666, i64=666,
  *     f32=666.6, f64=666.6).
  *   - `memory`, `table` — structural imports still gate `linking`-style
  *     manifests; until the runner has multi-module instantiation
  *     plumbing those files stay pinned in [[KnownFailures]].
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

  override val globals: Map[String, HostGlobal] = Map(
    "global_i32" -> HostGlobal(ValueType.I32Type, mutable = false, I32(666)),
    "global_i64" -> HostGlobal(ValueType.I64Type, mutable = false, I64(666L)),
    "global_f32" -> HostGlobal(ValueType.F32Type, mutable = false, F32(666.6f)),
    "global_f64" -> HostGlobal(ValueType.F64Type, mutable = false, F64(666.6)),
  )
