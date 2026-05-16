#!/usr/bin/env bash
# Re-generate the JSON manifests + .wasm modules under
# interp/jvm/src/test/resources/spec/ from a local checkout of
# https://github.com/WebAssembly/testsuite.
#
# Usage:
#   ./scripts/build-spec-tests.sh /path/to/wasm-testsuite
#
# The set of .wast files we run against is curated below. Each one must
# convert cleanly with `wast2json --enable-all`. Add a file here only after
# confirming our interpreter passes (or knowingly fails — see
# SpecRunner.KnownFailures).

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 /path/to/wasm-testsuite" >&2
  exit 2
fi

TESTSUITE="$1"
if [[ ! -d "$TESTSUITE" ]]; then
  echo "error: '$TESTSUITE' is not a directory" >&2
  exit 2
fi

if ! command -v wast2json >/dev/null 2>&1; then
  echo "error: wast2json not found in PATH (install wabt — https://github.com/WebAssembly/wabt)" >&2
  exit 2
fi

OUT="$(cd "$(dirname "$0")/.." && pwd)/interp/jvm/src/test/resources/spec"
mkdir -p "$OUT"
rm -f "$OUT"/*.json "$OUT"/*.wasm "$OUT"/*.wat

# Curated slice — extend this list as more proposals come online. Every entry
# must convert cleanly with wast2json --enable-all and the interpreter must
# either pass it outright or have the test pinned in SpecRunner.KnownFailures.
FILES=(
  i32
  i64
  f32
  f64
  conversions
  endianness
  fac
  forward
  int_literals
  float_literals
  int_exprs
  nop
  unreachable
  const
  comments
  type
  inline-module
  func_ptrs
  stack
  switch
  labels
  call
  return
  block
  if
  loop
  br
  br_if
  br_table
  select
  unwind
  address
  align
  # --- SIMD --------------------------------------------------------------
  simd_address
  simd_align
  simd_bit_shift
  simd_bitwise
  simd_boolean
  simd_const
  simd_conversions
  simd_f32x4
  simd_f32x4_arith
  simd_f32x4_cmp
  simd_f32x4_pmin_pmax
  simd_f32x4_rounding
  simd_f64x2
  simd_f64x2_arith
  simd_f64x2_cmp
  simd_f64x2_pmin_pmax
  simd_f64x2_rounding
  simd_i8x16_arith
  simd_i8x16_arith2
  simd_i8x16_cmp
  simd_i8x16_sat_arith
  simd_i16x8_arith
  simd_i16x8_arith2
  simd_i16x8_cmp
  simd_i16x8_extadd_pairwise_i8x16
  simd_i16x8_extmul_i8x16
  simd_i16x8_q15mulr_sat_s
  simd_i16x8_sat_arith
  simd_i32x4_arith
  simd_i32x4_arith2
  simd_i32x4_cmp
  simd_i32x4_dot_i16x8
  simd_i32x4_extadd_pairwise_i16x8
  simd_i32x4_extmul_i16x8
  simd_i32x4_trunc_sat_f32x4
  simd_i32x4_trunc_sat_f64x2
  simd_i64x2_arith
  simd_i64x2_arith2
  simd_i64x2_cmp
  simd_i64x2_extmul_i32x4
  simd_int_to_int_extend
  simd_lane
  simd_load
  simd_load_extend
  simd_load_splat
  simd_load_zero
  simd_load8_lane
  simd_load16_lane
  simd_load32_lane
  simd_load64_lane
  simd_splat
  simd_store
  simd_store8_lane
  simd_store16_lane
  simd_store32_lane
  simd_store64_lane
  simd_select
)

for name in "${FILES[@]}"; do
  src="$TESTSUITE/$name.wast"
  if [[ ! -f "$src" ]]; then
    echo "  skip   $name.wast (not present)" >&2
    continue
  fi
  out="$OUT/$name.json"
  if wast2json --enable-all "$src" -o "$out" 2>/dev/null; then
    echo "  built  $name.wast"
  else
    echo "  FAILED $name.wast (wast2json rejected it)" >&2
    rm -f "$OUT/${name}".*
  fi
done

echo
echo "Wrote spec fixtures to $OUT"
echo "Run:  sbt 'interpJVM/Test/runMain io.github.edadma.wasm.spec.SpecComplianceTests'"
