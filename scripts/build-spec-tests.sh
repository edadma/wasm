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
